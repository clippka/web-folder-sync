package app.foldersync.revisions

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.common.IdempotencyService
import app.foldersync.common.Ids
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.Tx
import app.foldersync.manifests.EventsService
import app.foldersync.policy.IgnorePolicy
import app.foldersync.policy.PortablePath
import app.foldersync.policy.SpacePolicyService
import app.foldersync.reconciliation.EntryState
import app.foldersync.reconciliation.PlanService
import app.foldersync.reconciliation.StoredOp
import app.foldersync.spaces.BindingService
import app.foldersync.transfers.UploadService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

data class CommitRequest(
    val epoch: String,
    /** path -> completed upload id, for UPLOAD operations on files. */
    val uploads: Map<String, String> = emptyMap(),
    /** path -> completed upload id holding the local variant of a CONFLICT operation. */
    val conflictCandidates: Map<String, String> = emptyMap(),
    /** Paths of displayed PROPOSE_SERVER_DELETION operations the user approved. */
    val approvedServerDeletions: List<String> = emptyList(),
    /** Paths of displayed PROPOSE_LOCAL_DELETION operations the user approved (executed by the browser, recorded here). */
    val approvedLocalDeletions: List<String> = emptyList(),
)

data class AppliedEntry(val path: String, val kind: String, val version: Long)
data class ConflictRef(val id: String, val path: String, val existing: Boolean)
data class SkippedOp(val path: String, val reason: String)

data class CommitResult(
    val planId: String,
    val revision: Long?,
    val spaceRevision: Long,
    val policyRevision: Long,
    val policyChanged: Boolean,
    val rescanRequired: Boolean,
    val applied: List<AppliedEntry>,
    val conflicts: List<ConflictRef>,
    val skipped: List<SkippedOp>,
    /** Decision operations the request did not approve; they stay pending. */
    val pendingDecisions: List<String>,
)

/**
 * Applies the server side of an immutable plan in one short transaction. Commits are serialized per space (and
 * SQLite serializes writers overall). Every applied operation is guarded by the entry version recorded at planning
 * time, so a plan made from outdated state fails with `stale_plan` instead of overwriting newer content; the same
 * request replays safely under its Idempotency-Key. No file bytes move here: uploads were staged and verified earlier.
 */
