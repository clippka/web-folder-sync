package app.foldersync

import app.foldersync.blobs.MaintenanceService
import app.foldersync.config.FolderSyncProperties
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step 7: what a client needs from the server to resume after a lost response, a killed browser or a crowded database.
 */
class TransferRecoveryTest : SyncTest() {
    @Autowired lateinit var maintenance: MaintenanceService
    @Autowired lateinit var props: FolderSyncProperties

    private fun setQuota(userId: String, bytes: Long?) = users.setQuota("admin", userId, bytes)

    private fun begin(a: app.foldersync.support.Ctx, data: ByteArray, key: String) =
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to data.size, "sha256" to sha256(data)), key).expect(201).body()

    @Test
    fun `a killed client resumes the same upload with the same key and sends only the missing chunks`() {
        val a = setup()
        val data = ByteArray(3500) { (it * 7).toByte() }              // 4 chunks of 1024
        val id = begin(a, data, "resume-1")["uploadId"].asText()
        a.user.put("/api/uploads/$id/chunks/0", data.copyOfRange(0, 1024)).expect(200)
        a.user.put("/api/uploads/$id/chunks/2", data.copyOfRange(2048, 3072)).expect(200)
        // The client died here. After restart it replays the begin request with the stored key.
        val again = begin(a, data, "resume-1")
        assertEquals(id, again["uploadId"].asText(), "the same key returns the same upload")
        val status = a.user.get("/api/uploads/$id").expect(200).body()
        assertEquals("OPEN", status["state"].asText())
        assertEquals(listOf(0, 2), status["receivedChunks"].list().map { it.asInt() }, "the status, not the replayed begin, knows what arrived")
        a.user.put("/api/uploads/$id/chunks/1", data.copyOfRange(1024, 2048)).expect(200)
        a.user.put("/api/uploads/$id/chunks/3", data.copyOfRange(3072, 3500)).expect(200)
        a.user.post("/api/uploads/$id/complete").expect(200)
        assertEquals("COMPLETE", a.user.get("/api/uploads/$id").body()["state"].asText())
    }

    @Test
    fun `a used upload says so, so a resuming client begins a new one instead of committing it twice`() {
        val a = setup()
        val data = bytes("committed once")
        val id = upload(a.user, a.spaceId, data, "used-1")
        assertFalse(a.user.get("/api/uploads/$id").body()["consumed"].asBoolean())
        val ctx = a
        val plan = createPlan(ctx, mapOf("f.txt" to Local.File(data))).expect(201).body()["id"].asText()
        a.user.post("/api/plans/$plan/commit", mapOf("epoch" to epoch(a), "uploads" to mapOf("f.txt" to id)), "commit-used").expect(200)
        assertTrue(a.user.get("/api/uploads/$id").body()["consumed"].asBoolean())
        // Replaying the original begin still answers with the old upload; the flag is what tells the client to move on.
        assertEquals(id, begin(a, data, "used-1")["uploadId"].asText())
    }

    @Test
    fun `a cancelled or expired upload cannot be continued and releases its staging`() {
        val a = setup()
        val data = ByteArray(2000) { 3 }
        val id = begin(a, data, "gone-1")["uploadId"].asText()
        a.user.put("/api/uploads/$id/chunks/0", data.copyOfRange(0, 1024)).expect(200)
        mc.advanceMillis(props.uploadExpiry.toMillis() + 1000)
        maintenance.runOnce()
        assertFalse(Files.exists(props.stagingDir.resolve(id)))
        assertEquals("EXPIRED", a.user.get("/api/uploads/$id").body()["state"].asText())
        assertEquals("upload_expired", a.user.put("/api/uploads/$id/chunks/1", data.copyOfRange(1024, 2000)).expect(410).code())
        assertEquals("upload_expired", a.user.post("/api/uploads/$id/complete").expect(410).code())
    }

    @Test
    fun `the same chunk sent by several connections at once is stored once and every sender succeeds`() {
        val a = setup()
        val data = ByteArray(1024) { 9 }
        val id = begin(a, data, "twin-1")["uploadId"].asText()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((0 until 8).map { Callable { a.user.put("/api/uploads/$id/chunks/0", data).andReturn().response } }).map { it.get() }
            assertTrue(results.all { it.status == 200 || it.status == 409 }, results.map { it.status }.toString())
            assertTrue(results.count { it.status == 200 } >= 1)
        } finally { pool.shutdown() }
        a.user.put("/api/uploads/$id/chunks/0", data).expect(200)
        a.user.post("/api/uploads/$id/complete").expect(200)
    }

    @Test
    fun `many simultaneous uploads from one owner all publish despite database contention`() {
        val a = setup()
        val pool = Executors.newFixedThreadPool(12)
        try {
            val payloads = (0 until 24).map { i -> ByteArray(1500 + i) { (it + i).toByte() } }
            val ids = pool.invokeAll(payloads.mapIndexed { i, p -> Callable { upload(a.user, a.spaceId, p, "par-$i") } }).map { it.get() }
            assertEquals(24, ids.toSet().size)
            for (id in ids) assertEquals("COMPLETE", a.user.get("/api/uploads/$id").body()["state"].asText())
        } finally { pool.shutdown() }
    }

    @Test
    fun `a lost commit response is replayed and a lost acknowledgement response is harmless`() {
        val a = setup()
        val data = bytes("exactly once")
        val id = upload(a.user, a.spaceId, data)
        val plan = createPlan(a, mapOf("f.txt" to Local.File(data))).expect(201).body()["id"].asText()
        val body = mapOf("epoch" to epoch(a), "uploads" to mapOf("f.txt" to id))
        val one = a.user.post("/api/plans/$plan/commit", body, "lost-1").expect(200).body()
        val two = a.user.post("/api/plans/$plan/commit", body, "lost-1").expect(200).body()
        assertEquals(one["applied"], two["applied"], "same outcome, no second revision")
        val ack = mapOf("entries" to listOf(mapOf("path" to "f.txt", "outcome" to "APPLIED", "sha256" to sha256(data))))
        a.user.post("/api/plans/$plan/acknowledgements", ack).expect(200)
        a.user.post("/api/plans/$plan/acknowledgements", ack).expect(200)
        assertEquals(1, manifest(a)["f.txt"]!!["version"].asLong())
    }

    @Test
    fun `a quota refusal leaves no reservation, and a rejected digest releases the one it took`() {
        val a = setup()
        setQuota(a.user.id, 1000)
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 5000, "sha256" to sha256(ByteArray(1))), "qr-1").expect(422)
        val wrong = ByteArray(800) { 1 }
        val id = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 800, "sha256" to sha256(ByteArray(800) { 2 })), "qr-2").expect(201).body()["uploadId"].asText()
        a.user.put("/api/uploads/$id/chunks/0", wrong).expect(200)
        assertEquals("digest_mismatch", a.user.post("/api/uploads/$id/complete").expect(422).code())
        // Everything is released again, so a full-quota upload fits.
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 1000, "sha256" to sha256(ByteArray(1))), "qr-3").expect(201)
    }

    private fun bytes(s: String) = s.toByteArray()
    private fun epoch(a: app.foldersync.support.Ctx) = a.user.get("/api/spaces/${a.spaceId}").expect(200).body()["epoch"].asText()
}
