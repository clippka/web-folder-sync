package app.foldersync

import app.foldersync.reconciliation.StateDto
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small configured limits: 3 included files, 2000 byte files, snapshots valid for 2 revisions. */
class LimitsTest : SyncTest() {
    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun more(r: DynamicPropertyRegistry) {
            r.add("foldersync.max-included-files") { "3" }
            r.add("foldersync.max-file-size-bytes") { "2000" }
            r.add("foldersync.snapshot-max-lag") { "2" }
        }
    }

    private fun file(n: Int) = Local.File("content $n".toByteArray())

    @Test
    fun `more included files than the limit is refused while ignored files and directories do not count`() {
        val a = setup()
        val tooMany = createPlan(a, (1..4).associate { "f$it.txt" to file(it) }).expect(422)
        assertEquals("file_limit_exceeded", tooMany.code())

        // Ignore rules and directories do not consume the allowance.
        sync(a, mapOf(".gitignore" to Local.File("*.o\n".toByteArray())))
        val out = sync(
            a,
            mapOf(".gitignore" to Local.File("*.o\n".toByteArray()), "a.txt" to file(1), "b.txt" to file(2))
                + (1..20).associate { "d$it" to Local.Dir } + (1..20).associate { "obj$it.o" to file(it) },
            mapOf(".gitignore" to base("*.o\n".toByteArray())),
        )
        assertEquals(200, out.commit!!.andReturn().response.status)
        assertEquals(23, manifest(a).size)          // .gitignore + 2 files + 20 directories
    }

    @Test
    fun `the exact size limit transfers and one byte more is reported and skipped without touching existing copies`() {
        val a = setup()
        val exact = Local.File(ByteArray(2000) { 3 })
        val over = Local.File(ByteArray(2001) { 3 })
        val ok = sync(a, mapOf("exact.bin" to exact, "over.bin" to over, "small.txt" to file(1)))
        val kinds = kinds(ok)
        assertEquals("UPLOAD", kinds["exact.bin"])
        assertEquals("SUSPENDED", kinds["over.bin"], "oversized file is skipped, others continue")
        assertEquals("UPLOAD", kinds["small.txt"])
        assertEquals(setOf("exact.bin", "small.txt"), manifest(a).keys)

        // A synchronized file that later grows past the limit keeps its server copy and baseline; no deletion results.
        val grown = sync(a, mapOf("exact.bin" to over), mapOf("exact.bin" to base(exact.bytes)))
        assertEquals("SUSPENDED", kinds(grown)["exact.bin"])
        assertEquals(sha256(exact.bytes), manifest(a)["exact.bin"]!!["sha256"].asText())
        assertFalse(manifest(a)["exact.bin"]!!["deleted"].asBoolean())
    }

    @Test
    fun `the server enforces the limit on declared size and cannot be bypassed by splitting into chunks`() {
        val a = setup()
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 2001, "sha256" to sha256(ByteArray(1))), "lim-1").expect(413).also { assertEquals("file_too_large", it.code()) }
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 2000, "sha256" to sha256(ByteArray(2000))), "lim-2").expect(201).also {
            val id = it.body()["uploadId"].asText()
            // 2000 bytes = 2 chunks of 1024; a third chunk or an oversized chunk cannot add bytes.
            a.user.put("/api/uploads/$id/chunks/2", ByteArray(1024)).expect(422)
            a.user.put("/api/uploads/$id/chunks/1", ByteArray(1024)).expect(413)
            a.user.put("/api/uploads/$id/chunks/0", ByteArray(1024) { 1 }).expect(200)
            a.user.put("/api/uploads/$id/chunks/1", ByteArray(976) { 1 }).expect(200)
            assertEquals("digest_mismatch", a.user.post("/api/uploads/$id/complete").expect(422).code())
        }
        // The exact-limit file with matching digest succeeds.
        val exact = ByteArray(2000) { 9 }
        upload(a.user, a.spaceId, exact)
    }

    @Test
    fun `a snapshot older than the allowed lag expires and the client restarts from the current revision`() {
        val a = setup()
        sync(a, mapOf("a.txt" to file(1)))
        val snapshot = a.user.get("/api/spaces/${a.spaceId}/manifest").body()["revision"].asLong()
        var baselines = mapOf("a.txt" to base("content 1".toByteArray()))
        for (i in 2..5) {
            sync(a, mapOf("a.txt" to file(i)), baselines)
            baselines = mapOf("a.txt" to base("content $i".toByteArray()))
        }
        val expired = a.user.get("/api/spaces/${a.spaceId}/manifest", "revision" to snapshot.toString()).expect(410)
        assertEquals("snapshot_expired", expired.code())
        assertTrue(expired.body()["details"]["replan"].asBoolean())
        a.user.get("/api/spaces/${a.spaceId}/manifest").expect(200)
    }

    @Test
    fun `plans larger than a batch need several bounded requests`() {
        val a = setup()
        val first = (1..2).associate { "f$it.txt" to file(it) }
        val created = createPlan(a, first).expect(201).body()
        a.user.post("/api/plans/${created["id"].asText()}/entries", mapOf("entries" to emptyList<Any>())).expect(409)   // already finalized
        val big = a.user.post(
            "/api/spaces/${a.spaceId}/plans",
            mapOf(
                "bindingId" to a.bindingId, "epoch" to a.epoch, "policyRevision" to 0, "scanComplete" to true,
                "entries" to (1..1001).map { mapOf("path" to "p$it", "local" to mapOf("type" to "DIRECTORY")) },
            ),
        )
        big.expect(413)
        assertEquals("batch_too_large", big.code())
        val open = a.user.post("/api/spaces/${a.spaceId}/plans", mapOf("bindingId" to a.bindingId, "epoch" to a.epoch, "policyRevision" to 0, "scanComplete" to true)).expect(201).body()
        val id = open["id"].asText()
        assertEquals("INGESTING", open["state"].asText())
        a.user.post("/api/plans/$id/entries", mapOf("entries" to listOf(mapOf("path" to "a", "local" to mapOf("type" to "DIRECTORY"))))).expect(200)
        val done = a.user.post("/api/plans/$id/entries", mapOf("entries" to listOf(mapOf("path" to "b", "local" to mapOf("type" to "DIRECTORY"))), "final" to true)).expect(200).body()
        assertEquals("READY", done["state"].asText())
        assertEquals(2, done["counts"]["UPLOAD"].asInt())
    }
}
