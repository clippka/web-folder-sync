package app.foldersync.revisions

import app.foldersync.common.args
import app.foldersync.common.ApiException
import app.foldersync.policy.IgnorePolicy
import app.foldersync.reconciliation.EntryState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

data class EntryRecord(
    val path: String,
    val type: String,
    val version: Long,
    val deleted: Boolean,
    val blobId: String?,
    val sha256: String?,
    val size: Long?,
    val lastRevision: Long,
) {
    /** A tombstone is Absent for planning; its version still guards against writes based on older state. */
    fun state(): EntryState = when {
        deleted -> EntryState.Absent
        type == "DIRECTORY" -> EntryState.Directory
        else -> EntryState.File(sha256!!, size!!)
    }
}

fun EntryRecord?.stateOrAbsent(): EntryState = this?.state() ?: EntryState.Absent
fun EntryRecord?.versionOrZero(): Long = this?.version ?: 0L

@Service
class EntryStore(private val jdbc: JdbcClient) {
    fun find(spaceId: String, path: String): EntryRecord? =
        jdbc.sql("$SELECT WHERE space_id = ? AND path = ?").args(spaceId, path).query(::map).optional().orElse(null)

    /** Every entry including tombstones. A 25,000-file space is a few megabytes; planning needs it all at once. */
    fun all(spaceId: String): List<EntryRecord> =
        jdbc.sql("$SELECT WHERE space_id = ? ORDER BY path").param(spaceId).query(::map).list()

    fun controlFiles(spaceId: String): List<EntryRecord> =
        jdbc.sql("$SELECT WHERE space_id = ? AND deleted = 0 AND type = 'FILE' AND (path IN ('.gitignore','.syncignore') OR path LIKE '%/.gitignore' OR path LIKE '%/.syncignore')")
            .param(spaceId).query(::map).list()

    fun activeFileCount(spaceId: String): Int =
        jdbc.sql("SELECT COUNT(*) FROM entries WHERE space_id = ? AND deleted = 0 AND type = 'FILE'").param(spaceId).query(Int::class.java).single()

    fun hasLiveChildren(spaceId: String, path: String): Boolean =
        jdbc.sql("SELECT 1 FROM entries WHERE space_id = ? AND path > ? AND path < ? AND deleted = 0 LIMIT 1")
            // Every child sorts strictly between "path/" and "path0" in SQLite's byte-wise ordering ('/' + 1 == '0').
            .args(spaceId, "$path/", path + "0").query(Int::class.java).optional().isPresent

    private fun map(rs: java.sql.ResultSet, n: Int) = EntryRecord(
        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) == 1, rs.getString(5),
        rs.getString(6), rs.getLong(7).takeUnless { rs.wasNull() }, rs.getLong(8),
    )

    private companion object {
        const val SELECT = "SELECT path, type, version, deleted, blob_id, sha256, size, last_revision FROM entries"
    }
}

/** One atomic revision of a space. Obtain through [RevisionWriter.open] inside a transaction. */
class RevisionBatch internal constructor(val spaceId: String, val number: Long, private val jdbc: JdbcClient, private val clock: Clock, private val store: EntryStore) {
    var policyChanged = false
        private set
    var changes = 0
        private set

    /**
     * Writes [newState] for [path] and records the metadata-only change. Returns the new entry version.
     * Callers have already checked their optimistic preconditions; this enforces the structural ones: the parent must
     * be a live directory, a directory with live children cannot become a file or disappear.
     */
    fun put(path: String, newState: EntryState, blobId: String?, mtime: Long? = null): Long {
        val existing = store.find(spaceId, path)
        val now = clock.millis()
        if (newState != EntryState.Absent) requireParent(path)
        if (existing != null && !existing.deleted && existing.type == "DIRECTORY" && newState !is EntryState.Directory && store.hasLiveChildren(spaceId, path)) {
            throw ApiException.stale("Directory '$path' still has entries and cannot be replaced or removed", path = path)
        }
        val version = (existing?.version ?: 0) + 1
        val (type, sha, size) = when (newState) {
            is EntryState.File -> Triple("FILE", newState.sha256, newState.size)
            EntryState.Directory -> Triple("DIRECTORY", null, null)
            EntryState.Absent -> Triple(existing?.type ?: "FILE", null, null)
        }
        val deleted = newState == EntryState.Absent
        val kind = when {
            deleted -> "DELETE"
            existing == null || existing.deleted -> "CREATE"
            else -> "UPDATE"
        }
        if (existing == null) {
            jdbc.sql("INSERT INTO entries (space_id, path, type, version, deleted, blob_id, sha256, size, source_mtime, last_revision, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
                .args(spaceId, path, type, version, if (deleted) 1 else 0, if (deleted) null else blobId, sha, size, mtime, number, now).update()
        } else {
            jdbc.sql("UPDATE entries SET type = ?, version = ?, deleted = ?, blob_id = ?, sha256 = ?, size = ?, source_mtime = ?, last_revision = ?, updated_at = ? WHERE space_id = ? AND path = ?")
                .args(type, version, if (deleted) 1 else 0, if (deleted) null else blobId, sha, size, mtime, number, now, spaceId, path).update()
        }
        jdbc.sql("INSERT INTO entry_changes (space_id, revision, path, kind, type, sha256, size, entry_version) VALUES (?,?,?,?,?,?,?,?)")
            .args(spaceId, number, path, kind, type, sha, size, version).update()
        if (IgnorePolicy.isControlFile(path)) policyChanged = true
        changes++
        return version
    }

    private fun requireParent(path: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isEmpty()) return
        val p = store.find(spaceId, parent)
        if (p == null || p.deleted || p.type != "DIRECTORY") {
            throw ApiException.stale("Parent directory '$parent' of '$path' does not exist on the server", path = path)
        }
    }
}

@Service
class RevisionWriter(private val jdbc: JdbcClient, private val clock: Clock, private val store: EntryStore) {

    /** Allocates the next revision number for the space. Must run inside the committing transaction. */
    fun open(spaceId: String, actorUserId: String, bindingId: String?, operationId: String?): RevisionBatch {
        val n = jdbc.sql("UPDATE spaces SET revision = revision + 1 WHERE id = ? RETURNING revision").param(spaceId).query(Long::class.java).single()
        jdbc.sql("INSERT INTO revisions (space_id, number, actor_user_id, binding_id, operation_id, created_at) VALUES (?,?,?,?,?,?)")
            .args(spaceId, n, actorUserId, bindingId, operationId, clock.millis()).update()
        return RevisionBatch(spaceId, n, jdbc, clock, store)
    }

    /** Finishes a batch: bumps the policy revision when an ignore control file changed. */
    fun close(batch: RevisionBatch): Long? {
        if (!batch.policyChanged) return null
        return jdbc.sql("UPDATE spaces SET policy_revision = policy_revision + 1 WHERE id = ? RETURNING policy_revision")
            .param(batch.spaceId).query(Long::class.java).single()
    }
}
