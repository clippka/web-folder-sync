package app.foldersync.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Clock
import javax.sql.DataSource

/** Creates and verifies the `/data` layout. Any failure stops startup with a message naming the directory. */
object StorageLayout {
    fun initialize(p: FolderSyncProperties) {
        for (dir in listOf(p.dbDir, p.blobDir, p.stagingDir)) {
            try {
                Files.createDirectories(dir)
                val probe = Files.createTempFile(dir, ".write-check", ".tmp")
                Files.delete(probe)
            } catch (e: Exception) {
                throw StartupFailure("Storage directory '${dir.toAbsolutePath()}' is not writable: ${e.message}", e)
            }
        }
    }
}

/** Short transaction boundary: callers never hold one open across file transfer. */
class Tx(private val template: TransactionTemplate) {
    fun <T> run(block: () -> T): T {
        var result: Any? = null
        template.executeWithoutResult { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}

@Configuration
@ConfigurationPropertiesScan("app.foldersync")
class PersistenceConfig {

    @Bean(destroyMethod = "close")
    fun dataSource(p: FolderSyncProperties): DataSource {
        StorageLayout.initialize(p)
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${p.dbFile.toAbsolutePath()}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 4
            poolName = "foldersync-sqlite"
            // WAL keeps the -wal/-shm sidecars next to the database file inside /data/db.
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "FULL")
            addDataSourceProperty("busy_timeout", "10000")
            addDataSourceProperty("foreign_keys", "true")
            // Write transactions take the lock up front, so they never fail on a read-to-write upgrade.
            addDataSourceProperty("transaction_mode", "IMMEDIATE")
        }
        val ds = try {
            HikariDataSource(cfg)
        } catch (e: Exception) {
            throw StartupFailure("Cannot open database '${p.dbFile.toAbsolutePath()}': ${e.message}", e)
        }
        try {
            Migrator.migrate(ds)
        } catch (e: Exception) {
            ds.close()
            throw e
        }
        return ds
    }

    @Bean
    fun tx(manager: PlatformTransactionManager): Tx = Tx(TransactionTemplate(manager))

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
