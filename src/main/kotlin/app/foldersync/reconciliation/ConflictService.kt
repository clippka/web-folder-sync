package app.foldersync.reconciliation

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.blobs.BlobService
import app.foldersync.common.ApiException
import app.foldersync.common.IdempotencyService
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.Tx
import app.foldersync.manifests.EventsService
import app.foldersync.policy.IgnorePolicy
import app.foldersync.policy.PortablePath
import app.foldersync.policy.SpacePolicyService
import app.foldersync.revisions.EntryStore
import app.foldersync.revisions.RevisionWriter
import app.foldersync.revisions.versionOrZero
import app.foldersync.spaces.SpaceService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

data class ConflictSideDto(val type: String, val sha256: String?, val size: Long?, val blobId: String?)

data class ConflictDto(
    val id: String,
    val spaceId: String,
    val path: String,
    val kind: String,
    val state: String,
    val resolution: String?,
    val deviceLabel: String?,
    val serverEntryVersion: Long,
    val local: ConflictSideDto,
    val server: ConflictSideDto,
    val createdAt: Long,
    val resolvedAt: Long?,
    /** When the server variant last changed; null while the server variant is absent. */
    val serverUpdatedAt: Long? = null,
    /** The decisions this conflict accepts, in display order. Skip is always possible. */
    val options: List<String> = emptyList(),
    /** The binding (folder connection) whose scan produced the local variant. */
    val bindingId: String? = null,
)

enum class Resolution { KEEP_LOCAL, KEEP_SERVER, KEEP_BOTH, SKIP }

data class ResolveRequest(val resolution: Resolution, val expectedServerVersion: Long)

data class ResolveResult(
    val conflict: ConflictDto,
    val entryVersion: Long?,
    val revision: Long?,
    /** Keep both: where the local variant now lives on the server. */
    val siblingPath: String? = null,
    val siblingVersion: Long? = null,
)

/** A bounded, text-only look at one variant. Binary content is never decoded. */
data class PreviewDto(val kind: String, val text: String?, val truncated: Boolean, val size: Long?, val sha256: String?)

/**
 * Conflict persistence and the explicit decision contract. Both current variants stay protected (the server variant by
 * its entry or its own reference, the local variant by the staged candidate) until a decision succeeds; only then does
 * the unchosen candidate become collectable. Decisions are made against the version the user saw: a changed file
 * yields `stale_conflict` and requires a refreshed decision. Keep both stores the local variant under a deterministic
 * sibling name next to the unchanged server variant. Decisions that delete stay refused while the deletion gate is closed.
 */
