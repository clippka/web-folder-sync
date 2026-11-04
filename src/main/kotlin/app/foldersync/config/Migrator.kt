package app.foldersync.config

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest
import javax.sql.DataSource

class StartupFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Versioned forward-only SQL migrations from `classpath:db/migration/V<n>__<name>.sql`.
 * Applied versions are checksummed so an edited migration, or a database newer than this build, stops startup.
 * Each migration runs in its own transaction.
 */
object Migrator {
    private data class Migration(val version: Int, val name: String, val sql: String) {
        val checksum: String = MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun migrate(dataSource: DataSource): Int {
        val migrations = load()
        try {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)",
                    )
                }
                val applied = HashMap<Int, String>()
                c.createStatement().use { st ->
                    st.executeQuery("SELECT version, checksum FROM schema_migrations").use { rs ->
                        while (rs.next()) applied[rs.getInt(1)] = rs.getString(2)
                    }
                }
                val known = migrations.associateBy { it.version }
                applied.keys.filter { it !in known }.let {
                    if (it.isNotEmpty()) throw StartupFailure("Database schema version ${it.max()} is newer than this application (${migrations.lastOrNull()?.version ?: 0}); downgrades are not supported")
                }
                var count = 0
                for (m in migrations) {
                    val existing = applied[m.version]
                    if (existing != null) {
                        if (existing != m.checksum) throw StartupFailure("Migration V${m.version} (${m.name}) was modified after being applied")
                        continue
                    }
                    c.autoCommit = false
                    try {
                        for (stmt in statements(m.sql)) c.createStatement().use { it.executeUpdate(stmt) }
                        c.prepareStatement("INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (?,?,?,?)").use {
                            it.setInt(1, m.version); it.setString(2, m.name); it.setString(3, m.checksum); it.setLong(4, System.currentTimeMillis())
                            it.executeUpdate()
                        }
                        c.commit()
                        count++
                    } catch (e: Exception) {
                        c.rollback()
                        throw StartupFailure("Migration V${m.version} (${m.name}) failed: ${e.message}", e)
                    } finally {
                        c.autoCommit = true
                    }
                }
                return count
            }
        } catch (e: StartupFailure) {
            throw e
        } catch (e: Exception) {
            throw StartupFailure("Schema initialization failed: ${e.message}", e)
        }
    }

    private fun load(): List<Migration> {
        val pattern = Regex("""V(\d+)__(.+)\.sql""")
        val list = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql").mapNotNull { r ->
            val m = pattern.matchEntire(r.filename ?: return@mapNotNull null) ?: return@mapNotNull null
            Migration(m.groupValues[1].toInt(), m.groupValues[2], r.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) })
        }.sortedBy { it.version }
        require(list.map { it.version }.distinct().size == list.size) { "Duplicate migration versions" }
        return list
    }

    /** Splits on statement-terminating semicolons; migrations contain no triggers or string literals with ';'. */
    private fun statements(sql: String): List<String> =
        sql.lineSequence().map { it.substringBefore("--") }.joinToString("\n").split(';').map { it.trim() }.filter { it.isNotEmpty() }
}
