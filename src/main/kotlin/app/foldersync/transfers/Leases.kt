package app.foldersync.transfers

import app.foldersync.common.args
import app.foldersync.common.ApiException
import app.foldersync.common.Ids
import app.foldersync.config.FolderSyncProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

data class LeaseDto(val id: String, val operationId: String, val spaceId: String, val expiresAt: Long)

/**
 * A lease is temporary permission to read the blobs an operation (a plan) lists, so an in-flight download survives a
 * concurrent replacement of the current entry. It is bounded: renewal extends the expiry by the lease TTL but never
 * beyond [FolderSyncProperties.leaseMaxLifetime] after creation. An expired lease is gone for good; the client
 * replans against current state. Leases never make bytes readable by anyone but their owner.
 */
@Service
class LeaseService(private val jdbc: JdbcClient, private val props: FolderSyncProperties, private val clock: Clock) {

    /** Called inside the plan transaction. */
    fun createForOperation(ownerId: String, spaceId: String, operationId: String): LeaseDto {
        val id = Ids.uuid()
        val now = clock.millis()
        val expires = now + props.leaseTtl.toMillis()
        jdbc.sql("INSERT INTO transfer_leases (id, owner_id, space_id, operation_id, created_at, expires_at) VALUES (?,?,?,?,?,?)")
            .args(id, ownerId, spaceId, operationId, now, expires).update()
        return LeaseDto(id, operationId, spaceId, expires)
    }

    fun renew(ownerId: String, leaseId: String): LeaseDto {
        val row = jdbc.sql("SELECT operation_id, space_id, created_at, expires_at, released FROM transfer_leases WHERE id = ? AND owner_id = ?")
            .args(leaseId, ownerId)
            .query { rs, _ -> LeaseRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5) == 1) }
            .optional().orElseThrow { ApiException.notFound("lease") }
        val now = clock.millis()
        if (row.released || row.expiresAt <= now) {
            throw ApiException.gone("lease_expired", "This transfer lease expired; replan against current state", mapOf("replan" to true))
        }
        val expires = maxOf(row.expiresAt, minOf(now + props.leaseTtl.toMillis(), row.createdAt + props.leaseMaxLifetime.toMillis()))
        jdbc.sql("UPDATE transfer_leases SET expires_at = ? WHERE id = ?").args(expires, leaseId).update()
        return LeaseDto(leaseId, row.operationId, row.spaceId, expires)
    }

    fun release(ownerId: String, leaseId: String) {
        val n = jdbc.sql("UPDATE transfer_leases SET released = 1 WHERE id = ? AND owner_id = ?").args(leaseId, ownerId).update()
        if (n == 0) throw ApiException.notFound("lease")
    }

    fun deleteLapsed(): Int =
        jdbc.sql("DELETE FROM transfer_leases WHERE released = 1 OR expires_at <= ?").param(clock.millis()).update()

    private data class LeaseRow(val operationId: String, val spaceId: String, val createdAt: Long, val expiresAt: Long, val released: Boolean)
}
