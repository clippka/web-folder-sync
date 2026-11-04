package app.foldersync.reconciliation

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.common.Ids
import app.foldersync.common.isSha256
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.Tx
import app.foldersync.policy.IgnorePolicy
import app.foldersync.policy.PortablePath
import app.foldersync.policy.SpacePolicyService
import app.foldersync.revisions.EntryRecord
import app.foldersync.revisions.EntryStore
import app.foldersync.revisions.EpochService
import app.foldersync.revisions.stateOrAbsent
import app.foldersync.revisions.versionOrZero
import app.foldersync.spaces.BindingService
import app.foldersync.spaces.SpaceService
import app.foldersync.transfers.LeaseService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

// ---- wire types -----------------------------------------------------------------------------------------------

/** `type` is ABSENT, FILE or DIRECTORY; files carry a lowercase hex SHA-256 and a byte size. */
data class StateDto(val type: String = "ABSENT", val sha256: String? = null, val size: Long? = null) {
    fun toState(path: String): EntryState = when (type) {
        "ABSENT" -> EntryState.Absent
        "DIRECTORY" -> EntryState.Directory
        "FILE" -> {
            if (!isSha256(sha256) || size == null || size < 0) throw ApiException.invalid("invalid_state", "File state needs a 64-character lowercase sha256 and a size", path)
            EntryState.File(sha256!!, size)
        }
        else -> throw ApiException.invalid("invalid_state", "Unknown entry type '$type'", path)
    }

    companion object {
        fun of(s: EntryState): StateDto = when (s) {
            EntryState.Absent -> StateDto("ABSENT")
            EntryState.Directory -> StateDto("DIRECTORY")
            is EntryState.File -> StateDto("FILE", s.sha256, s.size)
        }
    }
}

data class PlanEntryDto(
    val path: String,
    /** Last acknowledged shared state; omit (ABSENT) when there is none. A missing baseline never implies deletion. */
    val baseline: StateDto? = null,
    /** Current local state; omit for "not present". */
    val local: StateDto? = null,
    /** True when the local entry could not be read: nothing may be inferred for it. */
    val unreadable: Boolean = false,
    /** True when the path was suspended (ignored/oversized) and is included again. */
    val reincluded: Boolean = false,
)

data class CreatePlanRequest(
    val bindingId: String,
    val epoch: String,
    /** Manifest revision the client based its scan on. */
    val baseRevision: Long = 0,
    val policyRevision: Long,
    /** False when enumeration failed or was cut short: no deletion may result. */
    val scanComplete: Boolean,
    val entries: List<PlanEntryDto> = emptyList(),
    val final: Boolean = false,
)

data class AppendEntriesRequest(val entries: List<PlanEntryDto> = emptyList(), val final: Boolean = false)

data class RejectedPath(val path: String, val reason: String)

data class PlanDto(
    val id: String,
    val spaceId: String,
    val state: String,
    val phase: String?,
    val serverRevision: Long?,
    val policyRevision: Long,
    val epoch: String,
    val paused: Boolean,
    val rescanRequired: Boolean,
    val scanComplete: Boolean,
    val inputCount: Int,
    val counts: Map<String, Int>,
    val decisionCount: Int,
    val rejectedCount: Int,
    val rejected: List<RejectedPath>,
    val leaseId: String?,
    val expiresAt: Long,
)

data class OperationDto(
    val seq: Int,
    val kind: String,
    val path: String,
    val needsDecision: Boolean,
    val state: StateDto?,
    val expected: StateDto?,
    val expectedVersion: Long?,
    val reason: String?,
    val cause: String?,
    val blobId: String?,
)

data class OperationPage(val operations: List<OperationDto>, val nextAfter: Int?)

// ---- storage rows ---------------------------------------------------------------------------------------------

data class PlanRow(
    val id: String, val ownerId: String, val spaceId: String, val bindingId: String, val runId: String, val state: String,
    val phase: String?, val serverRevision: Long?, val policyRevision: Long, val epoch: String, val scanComplete: Boolean,
    val paused: Boolean, val rescanRequired: Boolean, val inputCount: Int, val expiresAt: Long,
)

data class StoredOp(
    val seq: Int, val kind: String, val path: String, val needsDecision: Boolean,
    val state: StateDto?, val expected: StateDto?, val expectedVersion: Long?, val reason: String?, val cause: String?,
    val blobId: String?, val leaseId: String?,
)

