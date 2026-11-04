package app.foldersync.spaces

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.common.Ids
import app.foldersync.config.Tx
import app.foldersync.revisions.EpochService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

data class SpaceRecord(
    val id: String,
    val ownerId: String,
    val name: String,
    val revision: Long,
    val policyRevision: Long,
    val createdAt: Long,
)

data class DeviceRecord(val id: String, val ownerId: String, val label: String, val lastSeen: Long?, val revoked: Boolean, val createdAt: Long)

enum class BindingStatus { ACTIVE, DISCONNECTED, SUPERSEDED, REVOKED }

data class BindingRecord(
    val id: String,
    val ownerId: String,
    val deviceId: String,
    val spaceId: String,
    val status: BindingStatus,
    val enrollmentGeneration: Long,
    val createdAt: Long,
)

/**
 * Every lookup takes the caller's user id and answers `not_found` for resources that exist but belong to someone else,
 * so known identifiers reveal nothing. Administrators get no exemption: there is no cross-owner query here.
 */
@Service
class SpaceService(private val jdbc: JdbcClient, private val clock: Clock, private val audit: AuditService) {

    fun create(ownerId: String, rawName: String): SpaceRecord {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 100 || name.any { it.code < 0x20 || it.code == 0x7f }) {
            throw ApiException.invalid("invalid_space_name", "Space names must be 1-100 characters without control characters")
        }
        val id = Ids.uuid()
        val now = clock.millis()
        try {
            jdbc.sql("INSERT INTO spaces (id, owner_id, name, created_at) VALUES (?,?,?,?)").args(id, ownerId, name, now).update()
        } catch (e: DataIntegrityViolationException) {
            throw ApiException.conflict("space_name_taken", "You already have a space with that name")
        }
        audit.record(ownerId, "space.created", id)
        return SpaceRecord(id, ownerId, name, 0, 0, now)
    }

    fun list(ownerId: String): List<SpaceRecord> =
        jdbc.sql("$SELECT WHERE owner_id = ? ORDER BY name").param(ownerId).query(::map).list()

    fun require(spaceId: String, ownerId: String): SpaceRecord =
        jdbc.sql("$SELECT WHERE id = ? AND owner_id = ?").args(spaceId, ownerId).query(::map).optional()
            .orElseThrow { ApiException.notFound("space") }

    private fun map(rs: java.sql.ResultSet, n: Int) = SpaceRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6))

    private companion object {
        const val SELECT = "SELECT id, owner_id, name, revision, policy_revision, created_at FROM spaces"
    }
}

@Service
class DeviceService(private val jdbc: JdbcClient, private val clock: Clock, private val audit: AuditService, private val tx: Tx) {

    fun create(ownerId: String, rawLabel: String?): DeviceRecord {
        val label = (rawLabel ?: "").trim().ifEmpty { "Unnamed device" }
        if (label.length > 80 || label.any { it.code < 0x20 || it.code == 0x7f }) {
            throw ApiException.invalid("invalid_device_label", "Device labels must be at most 80 characters without control characters")
        }
        val id = Ids.uuid()
        val now = clock.millis()
        jdbc.sql("INSERT INTO devices (id, owner_id, label, created_at) VALUES (?,?,?,?)").args(id, ownerId, label, now).update()
        audit.record(ownerId, "device.created", detail = mapOf("deviceId" to id))
        return DeviceRecord(id, ownerId, label, null, false, now)
    }

    fun list(ownerId: String): List<DeviceRecord> =
        jdbc.sql("$SELECT WHERE owner_id = ? ORDER BY created_at").param(ownerId).query(::map).list()

    fun require(deviceId: String, ownerId: String): DeviceRecord =
        jdbc.sql("$SELECT WHERE id = ? AND owner_id = ?").args(deviceId, ownerId).query(::map).optional()
            .orElseThrow { ApiException.notFound("device") }

    fun touch(deviceId: String) {
        jdbc.sql("UPDATE devices SET last_seen = ? WHERE id = ?").args(clock.millis(), deviceId).update()
    }

    /** Revoking a device also revokes its bindings, so its plans, commits and uploads stop working at once. */
    fun revoke(deviceId: String, ownerId: String) {
        require(deviceId, ownerId)
        tx.run {
            jdbc.sql("UPDATE devices SET revoked = 1 WHERE id = ?").param(deviceId).update()
            jdbc.sql("UPDATE bindings SET status = 'REVOKED', updated_at = ? WHERE device_id = ? AND status = 'ACTIVE'")
                .args(clock.millis(), deviceId).update()
        }
        audit.record(ownerId, "device.revoked", detail = mapOf("deviceId" to deviceId))
    }

