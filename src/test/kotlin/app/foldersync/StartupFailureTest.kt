package app.foldersync

import app.foldersync.config.StartupFailure
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Storage and schema problems must stop startup with a message that names the problem. */
class StartupFailureTest {

    private fun run(dir: Path, vararg extra: String) = SpringApplicationBuilder(FolderSyncApplication::class.java).run(
        "--foldersync.data-dir=$dir", "--server.port=0", "--foldersync.cleanup-interval=PT24H", "--spring.main.banner-mode=off",
        *extra,
    )

    private fun rootCause(t: Throwable): Throwable = generateSequence(t) { it.cause }.last()
    private fun messages(t: Throwable) = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `an unusable data directory stops startup with a clear message`() {
        val file = Files.createTempFile("not-a-directory", ".txt")
        val e = assertFailsWith<Exception> { run(file) }
        assertTrue(messages(e).contains("is not writable"), messages(e))
    }

    @Test
    fun `a modified migration or a newer schema stops startup`() {
        val dir = Files.createTempDirectory("fs-migrate-")
        run(dir).close()
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("db/foldersync.db")}").use { c ->
            c.createStatement().executeUpdate("UPDATE schema_migrations SET checksum = 'tampered' WHERE version = 1")
        }
        val tampered = assertFailsWith<Exception> { run(dir) }
        assertTrue(messages(tampered).contains("was modified after being applied"), messages(tampered))

        val dir2 = Files.createTempDirectory("fs-newer-")
        run(dir2).close()
        DriverManager.getConnection("jdbc:sqlite:${dir2.resolve("db/foldersync.db")}").use { c ->
            c.createStatement().executeUpdate("INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (99, 'future', 'x', 0)")
        }
        val newer = assertFailsWith<Exception> { run(dir2) }
        assertTrue(messages(newer).contains("newer than this application"), messages(newer))
        assertEquals(StartupFailure::class, generateSequence<Throwable>(newer) { it.cause }.filterIsInstance<StartupFailure>().first()::class)
    }

    @Test
    fun `an unreadable administrator secret stops startup and no secret means no administrator`() {
        val dir = Files.createTempDirectory("fs-admin-")
        val e = assertFailsWith<Exception> { run(dir, "--foldersync.admin.username=boss", "--foldersync.admin.password-file=${dir.resolve("missing.secret")}") }
        assertTrue(messages(e).contains("cannot be read"), messages(e))

        val weak = Files.createTempFile("weak", ".secret").also { Files.writeString(it, "short") }
        val w = assertFailsWith<Exception> { run(Files.createTempDirectory("fs-admin2-"), "--foldersync.admin.username=boss", "--foldersync.admin.password-file=$weak") }
        assertTrue(messages(w).contains("weak_password"), messages(w))
    }

    @Test
    fun `restarting on the same directory keeps the schema and epoch`() {
        val dir = Files.createTempDirectory("fs-same-")
        val first = run(dir)
        val epoch1 = first.getBean(app.foldersync.revisions.EpochService::class.java).current()
        first.close()
        val second = run(dir)
        assertEquals(epoch1, second.getBean(app.foldersync.revisions.EpochService::class.java).current())
        second.close()
    }
}
