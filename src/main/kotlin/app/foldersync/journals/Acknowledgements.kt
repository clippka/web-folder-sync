package app.foldersync.journals

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.Tx
import app.foldersync.reconciliation.PlanService
import app.foldersync.revisions.EntryStore
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

data class AckEntry(val path: String, val outcome: String, val sha256: String? = null)
data class AckRequest(val entries: List<AckEntry> = emptyList())
data class AckResult(val recorded: Int, val applied: Int, val failed: Int, val pending: Int, val totalOperations: Int)

/**
 * Records which operations of a plan the client reports as completed. This is a *record*, never a substitute for
 * local verification: the browser advances its own per-path baselines only after verifying actual bytes. The server
 * still refuses acknowledgements that contradict what it knows (an upload ack must match the committed entry, a
 * download ack must name the planned content), which catches stale or confused clients early.
 */
@Service
class JournalService(
    private val jdbc: JdbcClient,
    private val tx: Tx,
    private val clock: Clock,
    private val props: FolderSyncProperties,
    private val plans: PlanService,
    private val entries: EntryStore,
    private val audit: AuditService,
) {
    private val ackable = setOf("UPLOAD", "DOWNLOAD", "ADOPT", "ACKNOWLEDGE_DELETION", "PROPOSE_SERVER_DELETION", "PROPOSE_LOCAL_DELETION")

    fun acknowledge(ownerId: String, planId: String, req: AckRequest): AckResult {
        val plan = plans.require(ownerId, planId)
        if (plan.state == "EXPIRED") throw ApiException.gone("plan_expired", "This plan expired", mapOf("replan" to true))
        if (plan.state == "INGESTING") throw ApiException.conflict("plan_not_ready", "This plan is not ready")
        if (req.entries.size > props.maxManifestBatch) throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "batch_too_large", "At most ${props.maxManifestBatch} acknowledgements per request")
        val now = clock.millis()
        tx.run {
            for (a in req.entries) {
                if (a.outcome !in setOf("APPLIED", "FAILED", "PENDING")) throw ApiException.invalid("invalid_outcome", "Outcome must be APPLIED, FAILED or PENDING", a.path)
                val op = jdbc.sql("SELECT kind, state_type, state_sha FROM plan_operations WHERE plan_id = ? AND path = ?").args(planId, a.path)
                    .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.optional().orElse(null)
                    ?: throw ApiException.invalid("unknown_operation", "'${a.path}' is not an operation of this plan", a.path)
                if (a.outcome == "APPLIED") {
                    if (op.first !in ackable) throw ApiException.invalid("not_acknowledgeable", "${op.first} operations cannot be acknowledged as applied", a.path)
                    if (op.first == "UPLOAD") {
                        if (plan.state != "COMMITTED") throw ApiException.conflict("plan_not_committed", "Uploads are acknowledged after the plan is committed", path = a.path)
                        val e = entries.find(plan.spaceId, a.path)
                        if (op.second == "FILE" && (e == null || e.deleted || e.sha256 != a.sha256)) {
                            throw ApiException.conflict("ack_mismatch", "The acknowledged content is not the committed server content", path = a.path)
                        }
                    }
                    if (op.second == "FILE" && op.first in setOf("DOWNLOAD", "ADOPT") && a.sha256 != op.third) {
                        throw ApiException.conflict("ack_mismatch", "The acknowledged content is not the planned content", path = a.path)
                    }
                    if (op.first in setOf("PROPOSE_SERVER_DELETION", "PROPOSE_LOCAL_DELETION") && a.sha256 != null) {
                        throw ApiException.invalid("invalid_ack", "Deletion acknowledgements carry no content hash", a.path)
                    }
                    if (op.first == "PROPOSE_LOCAL_DELETION") audit.record(ownerId, "deletion.local_applied", plan.spaceId, mapOf("path" to a.path))
                }
                jdbc.sql("INSERT OR REPLACE INTO plan_acks (plan_id, path, outcome, sha256, recorded_at) VALUES (?,?,?,?,?)")
                    .args(planId, a.path, a.outcome, a.sha256, now).update()
            }
        }
        return summary(planId, req.entries.size)
    }

    fun summary(planId: String, recorded: Int = 0): AckResult {
        val counts = jdbc.sql("SELECT outcome, COUNT(*) FROM plan_acks WHERE plan_id = ? GROUP BY outcome").param(planId)
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().toMap()
        val total = jdbc.sql("SELECT COUNT(*) FROM plan_operations WHERE plan_id = ?").param(planId).query(Int::class.java).single()
        return AckResult(recorded, counts["APPLIED"] ?: 0, counts["FAILED"] ?: 0, counts["PENDING"] ?: 0, total)
    }
}
