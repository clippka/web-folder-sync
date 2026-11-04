package app.foldersync.revisions

import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.common.Ids
import app.foldersync.config.FolderSyncProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

/**
 * The reconciliation epoch identifies one lineage of server state. Clients present the epoch they last saw; a
 * different value means the server was restored (or replaced) and revision numbers may have been rolled back, so
 * the client must run recovery reconciliation instead of trusting its baselines.
 *
 * The epoch is stored in the database and mirrored in `<data>/EPOCH`. A restore replaces the database but, per the
 * documented procedure, not that sidecar; the mismatch rotates the epoch at startup. Operators can also force a
 * rotation with `foldersync.rotate-epoch=true`.
 */
@Service
class EpochService(
    private val jdbc: JdbcClient,
    private val props: FolderSyncProperties,
    private val audit: AuditService,
) : InitializingBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cached = AtomicReference<String?>(null)

    fun current(): String = cached.get() ?: load().also { cached.set(it) }

    private fun load(): String =
        jdbc.sql("SELECT value FROM server_meta WHERE key = 'epoch'").query(String::class.java).optional().orElse(null)
            ?: Ids.token().also { writeDb(it); writeSidecar(it) }

    /** Throws the `epoch_mismatch` error when [presented] is not the current epoch. */
    fun require(presented: String?) {
        if (presented != current()) {
            throw ApiException.conflict(
                "epoch_mismatch",
                "The server's reconciliation epoch changed; run recovery reconciliation before syncing",
                retryable = false,
                details = mapOf("currentEpoch" to current(), "recovery" to true),
            )
        }
    }

    fun rotate(reason: String): String {
        val epoch = Ids.token()
        writeDb(epoch)
        writeSidecar(epoch)
        cached.set(epoch)
        audit.record(null, "epoch.rotated", detail = mapOf("reason" to reason))
        log.warn("Reconciliation epoch rotated ({}); clients will run recovery reconciliation", reason)
        return epoch
    }

    override fun afterPropertiesSet() = reconcileAtStartup()

    /** Runs while the context is built, so no request can observe a stale epoch. Migrations already ran. */
    fun reconcileAtStartup() {
        val db = jdbc.sql("SELECT value FROM server_meta WHERE key = 'epoch'").query(String::class.java).optional().orElse(null)
        val sidecar = runCatching { Files.readString(sidecarPath()).trim() }.getOrNull()
        when {
            db == null -> { current(); }
            props.rotateEpoch -> rotate("operator-requested")
            sidecar != null && sidecar != db -> rotate("database does not match epoch sidecar (restored backup)")
            // A database without its sidecar is a restored or copied volume: the sidecar is deliberately not part of a backup.
            sidecar == null -> rotate("epoch sidecar missing (restored data volume)")
            else -> cached.set(db)
        }
    }

    private fun sidecarPath() = props.dataDir.resolve("EPOCH")

    private fun writeDb(epoch: String) {
        jdbc.sql("INSERT INTO server_meta (key, value) VALUES ('epoch', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
            .param(epoch).update()
    }

    private fun writeSidecar(epoch: String) {
        val tmp = Files.createTempFile(props.dataDir, "epoch", ".tmp")
        Files.writeString(tmp, epoch)
        Files.move(tmp, sidecarPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