@Service
class PlanService(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val tx: Tx,
    private val props: FolderSyncProperties,
    private val clock: Clock,
    private val epochs: EpochService,
    private val spaces: SpaceService,
    private val bindings: BindingService,
    private val entries: EntryStore,
    private val policies: SpacePolicyService,
    private val leases: LeaseService,
    private val audit: AuditService,
) {
    fun create(ownerId: String, spaceId: String, req: CreatePlanRequest): PlanDto {
        val space = spaces.require(spaceId, ownerId)
        val binding = bindings.requireActive(req.bindingId, ownerId, spaceId)
        epochs.require(req.epoch)
        if (req.policyRevision != space.policyRevision) {
            throw ApiException.conflict("stale_policy", "The ignore policy changed; refetch the control files and rescan", true,
                details = mapOf("replan" to true, "currentPolicyRevision" to space.policyRevision))
        }
        val planId = Ids.uuid()
        val runId = Ids.uuid()
        val now = clock.millis()
        tx.run {
            jdbc.sql("INSERT INTO sync_runs (id, owner_id, space_id, binding_id, base_revision, policy_revision, state, created_at) VALUES (?,?,?,?,?,?, 'PLANNING', ?)")
                .args(runId, ownerId, spaceId, binding.id, req.baseRevision, req.policyRevision, now).update()
            jdbc.sql("INSERT INTO plans (id, owner_id, space_id, binding_id, run_id, state, policy_revision, epoch, scan_complete, expires_at, created_at) VALUES (?,?,?,?,?, 'INGESTING', ?,?,?,?,?)")
                .args(planId, ownerId, spaceId, binding.id, runId, req.policyRevision, epochs.current(), if (req.scanComplete) 1 else 0, now + props.planTtl.toMillis(), now).update()
        }
        audit.record(ownerId, "run.started", spaceId, mapOf("planId" to planId))
        if (req.entries.isNotEmpty() || req.final) return append(ownerId, planId, AppendEntriesRequest(req.entries, req.final))
        return get(ownerId, planId)
    }

    fun append(ownerId: String, planId: String, req: AppendEntriesRequest): PlanDto {
        var plan = require(ownerId, planId)
        if (plan.state != "INGESTING") {
            if (plan.state == "READY" && req.final && req.entries.isEmpty()) return get(ownerId, planId)
            throw ApiException.conflict("plan_not_open", "This plan no longer accepts entries")
        }
        if (plan.expiresAt <= clock.millis()) throw ApiException.gone("plan_expired", "This plan expired; start a new scan", mapOf("replan" to true))
        epochs.require(plan.epoch)
        if (req.entries.size > props.maxManifestBatch) {
            throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "batch_too_large", "At most ${props.maxManifestBatch} entries per request")
        }
        tx.run {
            for (e in req.entries) ingest(plan.id, e)
            val count = jdbc.sql("SELECT COUNT(*) FROM plan_inputs WHERE plan_id = ?").param(plan.id).query(Int::class.java).single()
            if (count > props.maxPlanEntries) throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "plan_too_large", "A plan may contain at most ${props.maxPlanEntries} entries")
            jdbc.sql("UPDATE plans SET input_count = ? WHERE id = ?").args(count, plan.id).update()
        }
        plan = require(ownerId, planId)
        return if (req.final) finalizePlan(plan) else get(ownerId, planId)
    }

    private fun ingest(planId: String, e: PlanEntryDto) {
        val problem = PortablePath.validate(e.path)
        if (problem != null) {
            jdbc.sql("INSERT OR REPLACE INTO plan_rejects (plan_id, path, reason) VALUES (?,?,?)").args(planId, e.path.take(1024), problem.name.lowercase()).update()
            return
        }
        val baseline = (e.baseline ?: StateDto()).toState(e.path)
        val local = if (e.unreadable) null else (e.local ?: StateDto()).toState(e.path)
        jdbc.sql("INSERT OR REPLACE INTO plan_inputs (plan_id, path, baseline_type, baseline_sha, baseline_size, unreadable, local_type, local_sha, local_size, reincluded) VALUES (?,?,?,?,?,?,?,?,?,?)")
            .args(
                planId, e.path, typeOf(baseline), (baseline as? EntryState.File)?.sha256, (baseline as? EntryState.File)?.size,
                if (e.unreadable) 1 else 0, local?.let(::typeOf), (local as? EntryState.File)?.sha256, (local as? EntryState.File)?.size,
                if (e.reincluded) 1 else 0,
            ).update()
    }

    // ---- finalize: server state + client inputs -> immutable operations ------------------------------------------

    private class Snapshot(val revision: Long, val policyRevision: Long, val entries: List<EntryRecord>, val inputs: List<PathInput>)
    private class Computed(val plan: Plan, val rejected: List<RejectedPath>, val blobs: Map<String, String?>, val versions: Map<String, Long>)

    private fun finalizePlan(plan: PlanRow): PlanDto {
        repeat(3) {
            val snap = tx.run {
                val s = jdbc.sql("SELECT revision, policy_revision FROM spaces WHERE id = ?").param(plan.spaceId).query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()
                Snapshot(s.first, s.second, entries.all(plan.spaceId), loadInputs(plan.id))
            }
            if (snap.policyRevision != plan.policyRevision) {
                throw ApiException.conflict("stale_policy", "The ignore policy changed; refetch the control files and rescan", true,
                    details = mapOf("replan" to true, "currentPolicyRevision" to snap.policyRevision))
            }
            val computed = compute(plan, snap)
            val persisted = tx.run {
                val cur = jdbc.sql("SELECT revision, policy_revision FROM spaces WHERE id = ?").param(plan.spaceId).query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()
                if (cur.first != snap.revision || cur.second != snap.policyRevision) false else { persist(plan, computed); true }
            }
            if (persisted) {
                audit.record(plan.ownerId, "run.planned", plan.spaceId, mapOf("planId" to plan.id, "ops" to computed.plan.operations.size.toString()))
                return get(plan.ownerId, plan.id)
            }
        }
        throw ApiException.conflict("server_busy", "The space changed while planning; try again", retryable = true)
    }

    private fun compute(plan: PlanRow, snap: Snapshot): Computed {
        val policy = policies.policyFor(plan.spaceId, snap.policyRevision)
        val serverByPath = snap.entries.associateBy { it.path }
        val inputByPath = snap.inputs.associateBy { it.path }
        val livePaths = HashSet<String>(inputByPath.keys)
        snap.entries.filter { !it.deleted }.forEach { livePaths.add(it.path) }

        // Colliding names are reported and excluded from planning; nothing is renamed or merged.
        val rejected = ArrayList<RejectedPath>()
        val rejectedPaths = HashSet<String>()
        for (group in PortablePath.findCollisions(livePaths)) for (p in group) { rejected.add(RejectedPath(p, "case_or_unicode_collision")); rejectedPaths.add(p) }

        val all = ArrayList<PathInput>()
        for (path in livePaths) {
            if (path in rejectedPaths) continue
            val server = serverByPath[path].stateOrAbsent()
            val given = inputByPath[path]
            // A path the client did not mention but the server has: no baseline, nothing observed locally. That plans a
            // download (or a no-op when the scan is incomplete) and can never plan a deletion.
            val input = given ?: PathInput(path, EntryState.Absent, LocalObservation.Observed(EntryState.Absent), server)
            val localState = (input.local as? LocalObservation.Observed)?.state ?: EntryState.Absent
            val isDir = localState is EntryState.Directory || server is EntryState.Directory
            val suspension = when {
                policy.evaluate(path, isDir).ignored -> SuspensionReason.IGNORED
                localState is EntryState.File && localState.size > props.maxFileSizeBytes -> SuspensionReason.OVERSIZED
                server is EntryState.File && server.size > props.maxFileSizeBytes -> SuspensionReason.OVERSIZED
                else -> null
            }
            all.add(input.copy(server = server, suspension = suspension))
        }
        val included = all.count { it.suspension == null && ((it.local as? LocalObservation.Observed)?.state is EntryState.File || it.server is EntryState.File) }
        if (included > props.maxIncludedFiles) {
            throw ApiException.invalid("file_limit_exceeded", "A space may contain at most ${props.maxIncludedFiles} included files (found $included)",
                details = mapOf("limit" to props.maxIncludedFiles, "found" to included))
        }
        val scan = if (plan.scanComplete) ScanCompleteness.COMPLETE else ScanCompleteness.INCOMPLETE
        val result = Planner.plan(all, scan, snap.revision, snap.policyRevision)
        val versions = result.operations.associate { it.path to serverByPath[it.path].versionOrZero() }
        val blobs = result.operations.filterIsInstance<Operation.Download>().associate { it.path to serverByPath[it.path]?.blobId }
        return Computed(result, rejected, blobs, versions)
    }

    private fun persist(plan: PlanRow, c: Computed) {
        val now = clock.millis()
        val won = jdbc.sql("UPDATE plans SET state = 'READY', phase = ?, server_revision = ?, policy_revision = ?, paused = ?, rescan_required = ? WHERE id = ? AND state = 'INGESTING'")
            .args(c.plan.phase.name, c.plan.serverRevision, c.plan.policyRevision, if (c.plan.paused) 1 else 0, if (c.plan.rescanRequired) 1 else 0, plan.id).update()
        if (won == 0) return
        for (r in c.rejected) jdbc.sql("INSERT OR REPLACE INTO plan_rejects (plan_id, path, reason) VALUES (?,?,?)").args(plan.id, r.path, r.reason).update()
        val ops = c.plan.operations
        template.batchUpdate(
            "INSERT INTO plan_operations (plan_id, seq, kind, path, needs_decision, state_type, state_sha, state_size, expected_type, expected_sha, expected_size, expected_version, reason, cause, blob_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ops.mapIndexed { i, op -> row(plan.id, i, op, c.versions[op.path], c.blobs[op.path]) },
        )
        jdbc.sql("DELETE FROM plan_inputs WHERE plan_id = ?").param(plan.id).update()
        if (c.blobs.values.any { it != null }) leases.createForOperation(plan.ownerId, plan.spaceId, plan.id)
        jdbc.sql("UPDATE sync_runs SET state = 'PLANNED', planned_ops = ? WHERE id = ?").args(ops.size, plan.runId).update()
    }

    private fun row(planId: String, seq: Int, op: Operation, version: Long?, blob: String?): Array<Any?> {
        var state: EntryState? = null
        var expected: EntryState? = null
        var reason: String? = null
        var cause: String? = null
        val kind: String
        when (op) {
            is Operation.Upload -> { kind = "UPLOAD"; state = op.state; expected = op.expectedServer }
            is Operation.Download -> { kind = "DOWNLOAD"; state = op.state; expected = op.expectedLocal }
            is Operation.Adopt -> { kind = "ADOPT"; state = op.state }
            is Operation.AcknowledgeDeletion -> kind = "ACKNOWLEDGE_DELETION"
            is Operation.ProposeServerDeletion -> { kind = "PROPOSE_SERVER_DELETION"; expected = op.expectedServer }
            is Operation.ProposeLocalDeletion -> { kind = "PROPOSE_LOCAL_DELETION"; expected = op.expectedLocal }
            is Operation.Conflict -> { kind = "CONFLICT"; state = op.local; expected = op.server; reason = op.kind.name }
            is Operation.Blocked -> { kind = "BLOCKED"; reason = op.reason.name; cause = op.cause }
            is Operation.Suspended -> { kind = "SUSPENDED"; reason = op.reason.name }
        }
        fun f(s: EntryState?) = s as? EntryState.File
        return arrayOf(
            planId, seq, kind, op.path, if (op.needsDecision) 1 else 0,
            state?.let(::typeOf), f(state)?.sha256, f(state)?.size,
            expected?.let(::typeOf), f(expected)?.sha256, f(expected)?.size,
            version, reason, cause, blob,
        )
    }

    private fun loadInputs(planId: String): List<PathInput> =
        jdbc.sql("SELECT path, baseline_type, baseline_sha, baseline_size, unreadable, local_type, local_sha, local_size, reincluded FROM plan_inputs WHERE plan_id = ?")
            .param(planId).query { rs, _ ->
                fun st(type: String?, sha: String?, size: Long?): EntryState = when (type) {
                    "FILE" -> EntryState.File(sha!!, size!!)
                    "DIRECTORY" -> EntryState.Directory
                    else -> EntryState.Absent
                }
                val unreadable = rs.getInt(5) == 1
                PathInput(
                    rs.getString(1), st(rs.getString(2), rs.getString(3), rs.getLong(4)),
                    if (unreadable) LocalObservation.Unreadable else LocalObservation.Observed(st(rs.getString(6), rs.getString(7), rs.getLong(8))),
                    EntryState.Absent, reincluded = rs.getInt(9) == 1,
                )
            }.list()

    // ---- reads ---------------------------------------------------------------------------------------------------

    fun get(ownerId: String, planId: String): PlanDto {
        val p = require(ownerId, planId)
        val counts = jdbc.sql("SELECT kind, COUNT(*) FROM plan_operations WHERE plan_id = ? GROUP BY kind").param(planId)
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().toMap()
        val decisions = jdbc.sql("SELECT COUNT(*) FROM plan_operations WHERE plan_id = ? AND needs_decision = 1").param(planId).query(Int::class.java).single()
        val rejectedCount = jdbc.sql("SELECT COUNT(*) FROM plan_rejects WHERE plan_id = ?").param(planId).query(Int::class.java).single()
        val rejected = jdbc.sql("SELECT path, reason FROM plan_rejects WHERE plan_id = ? ORDER BY path LIMIT 200").param(planId)
            .query { rs, _ -> RejectedPath(rs.getString(1), rs.getString(2)) }.list()
        val lease = jdbc.sql("SELECT id FROM transfer_leases WHERE operation_id = ? AND released = 0 LIMIT 1").param(planId).query(String::class.java).optional().orElse(null)
        return PlanDto(
            p.id, p.spaceId, p.state, p.phase, p.serverRevision, p.policyRevision, p.epoch, p.paused, p.rescanRequired, p.scanComplete,
            p.inputCount, counts, decisions, rejectedCount, rejected, lease, p.expiresAt,
        )
    }

    fun operations(ownerId: String, planId: String, after: Int, limit: Int): OperationPage {
        require(ownerId, planId)
        val page = limit.coerceIn(1, props.maxManifestBatch)
        val rows = storedOps(planId, after, page + 1)
        val more = rows.size > page
        val ops = rows.take(page).map { OperationDto(it.seq, it.kind, it.path, it.needsDecision, it.state, it.expected, it.expectedVersion, it.reason, it.cause, it.blobId) }
        return OperationPage(ops, if (more) ops.last().seq else null)
    }

    fun storedOps(planId: String, after: Int, limit: Int): List<StoredOp> =
        jdbc.sql("SELECT seq, kind, path, needs_decision, state_type, state_sha, state_size, expected_type, expected_sha, expected_size, expected_version, reason, cause, blob_id, lease_id FROM plan_operations WHERE plan_id = ? AND seq > ? ORDER BY seq LIMIT ?")
            .args(planId, after, limit).query { rs, _ ->
                fun dto(type: String?, sha: String?, size: Long): StateDto? = type?.let { StateDto(it, sha, if (it == "FILE") size else null) }
                StoredOp(
                    rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1,
                    dto(rs.getString(5), rs.getString(6), rs.getLong(7)), dto(rs.getString(8), rs.getString(9), rs.getLong(10)),
                    rs.getLong(11).takeUnless { rs.wasNull() }, rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                )
            }.list()

    fun allOps(planId: String): List<StoredOp> = storedOps(planId, -1, Int.MAX_VALUE)

    fun require(ownerId: String, planId: String): PlanRow =
        jdbc.sql("SELECT id, owner_id, space_id, binding_id, run_id, state, phase, server_revision, policy_revision, epoch, scan_complete, paused, rescan_required, input_count, expires_at FROM plans WHERE id = ? AND owner_id = ?")
            .args(planId, ownerId).query { rs, _ ->
                PlanRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getLong(8).takeUnless { rs.wasNull() }, rs.getLong(9), rs.getString(10), rs.getInt(11) == 1, rs.getInt(12) == 1, rs.getInt(13) == 1,
                    rs.getInt(14), rs.getLong(15),
                )
            }.optional().orElseThrow { ApiException.notFound("plan") }

    private fun typeOf(s: EntryState) = when (s) { EntryState.Absent -> "ABSENT"; EntryState.Directory -> "DIRECTORY"; is EntryState.File -> "FILE" }

    /** Drops expired plans' working data. Their rows stay as run history. */
    fun expireStale(): Int {
        val ids = jdbc.sql("SELECT id FROM plans WHERE state IN ('INGESTING','READY') AND expires_at <= ? LIMIT 500").param(clock.millis()).query { rs, _ -> rs.getString(1) }.list()
        for (id in ids) tx.run {
            jdbc.sql("UPDATE plans SET state = 'EXPIRED' WHERE id = ? AND state IN ('INGESTING','READY')").param(id).update()
            jdbc.sql("DELETE FROM plan_inputs WHERE plan_id = ?").param(id).update()
            jdbc.sql("DELETE FROM plan_operations WHERE plan_id = ?").param(id).update()
            jdbc.sql("UPDATE sync_runs SET state = 'EXPIRED', finished_at = ? WHERE id = (SELECT run_id FROM plans WHERE id = ?) AND state IN ('PLANNING','PLANNED')").args(clock.millis(), id).update()
        }
        return ids.size
    }
}
