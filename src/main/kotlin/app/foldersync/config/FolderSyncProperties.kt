package app.foldersync.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

/** All operator-facing settings. Every value has a safe default; nothing here is a credential. */
@ConfigurationProperties("foldersync")
data class FolderSyncProperties(
    val dataDir: Path = Path.of("./data"),
    /** Complete-file limit enforced at scan time (client) and on declared and received bytes (server). */
    val maxFileSizeBytes: Long = 104_857_600,
    val maxIncludedFiles: Int = 25_000,
    val chunkSizeBytes: Int = 8 * 1024 * 1024,
    /** Upper bound for one manifest/plan ingestion batch and one manifest page. */
    val maxManifestBatch: Int = 1_000,
    /** Upper bound for the entries of one plan (included files, directories and suspended paths). */
    val maxPlanEntries: Int = 200_000,
    val maxControlFileBytes: Int = 1_048_576,
    val uploadExpiry: Duration = Duration.ofHours(24),
    val leaseTtl: Duration = Duration.ofMinutes(15),
    val leaseMaxLifetime: Duration = Duration.ofHours(2),
    val planTtl: Duration = Duration.ofHours(1),
    /** Files in the blob directory without a database row are only removed once older than this. */
    val orphanFileGrace: Duration = Duration.ofHours(1),
    /** Manifest snapshots older than this many revisions are refused; the client must restart its scan. */
    val snapshotMaxLag: Long = 10_000,
    val cleanupInterval: Duration = Duration.ofMinutes(5),
    /**
     * Confirmed deletion. On since the Step 8 safety gate passed (docs/SAFETY_GATE.md); setting this to false is the
     * operator's kill switch: every deletion approval and every deleting conflict decision is then refused.
     */
    val deletionEnabled: Boolean = true,
    /** A deletion batch of at least this many files, or of more than [deletionWarnPercent] of the active files, is emphasized in review. */
    val deletionWarnCount: Int = 100,
    val deletionWarnPercent: Int = 20,
    /** How many bytes of a conflict variant the text preview returns at most. */
    val previewBytes: Int = 16 * 1024,
    /** Set once when starting a server restored from a backup; rotates the reconciliation epoch. */
    val rotateEpoch: Boolean = false,
    /** Free disk space that must remain after an upload reservation. */
    val minFreeDiskBytes: Long = 64L * 1024 * 1024,
    val admin: Admin = Admin(),
    val login: Login = Login(),
) {
    data class Admin(val username: String? = null, val passwordFile: Path? = null)

    data class Login(
        val maxFailures: Int = 5,
        val window: Duration = Duration.ofMinutes(15),
        val lockout: Duration = Duration.ofMinutes(15),
    )

    val dbDir: Path get() = dataDir.resolve("db")
    val blobDir: Path get() = dataDir.resolve("blobs")
    val stagingDir: Path get() = dataDir.resolve("staging")
    val dbFile: Path get() = dbDir.resolve("foldersync.db")
}