    private fun map(rs: java.sql.ResultSet, n: Int) =
        DeviceRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4).takeUnless { rs.wasNull() }, rs.getInt(5) == 1, rs.getLong(6))

    private companion object {
        const val SELECT = "SELECT id, owner_id, label, last_seen, revoked, created_at FROM devices"
    }
}

@Service
class BindingService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val audit: AuditService,
    private val spaces: SpaceService,
    private val devices: DeviceService,
    private val epochs: EpochService,
    private val tx: Tx,
) {
    /**
     * Enrolls a device in a space. The space and the device must both belong to the caller, so a binding can never
     * join another user's device to this space (or the reverse). Each enrollment gets a higher generation than the
     * last; the previous active binding of the same device and space is superseded.
     */
    fun enroll(ownerId: String, spaceId: String, deviceId: String): BindingRecord {
        spaces.require(spaceId, ownerId)
        val device = devices.require(deviceId, ownerId)
        if (device.revoked) throw ApiException.conflict("device_revoked", "This device has been revoked")
        val id = Ids.uuid()
        val now = clock.millis()
        val generation = tx.run {
            jdbc.sql("UPDATE bindings SET status = 'SUPERSEDED', updated_at = ? WHERE device_id = ? AND space_id = ? AND status = 'ACTIVE'")
                .args(now, deviceId, spaceId).update()
            val next = jdbc.sql("SELECT COALESCE(MAX(enrollment_generation), 0) + 1 FROM bindings WHERE device_id = ? AND space_id = ?")
                .args(deviceId, spaceId).query(Long::class.java).single()
            jdbc.sql("INSERT INTO bindings (id, owner_id, device_id, space_id, status, enrollment_generation, created_at, updated_at) VALUES (?,?,?,?, 'ACTIVE', ?,?,?)")
                .args(id, ownerId, deviceId, spaceId, next, now, now).update()
            next
        }
        devices.touch(deviceId)
        audit.record(ownerId, "binding.enrolled", spaceId, mapOf("bindingId" to id))
        return BindingRecord(id, ownerId, deviceId, spaceId, BindingStatus.ACTIVE, generation, now)
    }

    fun listForSpace(ownerId: String, spaceId: String): List<BindingRecord> {
        spaces.require(spaceId, ownerId)
        return jdbc.sql("$SELECT WHERE space_id = ? AND owner_id = ? ORDER BY created_at").args(spaceId, ownerId).query(::map).list()
    }

    fun require(bindingId: String, ownerId: String): BindingRecord =
        jdbc.sql("$SELECT WHERE id = ? AND owner_id = ?").args(bindingId, ownerId).query(::map).optional()
            .orElseThrow { ApiException.notFound("binding") }

    /** A binding usable for new work: active, on the given space, with a device that is not revoked. */
    fun requireActive(bindingId: String, ownerId: String, spaceId: String): BindingRecord {
        val b = require(bindingId, ownerId)
        if (b.spaceId != spaceId) throw ApiException.notFound("binding")
        if (b.status != BindingStatus.ACTIVE) throw ApiException.conflict("binding_inactive", "This binding is no longer active; reconnect the folder")
        val device = devices.require(b.deviceId, ownerId)
        if (device.revoked) throw ApiException.conflict("device_revoked", "This device has been revoked")
        return b
    }

    /** Disconnect removes only the association; no files on either side are touched. */
    fun disconnect(ownerId: String, bindingId: String) {
        val b = require(bindingId, ownerId)
        jdbc.sql("UPDATE bindings SET status = 'DISCONNECTED', updated_at = ? WHERE id = ? AND status = 'ACTIVE'").args(clock.millis(), bindingId).update()
        audit.record(ownerId, "binding.disconnected", b.spaceId, mapOf("bindingId" to bindingId))
    }

    fun currentEpoch() = epochs.current()

    private fun map(rs: java.sql.ResultSet, n: Int) = BindingRecord(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
        BindingStatus.valueOf(rs.getString(5)), rs.getLong(6), rs.getLong(7),
    )

    private companion object {
        const val SELECT = "SELECT id, owner_id, device_id, space_id, status, enrollment_generation, created_at FROM bindings"
    }
}