@Service
class CommitService(
    private val jdbc: JdbcClient,
    private val tx: Tx,
    private val clock: Clock,
    private val props: FolderSyncProperties,
    private val plans: PlanService,
    private val bindings: BindingService,
    private val epochs: EpochService,
    private val entries: EntryStore,
    private val writer: RevisionWriter,
    private val policies: SpacePolicyService,
    private val uploads: UploadService,
    private val idempotency: IdempotencyService,
    private val audit: AuditService,
    private val events: EventsService,
) {
    private val spaceLocks = ConcurrentHashMap<String, Any>()

    fun commit(ownerId: String, planId: String, req: CommitRequest, idempotencyKey: String?): CommitResult {
        val plan = plans.require(ownerId, planId)
        bindings.requireActive(plan.bindingId, ownerId, plan.spaceId)
        epochs.require(req.epoch)
        epochs.require(plan.epoch)
        synchronized(spaceLocks.computeIfAbsent(plan.spaceId) { Any() }) {
            return tx.run {
                idempotency.run(ownerId, "plan.commit:$planId", idempotencyKey, req, CommitResult::class.java) { apply(ownerId, planId, req) }
            }
        }
    }

    private fun apply(ownerId: String, planId: String, req: CommitRequest): CommitResult {
        val plan = plans.require(ownerId, planId)
        val now = clock.millis()
        when (plan.state) {
            "COMMITTED" -> throw ApiException.conflict("plan_already_committed", "This plan was already committed")
            "READY" -> {}
            "EXPIRED" -> throw ApiException.gone("plan_expired", "This plan expired; start a new scan", mapOf("replan" to true))
            else -> throw ApiException.conflict("plan_not_ready", "This plan is not ready to commit")
        }
        if (plan.expiresAt <= now) throw ApiException.gone("plan_expired", "This plan expired; start a new scan", mapOf("replan" to true))
        val (spaceRevision, policyRevision) = jdbc.sql("SELECT revision, policy_revision FROM spaces WHERE id = ?").param(plan.spaceId)
            .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()
        if (policyRevision != plan.policyRevision) {
            throw ApiException.conflict("stale_policy", "The ignore policy changed since this plan was made", true,
                details = mapOf("replan" to true, "currentPolicyRevision" to policyRevision))
        }

        val ops = plans.allOps(planId)
        val byPath = ops.associateBy { it.path }
        validateReferences(req, byPath)

        val policy = policies.policyFor(plan.spaceId, policyRevision)
        val approvedDeletes = req.approvedServerDeletions.toSet()

        // Pass 1: every operation we are about to apply must still match the version the plan was made against.
        val stale = ArrayList<String>()
        for (op in ops) {
            val applies = when (op.kind) {
                "UPLOAD" -> op.state?.type == "DIRECTORY" || op.path in req.uploads
                "PROPOSE_SERVER_DELETION" -> op.path in approvedDeletes
                "CONFLICT" -> true
                else -> false
            }
            if (applies && entries.find(plan.spaceId, op.path).versionOrZero() != (op.expectedVersion ?: 0L)) stale.add(op.path)
        }
        if (stale.isNotEmpty()) {
            throw ApiException.stale("The server changed since this plan was made; replan", mapOf("paths" to stale.take(200), "currentRevision" to spaceRevision), stale.first())
        }

        // Pass 2: apply in plan order (parents before children, children before parents for deletions).
        var batch: RevisionBatch? = null
        fun batch() = batch ?: writer.open(plan.spaceId, ownerId, plan.bindingId, planId).also { batch = it }
        val applied = ArrayList<AppliedEntry>()
        val conflicts = ArrayList<ConflictRef>()
        val skipped = ArrayList<SkippedOp>()
        val pending = ArrayList<String>()

        for (op in ops) {
            when (op.kind) {
                "UPLOAD" -> {
                    val state = op.state!!.toState(op.path)
                    checkAllowed(op.path, state, policy)
                    if (state is EntryState.File) {
                        val uploadId = req.uploads[op.path]
                        if (uploadId == null) { skipped.add(SkippedOp(op.path, "upload_missing")); continue }
                        val blobId = uploads.consume(ownerId, plan.spaceId, uploadId, state.size, state.sha256)
                        val v = batch().put(op.path, state, blobId)
                        applied.add(AppliedEntry(op.path, "FILE", v))
                    } else {
                        applied.add(AppliedEntry(op.path, "DIRECTORY", batch().put(op.path, state, null)))
                    }
                }
                "PROPOSE_SERVER_DELETION" -> {
                    if (op.path in approvedDeletes) {
                        val v = batch().put(op.path, EntryState.Absent, null)
                        applied.add(AppliedEntry(op.path, "DELETE", v))
                        audit.record(ownerId, "deletion.server_applied", plan.spaceId, mapOf("path" to op.path))
                    } else pending.add(op.path)
                }
                "PROPOSE_LOCAL_DELETION" -> {
                    if (op.path in req.approvedLocalDeletions) audit.record(ownerId, "deletion.local_approved", plan.spaceId, mapOf("path" to op.path))
                    else pending.add(op.path)
                }
                "CONFLICT" -> createConflict(ownerId, plan.spaceId, plan.id, plan.bindingId, op, req, conflicts, skipped)
            }
        }

        if (entries.activeFileCount(plan.spaceId) > props.maxIncludedFiles) {
            throw ApiException.invalid("file_limit_exceeded", "A space may contain at most ${props.maxIncludedFiles} included files")
        }
        val newPolicyRevision = batch?.let { writer.close(it) }
        batch?.let { events.revisionChanged(plan.spaceId, it.number) }
        val finalSpaceRevision = batch?.number ?: spaceRevision
        jdbc.sql("UPDATE plans SET state = 'COMMITTED', committed_at = ? WHERE id = ? AND state = 'READY'").args(now, planId).update()
        jdbc.sql("UPDATE sync_runs SET state = 'COMMITTED', applied_ops = ?, finished_at = ? WHERE id = ?").args(applied.size, now, plan.runId).update()
        audit.record(
            ownerId, "plan.committed", plan.spaceId,
            mapOf("planId" to planId, "applied" to applied.size.toString(), "conflicts" to conflicts.size.toString(), "revision" to (batch?.number?.toString() ?: "none")),
        )
        return CommitResult(
            planId, batch?.number, finalSpaceRevision, newPolicyRevision ?: policyRevision, newPolicyRevision != null,
            plan.rescanRequired || newPolicyRevision != null, applied, conflicts, skipped, pending,
        )
    }

    private fun validateReferences(req: CommitRequest, byPath: Map<String, StoredOp>) {
        fun need(paths: Collection<String>, kind: String, what: String) {
            for (p in paths) {
                val op = byPath[p]
                if (op == null || op.kind != kind) throw ApiException.invalid("unknown_operation", "$what '$p' does not match an operation of this plan", p)
            }
        }
        need(req.uploads.keys, "UPLOAD", "Upload")
        need(req.conflictCandidates.keys, "CONFLICT", "Conflict candidate")
        need(req.approvedServerDeletions, "PROPOSE_SERVER_DELETION", "Deletion approval")
        need(req.approvedLocalDeletions, "PROPOSE_LOCAL_DELETION", "Deletion approval")
        if ((req.approvedServerDeletions.isNotEmpty() || req.approvedLocalDeletions.isNotEmpty()) && !props.deletionEnabled) {
            throw ApiException.conflict("deletion_disabled", "Deletion is disabled until the safety gate has passed")
        }
    }

    /** The server never trusts the client's view of what is allowed: path syntax, ignore policy and size are re-checked. */
    private fun checkAllowed(path: String, state: EntryState, policy: IgnorePolicy) {
        PortablePath.validate(path)?.let { throw ApiException.invalid("invalid_path", "Path '$path' is not allowed (${it.name.lowercase()})", path) }
        if (policy.evaluate(path, state is EntryState.Directory).ignored) {
            throw ApiException.invalid("path_ignored", "Path '$path' is excluded by the space's ignore policy", path)
        }
        if (state is EntryState.File) {
            if (state.size > props.maxFileSizeBytes) throw ApiException.invalid("file_too_large", "File exceeds the ${props.maxFileSizeBytes} byte limit", path)
            if (IgnorePolicy.isControlFile(path) && state.size > props.maxControlFileBytes) {
                throw ApiException.invalid("control_file_too_large", "Ignore files may be at most ${props.maxControlFileBytes} bytes", path)
            }
        }
    }

    private fun createConflict(
        ownerId: String, spaceId: String, planId: String, bindingId: String, op: StoredOp, req: CommitRequest,
        out: MutableList<ConflictRef>, skipped: MutableList<SkippedOp>,
    ) {
        val open = jdbc.sql("SELECT id FROM conflicts WHERE space_id = ? AND path = ? AND state = 'UNRESOLVED'").args(spaceId, op.path)
            .query(String::class.java).optional().orElse(null)
        if (open != null) { out.add(ConflictRef(open, op.path, true)); return }

        val local = op.state?.toState(op.path) ?: EntryState.Absent
        val current = entries.find(spaceId, op.path)
        var localBlob: String? = null
        if (local is EntryState.File) {
            val uploadId = req.conflictCandidates[op.path]
            if (uploadId == null) { skipped.add(SkippedOp(op.path, "candidate_not_staged")); return }
            localBlob = uploads.consume(ownerId, spaceId, uploadId, local.size, local.sha256)
        }
        val server = current.stateOrAbsent()
        val label = jdbc.sql("SELECT d.label FROM bindings b JOIN devices d ON d.id = b.device_id WHERE b.id = ?").param(bindingId)
            .query(String::class.java).optional().orElse(null)
        val id = Ids.uuid()
        fun type(s: EntryState) = when (s) { EntryState.Absent -> "ABSENT"; EntryState.Directory -> "DIRECTORY"; is EntryState.File -> "FILE" }
        jdbc.sql(
            """INSERT INTO conflicts (id, owner_id, space_id, plan_id, path, kind, state, binding_id, device_label, server_entry_version,
                 local_type, local_sha, local_size, local_blob_id, server_type, server_sha, server_size, server_blob_id, created_at)
               VALUES (?,?,?,?,?,?, 'UNRESOLVED', ?,?,?, ?,?,?,?, ?,?,?,?, ?)""",
        ).args(
            id, ownerId, spaceId, planId, op.path, op.reason ?: "CONTENT", bindingId, label, current.versionOrZero(),
            type(local), (local as? EntryState.File)?.sha256, (local as? EntryState.File)?.size, localBlob,
            type(server), (server as? EntryState.File)?.sha256, (server as? EntryState.File)?.size, if (server is EntryState.File) current?.blobId else null,
            clock.millis(),
        ).update()
        audit.record(ownerId, "conflict.created", spaceId, mapOf("path" to op.path, "conflictId" to id))
        out.add(ConflictRef(id, op.path, false))
    }
}
