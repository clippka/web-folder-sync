package app.foldersync.blobs

import app.foldersync.common.IdempotencyService
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.Tx
import app.foldersync.manifests.EventsService
import app.foldersync.reconciliation.PlanService
import app.foldersync.transfers.LeaseService
import app.foldersync.transfers.UploadService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock

data class CleanupReport(
    val expiredUploads: Int = 0,
    val expiredPlans: Int = 0,
    val lapsedLeases: Int = 0,
    val deletedBlobs: Int = 0,
    val orphanFiles: Int = 0,
    val orphanStaging: Int = 0,
    val prunedRecords: Int = 0,
)

/**
 * Releases temporary state. Order matters: expire uploads and plans (which frees reservations and lets leases lapse),
 * drop lapsed leases, then collect blobs that nothing retains, then sweep files the database no longer knows about.
 * A blob is only ever removed after its row is deleted in a transaction that re-checked every reference, so a
 * concurrent commit either wins (and the blob stays) or loses (and could not have referenced it). The shared [gate]
 * lets backups pause collection while they copy files.
 */
@Service
class MaintenanceService(
    private val jdbc: JdbcClient,
    private val tx: Tx,
    private val clock: Clock,
    private val props: FolderSyncProperties,
    private val store: BlobStore,
    private val blobs: BlobService,
    private val uploads: UploadService,
    private val plans: PlanService,
    private val leases: LeaseService,
    private val idempotency: IdempotencyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val gate = ReentrantLock()

    fun runOnce(): CleanupReport {
        gate.lock()
        try {
            val expiredUploads = step("expire uploads") { uploads.expireStale() }
            val expiredPlans = step("expire plans") { plans.expireStale() }
            val lapsed = step("lapse leases") { leases.deleteLapsed() }
            val deleted = step("collect blobs") { collectBlobs() }
            val orphanFiles = step("sweep orphan files") { sweepOrphanFiles() }
            val orphanStaging = step("sweep staging") { uploads.removeOrphanStaging() }
            val pruned = step("prune records") { pruneRecords() }
            return CleanupReport(expiredUploads, expiredPlans, lapsed, deleted, orphanFiles, orphanStaging, pruned)
        } finally {
            gate.unlock()
        }
    }

    private fun collectBlobs(): Int {
        var total = 0
        while (true) {
            val ids = jdbc.sql(BlobReferences.UNREFERENCED).param("now", clock.millis()).param("limit", 200).query { rs, _ -> rs.getString(1) }.list()
            if (ids.isEmpty()) return total
            var removed = 0
            for (id in ids) {
                val gone = tx.run {
                    if (blobs.isRetained(id)) false else {
                        jdbc.sql("UPDATE conflicts SET local_blob_id = NULL WHERE local_blob_id = ?").param(id).update()
                        jdbc.sql("UPDATE conflicts SET server_blob_id = NULL WHERE server_blob_id = ?").param(id).update()
                        jdbc.sql("UPDATE uploads SET blob_id = NULL WHERE blob_id = ?").param(id).update()
                        jdbc.sql("DELETE FROM transfer_leases WHERE blob_id = ?").param(id).update()
                        jdbc.sql("DELETE FROM blobs WHERE id = ?").param(id).update() > 0
                    }
                }
                if (gone) { store.delete(id); removed++ }
            }
            total += removed
            if (removed == 0) return total
        }
    }

    private fun sweepOrphanFiles(): Int {
        val cutoff = clock.millis() - props.orphanFileGrace.toMillis()
        var n = 0
        store.walk().use { stream ->
            for (file in stream.toList()) {
                val name = file.fileName.toString()
                val known = name.matches(Regex("^[0-9a-f]{32}$")) &&
                    (jdbc.sql("SELECT COUNT(*) FROM blobs WHERE id = ?").param(name).query(Int::class.java).single() > 0 ||
                        jdbc.sql("SELECT COUNT(*) FROM uploads WHERE id = ? AND state = 'OPEN'").param(name).query(Int::class.java).single() > 0)
                if (!known && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    Files.deleteIfExists(file); n++
                }
            }
        }
        return n
    }

    private fun pruneRecords(): Int {
        val now = clock.millis()
        val planCutoff = now - Duration.ofDays(14).toMillis()
        var n = idempotency.prune(Duration.ofDays(7).toMillis())
        n += jdbc.sql("DELETE FROM plan_operations WHERE plan_id IN (SELECT id FROM plans WHERE state = 'COMMITTED' AND committed_at < ?)").param(planCutoff).update()
        n += jdbc.sql("DELETE FROM plan_acks WHERE plan_id IN (SELECT id FROM plans WHERE state = 'COMMITTED' AND committed_at < ?)").param(planCutoff).update()
        return n
    }

    /** One failing step must not stop the others; it is retried on the next run. */
    private fun step(name: String, block: () -> Int): Int =
        try {
            block()
        } catch (e: Exception) {
            log.error("Cleanup step '{}' failed: {}", name, e.message, e)
            0
        }
}

@Configuration
@EnableScheduling
class SchedulingConfig(
    private val maintenance: MaintenanceService,
    private val events: EventsService,
    private val props: FolderSyncProperties,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addFixedDelayTask(FixedDelayTask({ maintenance.runOnce() }, props.cleanupInterval, props.cleanupInterval))
        registrar.addFixedDelayTask(FixedDelayTask({ events.heartbeat() }, Duration.ofSeconds(25), Duration.ofSeconds(25)))
    }
}
