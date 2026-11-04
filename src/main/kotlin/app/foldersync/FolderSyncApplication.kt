package app.foldersync

import app.foldersync.blobs.BackupService
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.nio.file.Path
import kotlin.system.exitProcess

@SpringBootApplication
class FolderSyncApplication

/**
 * `restore <backup-dir>` copies a backup into an EMPTY data directory (`FOLDERSYNC_DATA_DIR`, default `./data`) and exits,
 * so operators need no host tooling: `docker compose run --rm app restore /backup`. The next normal start rotates the epoch.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "restore") {
        if (args.size != 2) {
            System.err.println("usage: restore <backup-dir>")
            exitProcess(2)
        }
        val dataDir = Path.of(System.getenv("FOLDERSYNC_DATA_DIR") ?: "./data")
        try {
            BackupService.restore(Path.of(args[1]), dataDir)
        } catch (e: Exception) {
            System.err.println("restore failed: ${e.message}")
            exitProcess(1)
        }
        println("restored into $dataDir; start the server normally, it will rotate the reconciliation epoch")
        return
    }
    runApplication<FolderSyncApplication>(*args)
}