@Service
class ConflictService(
    private val jdbc: JdbcClient,
    private val tx: Tx,
    private val clock: Clock,
    private val props: FolderSyncProperties,
    private val spaces: SpaceService,
    private val entries: EntryStore,
    private val writer: RevisionWriter,
    private val idempotency: IdempotencyService,
    private val audit: AuditService,
    private val events: EventsService,
    private val blobs: BlobService,
    private val policies: SpacePolicyService,
) {
    private val spaceLocks = ConcurrentHashMap<String, Any>()

    fun list(ownerId: String, spaceId: String, unresolvedOnly: Boolean): List<ConflictDto> {
        spaces.require(spaceId, ownerId)
        val where = if (unresolvedOnly) " AND state = 'UNRESOLVED'" else ""
        return jdbc.sql("$SELECT WHERE space_id = ? AND owner_id = ?$where ORDER BY created_at, path").args(spaceId, ownerId).query(::map).list()
    }

    fun get(ownerId: String, conflictId: String): ConflictDto =
        jdbc.sql("$SELECT WHERE id = ? AND owner_id = ?").args(conflictId, ownerId).query(::map).optional()
            .orElseThrow { ApiException.notFound("conflict") }

    fun resolve(ownerId: String, conflictId: String, req: ResolveRequest, idempotencyKey: String?): ResolveResult {
        val c = get(ownerId, conflictId)
        synchronized(spaceLocks.computeIfAbsent(c.spaceId) { Any() }) {
            return tx.run {
                idempotency.run(ownerId, "conflict.resolve:$conflictId", idempotencyKey, req, ResolveResult::class.java) { doResolve(ownerId, conflictId, req) }
            }
        }
    }

    private fun doResolve(ownerId: String, conflictId: String, req: ResolveRequest): ResolveResult {
        val c = get(ownerId, conflictId)
        if (c.state != "UNRESOLVED") throw ApiException.conflict("already_resolved", "This conflict was already resolved")
        val current = entries.find(c.spaceId, c.path)
        if (req.expectedServerVersion != c.serverEntryVersion || current.versionOrZero() != c.serverEntryVersion) {
            throw ApiException.conflict("stale_conflict", "The file changed since you reviewed it; refresh and decide again", true,
                path = c.path, details = mapOf("currentVersion" to current.versionOrZero(), "refresh" to true))
        }
        if (req.resolution != Resolution.SKIP && req.resolution.name !in c.options) {
            throw ApiException.invalid("resolution_not_available", "This decision is not available for this conflict", c.path, mapOf("options" to c.options))
        }
        when (req.resolution) {
            Resolution.SKIP -> return ResolveResult(c, null, null)
            Resolution.KEEP_BOTH -> return keepBoth(ownerId, c)
            Resolution.KEEP_SERVER -> {
                // The server variant is already current; only the browser changes. If that means removing its file, it is a deletion.
                if (c.server.type == "ABSENT" && c.local.type != "ABSENT" && !props.deletionEnabled) {
                    throw ApiException.conflict("deletion_disabled", "Deletion is disabled until the safety gate has passed", path = c.path)
                }
                markResolved(c, req.resolution)
                audit.record(ownerId, "conflict.resolved", c.spaceId, mapOf("path" to c.path, "resolution" to "KEEP_SERVER"))
                audit.record(ownerId, "conflict.candidate_discarded", c.spaceId, mapOf("path" to c.path, "side" to "local"))
                return ResolveResult(get(ownerId, conflictId), current.versionOrZero(), null)
            }
            Resolution.KEEP_LOCAL -> {
                val local = c.local
                if (local.type == "ABSENT" && !props.deletionEnabled) {
                    throw ApiException.conflict("deletion_disabled", "Deletion is disabled until the safety gate has passed", path = c.path)
                }
                if (local.type == "FILE" && (local.blobId == null || local.sha256 == null)) {
                    throw ApiException.conflict("candidate_missing", "The staged local variant is no longer available", path = c.path)
                }
                val batch = writer.open(c.spaceId, ownerId, null, "conflict:$conflictId")
                val state = when (local.type) {
                    "FILE" -> EntryState.File(local.sha256!!, local.size!!)
                    "DIRECTORY" -> EntryState.Directory
                    else -> EntryState.Absent
                }
                val version = batch.put(c.path, state, local.blobId)
                writer.close(batch)
                events.revisionChanged(c.spaceId, batch.number)
                markResolved(c, req.resolution)
                audit.record(ownerId, "conflict.resolved", c.spaceId, mapOf("path" to c.path, "resolution" to "KEEP_LOCAL"))
                audit.record(ownerId, "conflict.candidate_discarded", c.spaceId, mapOf("path" to c.path, "side" to "server"))
                if (state == EntryState.Absent) audit.record(ownerId, "deletion.server_applied", c.spaceId, mapOf("path" to c.path))
                return ResolveResult(get(ownerId, conflictId), version, batch.number)
            }
        }
    }

    /**
     * The server variant stays at the original path; the staged local variant becomes a new entry beside it, so both
     * are current, durable files and nothing is discarded. If the sibling cannot be created (name, ignore rule, file
     * limit) the transaction rolls back and the conflict stays unresolved.
     */
    private fun keepBoth(ownerId: String, c: ConflictDto): ResolveResult {
        val local = c.local
        if (local.blobId == null || local.sha256 == null || local.size == null) {
            throw ApiException.conflict("candidate_missing", "The staged local variant is no longer available", path = c.path)
        }
        val space = spaces.require(c.spaceId, ownerId)
        val taken = entries.all(c.spaceId).filter { !it.deleted }.map { it.path }
        val sibling = ConflictNames.sibling(c.path, c.deviceLabel, c.createdAt, taken)
            ?: throw ApiException.invalid("keep_both_unavailable", "No valid sibling name could be created for this file", c.path)
        if (PortablePath.validate(sibling) != null || policies.policyFor(c.spaceId, space.policyRevision).evaluate(sibling, false).ignored) {
            throw ApiException.invalid("keep_both_unavailable", "The sibling name '$sibling' is excluded by this space's ignore rules", c.path)
        }
        if (entries.activeFileCount(c.spaceId) + 1 > props.maxIncludedFiles) {
            throw ApiException.invalid("file_limit_exceeded", "A space may contain at most ${props.maxIncludedFiles} included files", c.path)
        }
        val batch = writer.open(c.spaceId, ownerId, null, "conflict:${c.id}")
        val version = batch.put(sibling, EntryState.File(local.sha256, local.size), local.blobId)
        writer.close(batch)
        events.revisionChanged(c.spaceId, batch.number)
        markResolved(c, Resolution.KEEP_BOTH)
        audit.record(ownerId, "conflict.resolved", c.spaceId, mapOf("path" to c.path, "resolution" to "KEEP_BOTH", "sibling" to sibling))
        return ResolveResult(get(ownerId, c.id), entries.find(c.spaceId, c.path).versionOrZero(), batch.number, sibling, version)
    }

    /** A bounded text preview of one variant. Anything that is not valid UTF-8 text is reported as binary and never decoded for display. */
    fun preview(ownerId: String, conflictId: String, side: String): PreviewDto {
        val c = get(ownerId, conflictId)
        val v = when (side) {
            "local" -> c.local
            "server" -> c.server
            else -> throw ApiException.bad("malformed_request", "side must be local or server")
        }
        val blobId = v.blobId
        if (v.type != "FILE" || blobId == null || !blobs.isReadable(blobId)) return PreviewDto("unavailable", null, false, v.size, v.sha256)
        val file = blobs.store().path(blobId)
        if (!Files.isRegularFile(file)) return PreviewDto("unavailable", null, false, v.size, v.sha256)
        val total = Files.size(file)
        if (total == 0L) return PreviewDto("empty", "", false, 0, v.sha256)
        val buf = ByteArray(minOf(total, props.previewBytes.toLong()).toInt())
        Files.newInputStream(file).use { it.readNBytes(buf, 0, buf.size) }
        val truncated = total > buf.size
        if (buf.any { it == 0.toByte() }) return PreviewDto("binary", null, truncated, total, v.sha256)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(buf.size + 1)
        // When the preview cut the file, a multi-byte character split at the boundary is not an error: decode without end-of-input.
        val result = decoder.decode(ByteBuffer.wrap(buf), out, !truncated)
        if (result.isError) return PreviewDto("binary", null, truncated, total, v.sha256)
        out.flip()
        val text = out.toString()
        if (text.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) return PreviewDto("binary", null, truncated, total, v.sha256)
        return PreviewDto("text", text, truncated, total, v.sha256)
    }

    private fun markResolved(c: ConflictDto, r: Resolution) {
        jdbc.sql("UPDATE conflicts SET state = 'RESOLVED', resolution = ?, resolved_at = ? WHERE id = ? AND state = 'UNRESOLVED'")
            .args(r.name, clock.millis(), c.id).update()
    }

    private fun map(rs: java.sql.ResultSet, n: Int): ConflictDto {
        val local = ConflictSideDto(rs.getString(9), rs.getString(10), rs.getLong(11).takeUnless { rs.wasNull() }, rs.getString(12))
        val server = ConflictSideDto(rs.getString(13), rs.getString(14), rs.getLong(15).takeUnless { rs.wasNull() }, rs.getString(16))
        val path = rs.getString(3)
        return ConflictDto(
            rs.getString(1), rs.getString(2), path, rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
            local, server, rs.getLong(17), rs.getLong(18).takeUnless { rs.wasNull() }, rs.getLong(19).takeUnless { rs.wasNull() },
            optionsFor(path, local, server), rs.getString(20),
        )
    }

    private fun optionsFor(path: String, local: ConflictSideDto, server: ConflictSideDto): List<String> = buildList {
        add(Resolution.KEEP_LOCAL.name)
        add(Resolution.KEEP_SERVER.name)
        // Two files can coexist under different names; an ignore control file must have a single winner.
        if (local.type == "FILE" && server.type == "FILE" && !IgnorePolicy.isControlFile(path)) add(Resolution.KEEP_BOTH.name)
    }

    private companion object {
        const val SELECT = "SELECT id, space_id, path, kind, state, resolution, device_label, server_entry_version, local_type, local_sha, local_size, local_blob_id, " +
            "server_type, server_sha, server_size, server_blob_id, created_at, resolved_at, " +
            "(SELECT e.updated_at FROM entries e WHERE e.space_id = conflicts.space_id AND e.path = conflicts.path AND e.deleted = 0), binding_id FROM conflicts"
    }
}
