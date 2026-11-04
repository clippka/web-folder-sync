package app.foldersync

import app.foldersync.blobs.MaintenanceService
import app.foldersync.reconciliation.Resolution
import app.foldersync.reconciliation.ResolveRequest
import app.foldersync.support.Ctx
import app.foldersync.support.Local
import app.foldersync.support.SyncOutcome
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Step 6: durable conflict review, Keep both, delete-versus-edit rules (deletion gate closed) and previews. */
class ConflictResolutionTest : SyncTest() {

    companion object {
        /** These tests cover the operator kill switch: with deletion switched off nothing can be deleted. */
        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun killSwitch(r: org.springframework.test.context.DynamicPropertyRegistry) {
            r.add("foldersync.deletion-enabled") { "false" }
        }
    }
    @Autowired lateinit var maintenance: MaintenanceService

    private fun bytes(s: String) = s.toByteArray()
    private fun conflictOf(user: Ctx, out: SyncOutcome, index: Int = 0): JsonNode =
        user.user.get("/api/conflicts/${out.commitBody!!["conflicts"][index]["id"].asText()}").expect(200).body()
    private fun text(ctx: Ctx, blobId: String) = String(download(ctx, blobId).expect(200).andReturn().response.contentAsByteArray)
    private fun resolve(ctx: Ctx, c: JsonNode, r: Resolution, key: String, version: Long = c["serverEntryVersion"].asLong()) =
        ctx.user.post("/api/conflicts/${c["id"].asText()}/resolve", ResolveRequest(r, version), key)

    // ---- Keep both ------------------------------------------------------------------------------------------------

    @Test
    fun `keep both leaves the server variant in place and stores the local variant beside it`() {
        val a = setup()
        sync(a, mapOf("dir" to Local.Dir, "dir/report.txt" to Local.File(bytes("server version"))))
        val b = secondDevice(a)
        val out = sync(b, mapOf("dir" to Local.Dir, "dir/report.txt" to Local.File(bytes("local version"))))
        val c = conflictOf(b, out)
        assertTrue(c["options"].list().map { it.asText() }.containsAll(listOf("KEEP_LOCAL", "KEEP_SERVER", "KEEP_BOTH")))
        assertTrue(c["serverUpdatedAt"].asLong() > 0, "the server variant's last change is reported")

        val r = resolve(b, c, Resolution.KEEP_BOTH, "kb-1").expect(200).body()
        val sibling = r["siblingPath"].asText()
        assertTrue(Regex("dir/report \\(conflict Second machine \\d{4}-\\d{2}-\\d{2}\\)\\.txt").matches(sibling), sibling)
        assertEquals("RESOLVED", r["conflict"]["state"].asText())
        assertEquals("KEEP_BOTH", r["conflict"]["resolution"].asText())

        val m = manifest(a)
        assertEquals("server version", text(a, m["dir/report.txt"]!!["blobId"].asText()))
        assertEquals("local version", text(a, m[sibling]!!["blobId"].asText()))
        assertEquals(sha256(bytes("local version")), m[sibling]!!["sha256"].asText())

        // The decision is replayed, not repeated, under the same key; a second decision is refused.
        assertEquals(sibling, resolve(b, c, Resolution.KEEP_BOTH, "kb-1").expect(200).body()["siblingPath"].asText())
        assertEquals("already_resolved", resolve(b, c, Resolution.KEEP_SERVER, "kb-2").expect(409).code())
        maintenance.runOnce()
        assertEquals("local version", text(a, manifest(a)[sibling]!!["blobId"].asText()), "the local variant survives cleanup")
    }

    @Test
    fun `keep both picks a fresh name when the usual one is taken`() {
        val a = setup()
        sync(a, mapOf("n.txt" to Local.File(bytes("server"))))
        val b = secondDevice(a)
        val first = conflictOf(b, sync(b, mapOf("n.txt" to Local.File(bytes("local one")))))
        val s1 = resolve(b, first, Resolution.KEEP_BOTH, "n-1").expect(200).body()["siblingPath"].asText()

        // A second conflict at the same path on the same day must not land on the first sibling's name.
        val c2 = secondDevice(a)
        val again = conflictOf(c2, sync(c2, mapOf("n.txt" to Local.File(bytes("local two")))))
        val s2 = resolve(c2, again, Resolution.KEEP_BOTH, "n-2").expect(200).body()["siblingPath"].asText()
        assertNotEquals(s1, s2)
        assertEquals(3, manifest(a).values.count { !it["deleted"].asBoolean() })
    }

    @Test
    fun `keep both is refused when the server changed since the review`() {
        val a = setup()
        sync(a, mapOf("s.txt" to Local.File(bytes("v1"))))
        val b = secondDevice(a)
        val c = conflictOf(b, sync(b, mapOf("s.txt" to Local.File(bytes("mine")))))
        assertEquals("stale_conflict", resolve(b, c, Resolution.KEEP_BOTH, "s-1", c["serverEntryVersion"].asLong() + 1).expect(409).code())
        assertEquals("UNRESOLVED", b.user.get("/api/conflicts/${c["id"].asText()}").body()["state"].asText())
    }

    // ---- delete versus edit, with the deletion gate closed --------------------------------------------------------

