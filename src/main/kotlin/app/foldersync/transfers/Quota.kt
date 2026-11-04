package app.foldersync.transfers

import app.foldersync.common.args
import app.foldersync.common.ApiException
import app.foldersync.common.Ids
import app.foldersync.identity.UserService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

data class QuotaUsage(val committedBytes: Long, val reservedBytes: Long, val quotaBytes: Long?) {
    val totalBytes: Long get() = committedBytes + reservedBytes
}

/**
 * Quota accounting across all of an owner's spaces. Usage is *derived*: current file entries, plus unresolved conflict
 * candidates not already counted as an entry, plus bytes reserved for staged transfers. Reservations are taken inside
 * the caller's transaction, which SQLite serializes with every other writer, so concurrent uploads in different
 * spaces cannot jointly exceed the limit. A reservation converts to committed usage when the upload is published and
 * is released on cancellation or expiry. An unset quota never rejects; lowering a quota below usage rejects only
 * requests that would add bytes and never touches existing data.
 */
@Service
class QuotaService(private val jdbc: JdbcClient, private val users: UserService, private val clock: Clock) {

    fun usage(ownerId: String): QuotaUsage {
        val entries = jdbc.sql(
            "SELECT COALESCE(SUM(e.size), 0) FROM entries e JOIN spaces s ON s.id = e.space_id WHERE s.owner_id = ? AND e.deleted = 0 AND e.type = 'FILE'",
        ).param(ownerId).query(Long::class.java).single()
        val candidates = jdbc.sql(
            """SELECT COALESCE(SUM(b.size), 0) FROM blobs b
               WHERE b.id IN (SELECT local_blob_id FROM conflicts WHERE owner_id = :o AND state = 'UNRESOLVED' AND local_blob_id IS NOT NULL
                              UNION SELECT server_blob_id FROM conflicts WHERE owner_id = :o AND state = 'UNRESOLVED' AND server_blob_id IS NOT NULL)
                 AND NOT EXISTS (SELECT 1 FROM entries e WHERE e.blob_id = b.id AND e.deleted = 0)""",
        ).param("o", ownerId).query(Long::class.java).single()
        val reserved = jdbc.sql("SELECT COALESCE(SUM(bytes), 0) FROM quota_reservations WHERE owner_id = ? AND state = 'RESERVED'")
            .param(ownerId).query(Long::class.java).single()
        return QuotaUsage(entries + candidates, reserved, users.quotaOf(ownerId))
    }

    /** Must run inside a transaction. Throws `quota_exceeded` without side effects when the bytes do not fit. */
    fun reserve(ownerId: String, spaceId: String, uploadId: String, bytes: Long): String {
        val u = usage(ownerId)
        val quota = u.quotaBytes
        if (quota != null && u.totalBytes + bytes > quota) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "quota_exceeded", "This upload would exceed your storage quota",
                details = mapOf("quotaBytes" to quota, "usedBytes" to u.totalBytes, "requestedBytes" to bytes),
            )
        }
        val id = Ids.uuid()
        jdbc.sql("INSERT INTO quota_reservations (id, owner_id, space_id, upload_id, bytes, state, created_at) VALUES (?,?,?,?,?, 'RESERVED', ?)")
            .args(id, ownerId, spaceId, uploadId, bytes, clock.millis()).update()
        return id
    }

    /** The upload's blob is now referenced by an entry or conflict, which count as committed usage. */
    fun convert(uploadId: String) {
        jdbc.sql("UPDATE quota_reservations SET state = 'COMMITTED' WHERE upload_id = ? AND state = 'RESERVED'").param(uploadId).update()
    }

    fun release(uploadId: String) {
        jdbc.sql("UPDATE quota_reservations SET state = 'RELEASED' WHERE upload_id = ? AND state = 'RESERVED'").param(uploadId).update()
    }
}
