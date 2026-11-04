package app.foldersync.blobs

import app.foldersync.common.args
import app.foldersync.common.ApiException
import app.foldersync.spaces.SpaceService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

data class BlobRecord(val id: String, val spaceId: String, val ownerId: String, val size: Long, val sha256: String, val createdAt: Long)

/**
 * Reference rules, in one place. A blob may be *read* only through a current entry, an unresolved conflict candidate
 * or an unexpired transfer lease. It is *retained* (not yet collectable) additionally while the upload that produced
 * it is complete but not yet consumed by a commit. Release is therefore immediate and derived: replacing or deleting
 * an entry, resolving a conflict, or letting a lease lapse makes the blob unreadable at once, before any bytes are
 * physically removed.
 */
object BlobReferences {
    private const val CURRENT_ENTRY = "EXISTS (SELECT 1 FROM entries e WHERE e.blob_id = :b AND e.deleted = 0)"
    private const val OPEN_CONFLICT = "EXISTS (SELECT 1 FROM conflicts c WHERE c.state = 'UNRESOLVED' AND (c.local_blob_id = :b OR c.server_blob_id = :b))"
    private const val ACTIVE_LEASE = "EXISTS (SELECT 1 FROM transfer_leases l WHERE l.released = 0 AND l.expires_at > :now AND " +
        "(l.blob_id = :b OR (l.blob_id IS NULL AND EXISTS (SELECT 1 FROM plan_operations o WHERE o.plan_id = l.operation_id AND o.blob_id = :b))))"
    private const val PENDING_UPLOAD = "EXISTS (SELECT 1 FROM uploads u WHERE u.blob_id = :b AND u.state = 'COMPLETE' AND u.consumed = 0 AND u.expires_at > :now)"

    const val READABLE = "SELECT ($CURRENT_ENTRY OR $OPEN_CONFLICT OR $ACTIVE_LEASE)"
    const val RETAINED = "SELECT ($CURRENT_ENTRY OR $OPEN_CONFLICT OR $ACTIVE_LEASE OR $PENDING_UPLOAD)"

    /** Candidates for collection: nothing retains them. Re-checked per blob inside a transaction before deleting. */
    const val UNREFERENCED = """
        SELECT b.id FROM blobs b
        WHERE NOT EXISTS (SELECT 1 FROM entries e WHERE e.blob_id = b.id AND e.deleted = 0)
          AND NOT EXISTS (SELECT 1 FROM conflicts c WHERE c.state = 'UNRESOLVED' AND (c.local_blob_id = b.id OR c.server_blob_id = b.id))
          AND NOT EXISTS (SELECT 1 FROM transfer_leases l WHERE l.released = 0 AND l.expires_at > :now AND
                (l.blob_id = b.id OR (l.blob_id IS NULL AND EXISTS (SELECT 1 FROM plan_operations o WHERE o.plan_id = l.operation_id AND o.blob_id = b.id))))
          AND NOT EXISTS (SELECT 1 FROM uploads u WHERE u.blob_id = b.id AND u.state = 'COMPLETE' AND u.consumed = 0 AND u.expires_at > :now)
        LIMIT :limit"""
}

@Service
class BlobService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val spaces: SpaceService,
    private val store: BlobStore,
) {
    fun register(id: String, spaceId: String, ownerId: String, size: Long, sha256: String) {
        jdbc.sql("INSERT INTO blobs (id, space_id, owner_id, size, sha256, created_at) VALUES (?,?,?,?,?,?)")
            .args(id, spaceId, ownerId, size, sha256, clock.millis()).update()
    }

    fun find(id: String): BlobRecord? =
        jdbc.sql("SELECT id, space_id, owner_id, size, sha256, created_at FROM blobs WHERE id = ?").param(id)
            .query { rs, _ -> BlobRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6)) }
            .optional().orElse(null)

    fun isReadable(blobId: String): Boolean = ask(BlobReferences.READABLE, blobId)

    fun isRetained(blobId: String): Boolean = ask(BlobReferences.RETAINED, blobId)

    private fun ask(sql: String, blobId: String): Boolean =
        jdbc.sql(sql).param("b", blobId).param("now", clock.millis()).query(Int::class.java).single() == 1

    /**
     * Authorizes one download. Another user's space or blob is `not_found`. The owner's own blob that no current
     * entry, open conflict or live lease references is `content_unavailable` (HTTP 410) even if the file still sits
     * on disk awaiting cleanup: the caller must replan against current state.
     */
    fun authorizeDownload(spaceId: String, blobId: String, ownerId: String): BlobRecord {
        spaces.require(spaceId, ownerId)
        if (!blobId.matches(Regex("^[0-9a-f]{32}$"))) throw ApiException.notFound("blob")
        val blob = find(blobId)?.takeIf { it.spaceId == spaceId && it.ownerId == ownerId } ?: throw ApiException.notFound("blob")
        if (!isReadable(blobId)) {
            throw ApiException.gone("content_unavailable", "This content is no longer current; refresh and replan", mapOf("replan" to true))
        }
        return blob
    }

    fun store() = store
}
