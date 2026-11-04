package app.foldersync.blobs

import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.config.FolderSyncProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

data class BackupReport(val directory: Path, val blobsCopied: Int, val bytesCopied: Long)

/**
 * Consistent metadata-plus-blob backups.
 *
 * 1. Pause blob collection (the maintenance gate), so nothing the snapshot references can disappear while we copy.
 * 2. `VACUUM INTO` takes a transactionally consistent copy of the database while the application keeps serving.
 * 3. Copy every blob the snapshot lists. Blobs created after the snapshot are simply not part of this backup; the
 *    snapshot never references them.
 *
 * The `EPOCH` sidecar is deliberately not part of a backup. Restoring therefore yields a data volume whose database
 * has no matching sidecar, which makes the server rotate its reconciliation epoch on first start (see
 * [app.foldersync.revisions.EpochService]), so clients cannot mistake rolled-back revisions for current state.
 */
@Service
class BackupService(
    private val jdbc: JdbcClient,
    private val props: FolderSyncProperties,
    private val store: BlobStore,
    private val maintenance: MaintenanceService,
    private val audit: AuditService,
) {
    fun backup(target: Path): BackupReport {
        if (Files.exists(target) && Files.list(target).use { it.findAny().isPresent }) {
            throw ApiException.conflict("backup_target_not_empty", "Backup target must be empty")
        }
        val dbCopy = target.resolve("db").resolve("foldersync.db")
        Files.createDirectories(dbCopy.parent)
        maintenance.gate.lock()
        try {
            jdbc.sql("VACUUM INTO '${dbCopy.toAbsolutePath().toString().replace("'", "''")}'").update()
            var copied = 0
            var bytes = 0L
            DriverManager.getConnection("jdbc:sqlite:${dbCopy.toAbsolutePath()}").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT id FROM blobs").use { rs ->
                        while (rs.next()) {
                            val id = rs.getString(1)
                            val src = store.path(id)
                            if (!Files.isRegularFile(src)) throw IllegalStateException("Blob $id is referenced but missing; refusing to write an inconsistent backup")
                            val dst = target.resolve("blobs").resolve(id.substring(0, 2)).resolve(id.substring(2, 4)).resolve(id)
                            Files.createDirectories(dst.parent)
                            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
                            copied++
                            bytes += Files.size(dst)
                        }
                    }
                }
            }
            audit.record(null, "backup.created", detail = mapOf("blobs" to copied.toString()))
            return BackupReport(target, copied, bytes)
        } finally {
            maintenance.gate.unlock()
        }
    }

    companion object {
        /**
         * Offline restore into an empty data directory. Copies the database and blobs and nothing else; start the
         * server afterwards. Use only while the application is stopped.
         */
        fun restore(backup: Path, dataDir: Path) {
            require(Files.isRegularFile(backup.resolve("db").resolve("foldersync.db"))) { "Not a FolderSync backup: $backup" }
            if (Files.exists(dataDir) && Files.list(dataDir).use { it.findAny().isPresent }) throw IllegalStateException("Restore target must be empty: $dataDir")
            Files.createDirectories(dataDir.resolve("db"))
            Files.copy(backup.resolve("db").resolve("foldersync.db"), dataDir.resolve("db").resolve("foldersync.db"))
            val blobs = backup.resolve("blobs")
            if (Files.isDirectory(blobs)) {
                Files.walk(blobs).use { s ->
                    s.filter { Files.isRegularFile(it) }.forEach {
                        val dst = dataDir.resolve("blobs").resolve(blobs.relativize(it).toString())
                        Files.createDirectories(dst.parent)
                        Files.copy(it, dst)
                    }
                }
            }
        }
    }
}
