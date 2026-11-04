package app.foldersync

import app.foldersync.revisions.CommitRequest
import app.foldersync.revisions.EpochService
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncFlowTest : SyncTest() {

    companion object {
        /** These tests cover the operator kill switch: with deletion switched off nothing can be deleted. */
        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun killSwitch(r: org.springframework.test.context.DynamicPropertyRegistry) {
            r.add("foldersync.deletion-enabled") { "false" }
        }
    }
    @Autowired lateinit var epochs: EpochService

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `two devices exchange exact bytes and nested empty directories in both directions`() {
        val a = setup()
        val crlf = bytes("line one\r\nline two\r\näöü")
        val first = sync(a, mapOf("src" to Local.Dir, "src/empty" to Local.Dir, "src/deep" to Local.Dir, "src/deep/a.txt" to Local.File(crlf)))
        assertEquals(200, first.commit!!.andReturn().response.status)
        assertEquals(4, first.commitBody!!["applied"].size())

        val b = secondDevice(a)
        val second = sync(b, emptyMap())
        assertEquals(setOf("DOWNLOAD"), second.ops.map { it["kind"].asText() }.toSet())
        // Parents are created before children.
        val order = second.ops.map { it.path() }
        assertTrue(order.indexOf("src") < order.indexOf("src/deep") && order.indexOf("src/deep") < order.indexOf("src/deep/a.txt"))
        val file = second.ops.first { it.path() == "src/deep/a.txt" }
        val received = download(b, file["blobId"].asText()).expect(200).andReturn().response.contentAsByteArray
        assertContentEquals(crlf, received, "CRLF and non-ASCII bytes must be preserved exactly")

        // The empty directory travels without any placeholder file.
        assertEquals("DIRECTORY", manifest(a)["src/empty"]!!["type"].asText())
        assertEquals(setOf("src", "src/empty", "src/deep", "src/deep/a.txt"), manifest(a).keys)

        // Opposite direction: B creates a nested empty tree, A receives it.
        val baselines = second.ops.associate { op -> op.path() to op["state"].let { s -> app.foldersync.reconciliation.StateDto(s["type"].asText(), s["sha256"]?.takeUnless { it.isNull }?.asText(), s["size"]?.takeUnless { it.isNull }?.asLong()) } }
        val local = mapOf("src" to Local.Dir, "src/empty" to Local.Dir, "src/deep" to Local.Dir, "src/deep/a.txt" to Local.File(crlf), "x" to Local.Dir, "x/y" to Local.Dir)
        val third = sync(b, local, baselines)
        assertEquals(setOf("x", "x/y"), third.commitBody!!["applied"].list().map { it.path() }.toSet())
        val back = sync(a, emptyMap())
        assertTrue(back.ops.any { it.path() == "x/y" && it["kind"].asText() == "DOWNLOAD" })
    }

    @Test
    fun `joining an empty or partial folder never deletes anything`() {
        val a = setup()
        sync(a, mapOf("keep.txt" to Local.File(bytes("keep")), "dir" to Local.Dir, "dir/inner.txt" to Local.File(bytes("inner"))))
        val b = secondDevice(a)
        // B has nothing: only downloads.
        val empty = sync(b, emptyMap(), commit = false)
        assertTrue(empty.ops.none { it["kind"].asText().contains("DELETION") })
        // B has a different partial set and no baseline: still no deletion, extra file is added.
        val partial = sync(b, mapOf("extra.txt" to Local.File(bytes("extra")), "keep.txt" to Local.File(bytes("keep"))))
        val kinds = kinds(partial)
        assertEquals("UPLOAD", kinds["extra.txt"])
        assertEquals("ADOPT", kinds["keep.txt"], "identical content is adopted without a transfer")
        assertEquals("DOWNLOAD", kinds["dir/inner.txt"])
        assertTrue(kinds.values.none { it.contains("DELETION") })
        assertEquals(setOf("keep.txt", "dir", "dir/inner.txt", "extra.txt"), manifest(a).keys)
    }

    @Test
    fun `different files at the same path become a conflict that keeps both variants`() {
        val a = setup()
        sync(a, mapOf("same.txt" to Local.File(bytes("server version"))))
        val b = secondDevice(a)
        val out = sync(b, mapOf("same.txt" to Local.File(bytes("local version"))))
        assertEquals("CONFLICT", kinds(out)["same.txt"])
        val conflict = out.commitBody!!["conflicts"][0]
        val detail = b.user.get("/api/conflicts/${conflict["id"].asText()}").expect(200).body()
        assertEquals("UNRESOLVED", detail["state"].asText())
        assertEquals("CONTENT", detail["kind"].asText())
        assertEquals("Second machine", detail["deviceLabel"].asText())
        val localBlob = detail["local"]["blobId"].asText()
        val serverBlob = detail["server"]["blobId"].asText()
        assertEquals("local version", String(download(b, localBlob).expect(200).andReturn().response.contentAsByteArray))
        assertEquals("server version", String(download(b, serverBlob).expect(200).andReturn().response.contentAsByteArray))
        // The server entry itself is untouched.
        assertEquals(sha256(bytes("server version")), manifest(a)["same.txt"]!!["sha256"].asText())
    }

    @Test
    fun `identical concurrent edits converge without a conflict`() {
        val a = setup()
        val b = secondDevice(a)
        val x = Local.File(bytes("same content"))
        sync(a, mapOf("f.txt" to x))
        val out = sync(b, mapOf("f.txt" to x))
        assertEquals("ADOPT", kinds(out)["f.txt"])
        assertEquals(0, out.commitBody!!["conflicts"].size())
    }

    @Test
    fun `a stale plan cannot overwrite a newer server revision`() {
        val a = setup()
        val v1 = bytes("v1")
        sync(a, mapOf("doc.txt" to Local.File(v1)))
        val b = secondDevice(a)
        val base = mapOf("doc.txt" to base(v1))

        // A plans an edit but has not committed yet.
        val planA = sync(a, mapOf("doc.txt" to Local.File(bytes("edit from A"))), base, commit = false)
        // B commits its own edit first.
        val bOut = sync(b, mapOf("doc.txt" to Local.File(bytes("edit from B"))), base)
        assertEquals(200, bOut.commit!!.andReturn().response.status)

        val up = upload(a.user, a.spaceId, bytes("edit from A"))
        val stale = a.user.post("/api/plans/${planA.plan["id"].asText()}/commit", CommitRequest(a.epoch, mapOf("doc.txt" to up)), "stale-1")
        stale.expect(409)
        assertEquals("stale_plan", stale.code())
        assertTrue(stale.body()["retryable"].asBoolean())
        assertTrue(stale.body()["details"]["replan"].asBoolean())
        assertEquals(sha256(bytes("edit from B")), manifest(a)["doc.txt"]!!["sha256"].asText(), "newer content survived")
    }

    @Test
    fun `retrying a commit with the same key replays and never duplicates`() {
        val a = setup()
        val plan = createPlan(a, mapOf("r.txt" to Local.File(bytes("retry me")))).expect(201).body()
        val id = plan["id"].asText()
        val up = upload(a.user, a.spaceId, bytes("retry me"))
        val req = CommitRequest(a.epoch, mapOf("r.txt" to up))
        val first = a.user.post("/api/plans/$id/commit", req, "same-key").expect(200).body()
        val revisionAfterFirst = a.user.get("/api/spaces/${a.spaceId}").body()["revision"].asLong()
        val replay = a.user.post("/api/plans/$id/commit", req, "same-key").expect(200).body()
        assertEquals(first, replay)
        assertEquals(revisionAfterFirst, a.user.get("/api/spaces/${a.spaceId}").body()["revision"].asLong(), "no second revision")
        // A different key is a different request against a plan that is already committed.
        a.user.post("/api/plans/$id/commit", req, "other-key").expect(409)
        // The same key with a different body is rejected rather than replayed.
        a.user.post("/api/plans/$id/commit", req.copy(approvedServerDeletions = listOf("zzz")), "same-key").expect(409)
        // Missing key.
        a.user.post("/api/plans/$id/commit", req).expect(400)
    }

    @Test
    fun `a commit that fails midway applies nothing and leaves uploads usable`() {
        val a = setup()
        val goodBytes = bytes("good")
        val plan = createPlan(a, mapOf("good.txt" to Local.File(goodBytes), "bad.txt" to Local.File(bytes("bad")))).expect(201).body()
        val good = upload(a.user, a.spaceId, goodBytes)
        val wrong = upload(a.user, a.spaceId, bytes("not the planned bytes"))
        val result = a.user.post("/api/plans/${plan["id"].asText()}/commit", CommitRequest(a.epoch, mapOf("good.txt" to good, "bad.txt" to wrong)), "partial-1")
        result.expect(422)
        assertEquals("upload_mismatch", result.code())
        assertTrue(manifest(a).isEmpty(), "nothing was published")
        // The good upload was not consumed by the failed attempt.
        val retry = a.user.post("/api/plans/${plan["id"].asText()}/commit", CommitRequest(a.epoch, mapOf("good.txt" to good, "bad.txt" to upload(a.user, a.spaceId, bytes("bad")))), "partial-2")
        retry.expect(200)
        assertEquals(setOf("good.txt", "bad.txt"), manifest(a).keys)
    }

    @Test
    fun `incomplete or unreadable scans and missing baselines never produce deletions`() {
        val a = setup()
        val v = bytes("data")
        sync(a, mapOf("f.txt" to Local.File(v), "g.txt" to Local.File(v)))
        val baselines = mapOf("f.txt" to base(v), "g.txt" to base(v))

        val incomplete = sync(a, emptyMap(), baselines, scanComplete = false, commit = false)
        assertTrue(incomplete.ops.none { it["kind"].asText().contains("DELETION") }, "incomplete scan: ${kinds(incomplete)}")

        val unreadable = sync(a, mapOf("f.txt" to Local.Unreadable, "g.txt" to Local.File(v)), baselines, commit = false)
        assertNotEquals("PROPOSE_SERVER_DELETION", kinds(unreadable)["f.txt"])

        val noBaseline = sync(a, emptyMap(), emptyMap(), commit = false)
        assertTrue(noBaseline.ops.none { it["kind"].asText().contains("DELETION") })

        // A genuine, complete scan does propose the deletion, as a decision, and it is not applied without approval.
        val complete = sync(a, mapOf("g.txt" to Local.File(v)), baselines)
        assertEquals("PROPOSE_SERVER_DELETION", kinds(complete)["f.txt"])
        assertTrue(complete.commitBody!!["pendingDecisions"].list().map { it.asText() }.contains("f.txt"))
        assertTrue(manifest(a).containsKey("f.txt") && !manifest(a)["f.txt"]!!["deleted"].asBoolean())
    }

    @Test
    fun `deletion cannot be approved while the kill switch is on`() {
        val a = setup()
        val v = bytes("data")
        sync(a, mapOf("f.txt" to Local.File(v)))
        val out = sync(a, emptyMap(), mapOf("f.txt" to base(v)), approveServerDeletions = true)
        out.commit!!.expect(409)
        assertEquals("deletion_disabled", out.commit.code())
        assertFalse(manifest(a)["f.txt"]!!["deleted"].asBoolean())
    }

    @Test
    fun `invalid and colliding paths are reported and excluded not renamed`() {
        val a = setup()
        val plan = sync(
            a,
            mapOf(
                "ok.txt" to Local.File(bytes("ok")), "../escape.txt" to Local.File(bytes("x")), "/abs.txt" to Local.File(bytes("x")),
                "bad:name.txt" to Local.File(bytes("x")), "CON" to Local.File(bytes("x")), "dir\\win.txt" to Local.File(bytes("x")),
                "Case.txt" to Local.File(bytes("1")), "case.TXT" to Local.File(bytes("2")),
            ),
        )
        val rejected = plan.plan["rejected"].list().map { it.path() }.toSet()
        assertTrue(rejected.containsAll(setOf("../escape.txt", "/abs.txt", "bad:name.txt", "CON", "dir\\win.txt", "Case.txt", "case.TXT")), "rejected=$rejected")
        assertEquals(setOf("ok.txt"), manifest(a).keys)
    }

    @Test
    fun `ignore files bump the policy revision and force a rescan before content is planned`() {
        val a = setup()
        val ignoreV1 = bytes("build/\n*.log\n")
        // Control files are planned before ordinary content: the first plan only carries the control file.
        val first = sync(a, mapOf(".gitignore" to Local.File(ignoreV1), "src.txt" to Local.File(bytes("s"))))
        assertEquals("CONTROL", first.plan["phase"].asText())
        assertTrue(first.plan["rescanRequired"].asBoolean())
        assertEquals(setOf(".gitignore"), manifest(a).keys)
        assertEquals(1, policyRevision(a))

        // A plan made against the old policy revision is refused.
        val stale = a.user.post("/api/spaces/${a.spaceId}/plans", mapOf("bindingId" to a.bindingId, "epoch" to a.epoch, "policyRevision" to 0, "scanComplete" to true))
        stale.expect(409); assertEquals("stale_policy", stale.code())

        // After the rescan, content follows the policy: ignored paths are suspended and never uploaded.
        val second = sync(a, mapOf(".gitignore" to Local.File(ignoreV1), "src.txt" to Local.File(bytes("s")), "build/out.o" to Local.File(bytes("o")), "debug.log" to Local.File(bytes("l")), ".git/config" to Local.File(bytes("g"))), mapOf(".gitignore" to base(ignoreV1)))
        assertEquals("CONTENT", second.plan["phase"].asText())
        val kinds = kinds(second)
        assertEquals("UPLOAD", kinds["src.txt"])
        assertEquals("SUSPENDED", kinds["build/out.o"])
        assertEquals("SUSPENDED", kinds["debug.log"])
        assertEquals("SUSPENDED", kinds[".git/config"])
        assertEquals(setOf(".gitignore", "src.txt"), manifest(a).keys)
    }

    @Test
    fun `newly ignored paths stay on the server untouched and are not turned into deletions`() {
        val a = setup()
        val o = bytes("object")
        sync(a, mapOf("tmp/cache.bin" to Local.File(o), "tmp" to Local.Dir))
        val ignore = bytes("tmp/\n")
        sync(a, mapOf(".gitignore" to Local.File(ignore)))      // control phase publishes the rule
        // The user's folder no longer lists tmp (ignored); a complete scan must not delete it from the server.
        val out = sync(a, mapOf(".gitignore" to Local.File(ignore)), mapOf("tmp" to app.foldersync.reconciliation.StateDto("DIRECTORY"), "tmp/cache.bin" to base(o), ".gitignore" to base(ignore)))
        assertEquals("SUSPENDED", kinds(out)["tmp/cache.bin"])
        assertTrue(out.ops.none { it["kind"].asText().contains("DELETION") })
        assertFalse(manifest(a)["tmp/cache.bin"]!!["deleted"].asBoolean())
    }

    @Test
    fun `conflicting ignore files pause the space`() {
        val a = setup()
        val b = secondDevice(a)
        sync(a, mapOf(".syncignore" to Local.File(bytes("one\n"))))
        val out = sync(b, mapOf(".syncignore" to Local.File(bytes("two\n")), "after.txt" to Local.File(bytes("x"))))
        assertTrue(out.plan["paused"].asBoolean())
        assertEquals("CONFLICT", kinds(out)[".syncignore"])
        assertFalse(kinds(out).containsKey("after.txt"), "ordinary content waits for the control-file decision")
    }

    @Test
    fun `manifest snapshots are stable while pages are read and changes feed reports later revisions`() {
        val a = setup()
        sync(a, (1..7).associate { "f$it.txt" to Local.File(bytes("v$it")) })
        val firstPage = a.user.get("/api/spaces/${a.spaceId}/manifest", "limit" to "3").expect(200).body()
        val snapshot = firstPage["revision"].asLong()
        assertEquals(3, firstPage["entries"].size())
        // Something changes between pages.
        sync(a, mapOf("late.txt" to Local.File(bytes("late"))) + (1..7).associate { "f$it.txt" to Local.File(bytes("v$it")) }, (1..7).associate { "f$it.txt" to base(bytes("v$it")) })
        val seen = HashSet<String>()
        firstPage["entries"].forEach { seen.add(it.path()) }
        var after: String? = firstPage["nextAfter"].asText()
        while (after != null) {
            val page = a.user.get("/api/spaces/${a.spaceId}/manifest", "limit" to "3", "after" to after, "revision" to snapshot.toString()).expect(200).body()
            page["entries"].forEach { seen.add(it.path()) }
            after = page["nextAfter"].takeUnless { it.isNull }?.asText()
        }
        assertEquals((1..7).map { "f$it.txt" }.toSet(), seen, "late.txt is not part of the earlier snapshot")
        val changes = a.user.get("/api/spaces/${a.spaceId}/changes", "after" to snapshot.toString()).expect(200).body()
        assertEquals(listOf("late.txt"), changes["changes"].list().map { it.path() })
    }

    @Test
    fun `acknowledgements are recorded but must match the plan and committed state`() {
        val a = setup()
        val data = bytes("ack me")
        val out = sync(a, mapOf("n.txt" to Local.File(data)))
        val planId = out.plan["id"].asText()
        a.user.post("/api/plans/$planId/acknowledgements", mapOf("entries" to listOf(mapOf("path" to "n.txt", "outcome" to "APPLIED", "sha256" to sha256(bytes("something else")))))).expect(409)
        a.user.post("/api/plans/$planId/acknowledgements", mapOf("entries" to listOf(mapOf("path" to "nope.txt", "outcome" to "APPLIED")))).expect(422)
        val ok = a.user.post("/api/plans/$planId/acknowledgements", mapOf("entries" to listOf(mapOf("path" to "n.txt", "outcome" to "APPLIED", "sha256" to sha256(data))))).expect(200).body()
        assertEquals(1, ok["applied"].asInt())
        // Repeating is idempotent.
        assertEquals(1, a.user.post("/api/plans/$planId/acknowledgements", mapOf("entries" to listOf(mapOf("path" to "n.txt", "outcome" to "APPLIED", "sha256" to sha256(data))))).expect(200).body()["applied"].asInt())
    }

    @Test
    fun `a changed epoch forces recovery reconciliation`() {
        val a = setup()
        sync(a, mapOf("e.txt" to Local.File(bytes("e"))))
        val revision = a.user.get("/api/spaces/${a.spaceId}").body()["revision"].asLong()
        // A client that remembers a revision the server does not have (rolled back) is told to recover.
        val ahead = a.user.get("/api/spaces/${a.spaceId}/changes", "after" to (revision + 50).toString()).expect(409)
        assertEquals("revision_ahead", ahead.code())

        val old = epochs.current()
        val rotated = epochs.rotate("test")
        assertNotEquals(old, rotated)
        createPlan(a, emptyMap()).expect(409).also { assertEquals("epoch_mismatch", it.code()); assertTrue(it.body()["details"]["recovery"].asBoolean()) }
        a.user.get("/api/spaces/${a.spaceId}/manifest", "epoch" to old).expect(409)
        // A fresh enrollment learns the new epoch and can proceed.
        val fresh = enroll(a.user, a.spaceId, a.deviceId)
        assertEquals(rotated, fresh.epoch)
        createPlan(fresh, emptyMap()).expect(201)
    }

    @Test
    fun `revoked devices and disconnected bindings stop working`() {
        val a = setup()
        val b = secondDevice(a)
        b.user.delete("/api/devices/${b.deviceId}").expect(204)
        createPlan(b, emptyMap()).expect(409)
        a.user.delete("/api/bindings/${a.bindingId}").expect(204)
        createPlan(a, emptyMap()).expect(409)
    }
}
