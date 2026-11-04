package app.foldersync.manifests

import app.foldersync.common.ApiException
import app.foldersync.config.FolderSyncProperties
import app.foldersync.revisions.EpochService
import app.foldersync.spaces.SpaceService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

data class ManifestEntry(
    val path: String,
    val type: String,
    val version: Long,
    val deleted: Boolean,
    val sha256: String?,
    val size: Long?,
    /** Present only while this snapshot state is still the current content; downloads are authorized separately. */
    val blobId: String?,
)

data class ManifestPage(
    val spaceId: String,
    /** The snapshot revision. Pass it back as `revision` to read the remaining pages of the same snapshot. */
    val revision: Long,
    val policyRevision: Long,
    val epoch: String,
    val entries: List<ManifestEntry>,
    /** Pass as `after` for the next page; null on the last page. */
    val nextAfter: String?,
)

data class ChangeEntry(val revision: Long, val path: String, val kind: String, val type: String, val sha256: String?, val size: Long?, val version: Long)

data class ChangeCursor(val revision: Long, val path: String)

data class ChangesPage(
    val spaceId: String,
    val epoch: String,
    val latestRevision: Long,
    val changes: List<ChangeEntry>,
    val next: ChangeCursor?,
)

/**
 * Stable paginated manifest snapshots and change feeds, reconstructed from the compact metadata-only change log.
 * A snapshot at revision R is the latest change per path at or below R, so it never shifts while a client pages
 * through it. It carries metadata only: file bytes of superseded states are not retained anywhere, so a snapshot
 * never promises content. Content for a plan is protected by that plan's lease; if a commit finds the entries
 * moved, the server answers `stale_plan` and the client replans from a fresh snapshot. Snapshots older than
 * `snapshot-max-lag` revisions are refused with `snapshot_expired` for the same reason.
 */
@Service
class ManifestService(
    private val jdbc: JdbcClient,
    private val props: FolderSyncProperties,
    private val spaces: SpaceService,
    private val epochs: EpochService,
) {
    fun manifest(ownerId: String, spaceId: String, revision: Long?, after: String?, limit: Int?, epoch: String?): ManifestPage {
        val space = spaces.require(spaceId, ownerId)
        if (epoch != null) epochs.require(epoch)
        val snapshot = revision ?: space.revision
        if (snapshot > space.revision) {
            throw ApiException.conflict("revision_ahead", "That revision does not exist on this server", details = mapOf("currentRevision" to space.revision, "recovery" to true))
        }
        if (snapshot < space.revision - props.snapshotMaxLag) {
            throw ApiException.gone("snapshot_expired", "That snapshot is too old; start again from the current revision", mapOf("currentRevision" to space.revision, "replan" to true))
        }
        val page = (limit ?: props.maxManifestBatch).coerceIn(1, props.maxManifestBatch)
        val rows = jdbc.sql(
            """SELECT c.path, c.type, c.entry_version, c.kind, c.sha256, c.size, e.blob_id
               FROM entry_changes c
               LEFT JOIN entries e ON e.space_id = c.space_id AND e.path = c.path AND e.version = c.entry_version AND e.deleted = 0
               WHERE c.space_id = :space AND c.path > :after
                 AND c.revision = (SELECT MAX(c2.revision) FROM entry_changes c2 WHERE c2.space_id = c.space_id AND c2.path = c.path AND c2.revision <= :rev)
               ORDER BY c.path LIMIT :limit""",
        ).param("space", spaceId).param("after", after ?: "").param("rev", snapshot).param("limit", page + 1)
            .query { rs, _ ->
                val deleted = rs.getString(4) == "DELETE"
                ManifestEntry(
                    rs.getString(1), rs.getString(2), rs.getLong(3), deleted, rs.getString(5),
                    rs.getLong(6).takeUnless { rs.wasNull() }, if (deleted) null else rs.getString(7),
                )
            }.list()
        val more = rows.size > page
        val entries = rows.take(page)
        return ManifestPage(spaceId, snapshot, space.policyRevision, epochs.current(), entries, if (more) entries.last().path else null)
    }

    fun changes(ownerId: String, spaceId: String, afterRevision: Long, afterPath: String?, limit: Int?, epoch: String?): ChangesPage {
        val space = spaces.require(spaceId, ownerId)
        if (epoch != null) epochs.require(epoch)
        if (afterRevision < 0) throw ApiException.bad("malformed_request", "after must not be negative")
        if (afterRevision > space.revision) {
            // A client ahead of the server means the server was restored or replaced: its baselines are not trustworthy.
            throw ApiException.conflict("revision_ahead", "The server is behind the revision you hold; recovery reconciliation is required",
                details = mapOf("currentRevision" to space.revision, "recovery" to true))
        }
        val page = (limit ?: props.maxManifestBatch).coerceIn(1, props.maxManifestBatch)
        // Without a path cursor the feed continues strictly after the revision; with one it resumes inside that revision.
        val cursor = if (afterPath == null) "revision > :rev" else "(revision > :rev OR (revision = :rev AND path > :path))"
        val rows = jdbc.sql(
            """SELECT revision, path, kind, type, sha256, size, entry_version FROM entry_changes
               WHERE space_id = :space AND $cursor
               ORDER BY revision, path LIMIT :limit""",
        ).param("space", spaceId).param("rev", afterRevision).apply { if (afterPath != null) param("path", afterPath) }.param("limit", page + 1)
            .query { rs, _ ->
                ChangeEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6).takeUnless { rs.wasNull() }, rs.getLong(7))
            }.list()
        val more = rows.size > page
        val changes = rows.take(page)
        return ChangesPage(spaceId, epochs.current(), space.revision, changes, if (more) ChangeCursor(changes.last().revision, changes.last().path) else null)
    }
}