    @Test
    fun `delete versus edit offers only keep and confirm and never silently deletes`() {
        val a = setup()
        val other = secondDevice(a)
        val v = bytes("v1")
        sync(a, mapOf("f.txt" to Local.File(v)))
        sync(other, mapOf("f.txt" to Local.File(bytes("v2 edited elsewhere"))), mapOf("f.txt" to base(v)))
        // This machine deleted the file locally while the server copy was edited.
        val out = sync(a, emptyMap(), mapOf("f.txt" to base(v)))
        val c = conflictOf(a, out)
        assertEquals("DELETE_VS_EDIT", c["kind"].asText())
        assertEquals("ABSENT", c["local"]["type"].asText())
        assertFalse(c["options"].list().map { it.asText() }.contains("KEEP_BOTH"), "there is no second file to keep")
        assertEquals("resolution_not_available", resolve(a, c, Resolution.KEEP_BOTH, "dv-0").expect(422).code())

        // "Confirm deletion" (the local deletion wins) is refused while the gate is closed, and nothing changes.
        assertEquals("deletion_disabled", resolve(a, c, Resolution.KEEP_LOCAL, "dv-1").expect(409).code())
        assertFalse(manifest(a)["f.txt"]!!["deleted"].asBoolean())
        assertEquals("UNRESOLVED", a.user.get("/api/conflicts/${c["id"].asText()}").body()["state"].asText())

        // "Keep modified file" (the edited server copy wins) always works and deletes nothing.
        resolve(a, c, Resolution.KEEP_SERVER, "dv-2").expect(200)
        assertEquals(sha256(bytes("v2 edited elsewhere")), manifest(a)["f.txt"]!!["sha256"].asText())
    }

    // ---- conflicts block only dependent paths -----------------------------------------------------------------------

    @Test
    fun `unrelated changes complete while a conflict stays pending`() {
        val a = setup()
        sync(a, mapOf("hot.txt" to Local.File(bytes("server"))))
        val b = secondDevice(a)
        val out = sync(b, mapOf("hot.txt" to Local.File(bytes("local")), "cold.txt" to Local.File(bytes("new file"))))
        assertEquals("CONFLICT", kinds(out)["hot.txt"])
        assertEquals("UPLOAD", kinds(out)["cold.txt"])
        assertEquals(listOf("cold.txt"), out.commitBody!!["applied"].list().map { it["path"].asText() })
        assertEquals(1, b.user.get("/api/spaces/${b.spaceId}/conflicts").body().size())
        // Running the same state again keeps the single open conflict instead of piling up new ones.
        val again = sync(b, mapOf("hot.txt" to Local.File(bytes("local")), "cold.txt" to Local.File(bytes("new file"))), mapOf("cold.txt" to base(bytes("new file"))))
        assertEquals(true, again.commitBody!!["conflicts"][0]["existing"].asBoolean())
        assertEquals(1, b.user.get("/api/spaces/${b.spaceId}/conflicts").body().size())
    }

    // ---- previews ----------------------------------------------------------------------------------------------------

    private fun previewOf(ctx: Ctx, c: JsonNode, side: String) =
        ctx.user.get("/api/conflicts/${c["id"].asText()}/preview", "side" to side).expect(200).body()

    @Test
    fun `previews are bounded text and never decode binary`() {
        val a = setup()
        val big = "line of text\n".repeat(3000)                                    // well over the preview limit
        sync(a, mapOf("t.txt" to Local.File(bytes(big)), "b.bin" to Local.File(byteArrayOf(1, 0, 2, 3)), "u.txt" to Local.File(bytes("café ☃"))))
        val b = secondDevice(a)
        val out = sync(b, mapOf(
            "t.txt" to Local.File(bytes("<script>alert(1)</script>")), "b.bin" to Local.File(byteArrayOf(9, 9, 0)),
            "u.txt" to Local.File(byteArrayOf(0x66, 0xC3.toByte(), 0x28)),        // invalid UTF-8
        ))
        val byPath = out.commitBody!!["conflicts"].list().associate { it["path"].asText() to b.user.get("/api/conflicts/${it["id"].asText()}").body() }

        val serverText = previewOf(b, byPath["t.txt"]!!, "server")
        assertEquals("text", serverText["kind"].asText())
        assertTrue(serverText["truncated"].asBoolean())
        assertTrue(serverText["text"].asText().length <= 16 * 1024)
        assertEquals(big.length.toLong(), serverText["size"].asLong())
        val localText = previewOf(b, byPath["t.txt"]!!, "local")
        assertEquals("<script>alert(1)</script>", localText["text"].asText(), "returned as data; the page renders it with textContent")
        assertFalse(localText["truncated"].asBoolean())

        assertEquals("binary", previewOf(b, byPath["b.bin"]!!, "server")["kind"].asText())
        assertTrue(previewOf(b, byPath["b.bin"]!!, "server")["text"].isNull)
        assertEquals("binary", previewOf(b, byPath["u.txt"]!!, "local")["kind"].asText(), "invalid UTF-8 is not shown")
        assertEquals("text", previewOf(b, byPath["u.txt"]!!, "server")["kind"].asText())

        b.user.get("/api/conflicts/${byPath["t.txt"]!!["id"].asText()}/preview", "side" to "sideways").expect(400)
        // Downloads of either variant are forced attachments.
        val dl = download(b, byPath["t.txt"]!!["local"]["blobId"].asText()).expect(200).andReturn().response
        assertTrue(dl.getHeader("Content-Disposition")!!.startsWith("attachment"))
        assertEquals("nosniff", dl.getHeader("X-Content-Type-Options"))
        assertEquals("application/octet-stream", dl.contentType)
    }

    @Test
    fun `another user cannot preview a conflict`() {
        val a = setup()
        sync(a, mapOf("p.txt" to Local.File(bytes("x"))))
        val b = secondDevice(a)
        val c = conflictOf(b, sync(b, mapOf("p.txt" to Local.File(bytes("y")))))
        val stranger = newUser()
        stranger.get("/api/conflicts/${c["id"].asText()}/preview", "side" to "local").expect(404)
        resolve(Ctx(stranger, a.spaceId, a.deviceId, a.bindingId, a.epoch), c, Resolution.KEEP_BOTH, "x-1").expect(404)
    }
}
