package app.foldersync

import app.foldersync.blobs.MaintenanceService
import app.foldersync.blobs.BlobStore
import app.foldersync.reconciliation.Resolution
import app.foldersync.reconciliation.ResolveRequest
import app.foldersync.reconciliation.StateDto
import app.foldersync.revisions.CommitRequest
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs with the deletion gate open so the server-side deletion machinery (tombstones, approvals, release) is
 * exercised (this is also the default since the Step 8 safety gate passed).
 */
class DeletionAndTombstoneTest : SyncTest() {
    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun more(r: DynamicPropertyRegistry) {
            r.add("foldersync.deletion-enabled") { "true" }
        }
    }

    @Autowired lateinit var maintenance: MaintenanceService
    @Autowired lateinit var store: BlobStore

    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun `an approved deletion leaves a compact tombstone and releases the content`() {
        val a = setup()
        val v = bytes("to be deleted")
        sync(a, mapOf("gone.txt" to Local.File(v), "keep.txt" to Local.File(bytes("keep"))))
        val blob = manifest(a)["gone.txt"]!!["blobId"].asText()

        val plan = sync(a, mapOf("keep.txt" to Local.File(bytes("keep"))), mapOf("gone.txt" to base(v), "keep.txt" to base(bytes("keep"))), approveServerDeletions = true)
        assertEquals("PROPOSE_SERVER_DELETION", kinds(plan)["gone.txt"])
        assertEquals(listOf("gone.txt"), plan.commitBody!!["applied"].list().map { it.path() })

        val tomb = manifest(a)["gone.txt"]!!
        assertTrue(tomb["deleted"].asBoolean())
        assertTrue(tomb["sha256"].isNull && tomb["blobId"].isNull, "tombstone holds no content reference")
        val changes = a.user.get("/api/spaces/${a.spaceId}/changes").body()["changes"]
        assertTrue(changes.any { it.path() == "gone.txt" && it["kind"].asText() == "DELETE" })

        assertEquals("content_unavailable", download(a, blob).expect(410).code())
        maintenance.runOnce()
        assertFalse(store.exists(blob))
    }

    @Test
    fun `a long offline device with an old baseline gets a deletion proposal and not a resurrection`() {
        val a = setup()
        val stale = secondDevice(a)
        val v = bytes("shared")
        sync(a, mapOf("f.txt" to Local.File(v)))
        // The stale device synced once (has the baseline) and then went offline while A deleted the file.
        sync(a, emptyMap(), mapOf("f.txt" to base(v)), approveServerDeletions = true)

        val out = sync(stale, mapOf("f.txt" to Local.File(v)), mapOf("f.txt" to base(v)))
        assertEquals("PROPOSE_LOCAL_DELETION", kinds(out)["f.txt"])
        assertTrue(manifest(a)["f.txt"]!!["deleted"].asBoolean(), "the tombstone was not overwritten")
        assertTrue(out.commitBody!!["applied"].isEmpty)
    }

    @Test
    fun `delete versus edit is a conflict and keeping the modified file recreates it`() {
        val a = setup()
        val stale = secondDevice(a)
        val v = bytes("original")
        sync(a, mapOf("f.txt" to Local.File(v)))
        sync(a, emptyMap(), mapOf("f.txt" to base(v)), approveServerDeletions = true)

        val edited = Local.File(bytes("edited while offline"))
        val out = sync(stale, mapOf("f.txt" to edited), mapOf("f.txt" to base(v)))
        assertEquals("CONFLICT", kinds(out)["f.txt"])
        val c = stale.user.get("/api/conflicts/${out.commitBody!!["conflicts"][0]["id"].asText()}").body()
        assertEquals("DELETE_VS_EDIT", c["kind"].asText())
        assertEquals("ABSENT", c["server"]["type"].asText())

        stale.user.post("/api/conflicts/${c["id"].asText()}/resolve", ResolveRequest(Resolution.KEEP_LOCAL, c["serverEntryVersion"].asLong()), "dv-1").expect(200)
        val e = manifest(a)["f.txt"]!!
        assertFalse(e["deleted"].asBoolean())
        assertEquals(sha256(bytes("edited while offline")), e["sha256"].asText())
        assertEquals(3L, e["version"].asLong(), "create, delete tombstone, recreate")
    }

    @Test
    fun `confirming a conflicted deletion removes the file`() {
        val a = setup()
        val other = secondDevice(a)
        val v = bytes("v1")
        sync(a, mapOf("f.txt" to Local.File(v)))
        // Device A deletes locally while the server copy is modified by the other device: delete versus edit.
        sync(other, mapOf("f.txt" to Local.File(bytes("v2"))), mapOf("f.txt" to base(v)))
        val out = sync(a, emptyMap(), mapOf("f.txt" to base(v)))
        assertEquals("CONFLICT", kinds(out)["f.txt"])
        val c = a.user.get("/api/conflicts/${out.commitBody!!["conflicts"][0]["id"].asText()}").body()
        assertEquals("ABSENT", c["local"]["type"].asText())
        a.user.post("/api/conflicts/${c["id"].asText()}/resolve", ResolveRequest(Resolution.KEEP_LOCAL, c["serverEntryVersion"].asLong()), "cd-1").expect(200)
        assertTrue(manifest(a)["f.txt"]!!["deleted"].asBoolean())
    }

    @Test
    fun `confirming the server deletion of a locally edited file keeps the tombstone and is offered as keep server`() {
        val a = setup()
        val stale = secondDevice(a)
        val v = bytes("original")
        sync(a, mapOf("f.txt" to Local.File(v)))
        sync(a, emptyMap(), mapOf("f.txt" to base(v)), approveServerDeletions = true)
        val out = sync(stale, mapOf("f.txt" to Local.File(bytes("edited offline"))), mapOf("f.txt" to base(v)))
        val c = stale.user.get("/api/conflicts/${out.commitBody!!["conflicts"][0]["id"].asText()}").body()
        assertEquals("DELETE_VS_EDIT", c["kind"].asText())
        assertEquals(listOf("KEEP_LOCAL", "KEEP_SERVER"), c["options"].list().map { it.asText() })
        // Confirm deletion: the server tombstone stays, the edited local candidate is released.
        stale.user.post("/api/conflicts/${c["id"].asText()}/resolve", ResolveRequest(Resolution.KEEP_SERVER, c["serverEntryVersion"].asLong()), "cs-1").expect(200)
        assertTrue(manifest(a)["f.txt"]!!["deleted"].asBoolean())
        assertEquals("RESOLVED", stale.user.get("/api/conflicts/${c["id"].asText()}").body()["state"].asText())
    }

    @Test
    fun `directories are removed only with their own approved deletion and children first`() {
        val a = setup()
        val v = bytes("x")
        sync(a, mapOf("d" to Local.Dir, "d/x.txt" to Local.File(v)))

        // Removing the last file leaves the directory in place.
        val leaf = sync(a, mapOf("d" to Local.Dir), mapOf("d" to StateDto("DIRECTORY"), "d/x.txt" to base(v)), approveServerDeletions = true)
        assertEquals(listOf("d/x.txt"), leaf.commitBody!!["applied"].list().map { it.path() })
        assertFalse(manifest(a)["d"]!!["deleted"].asBoolean())

        // Re-create the file, then delete both: child first, parent second, in one approved batch.
        sync(a, mapOf("d" to Local.Dir, "d/x.txt" to Local.File(v)), mapOf("d" to StateDto("DIRECTORY")))
        val both = sync(a, emptyMap(), mapOf("d" to StateDto("DIRECTORY"), "d/x.txt" to base(v)), approveServerDeletions = true)
        assertEquals(listOf("d/x.txt", "d"), both.commitBody!!["applied"].list().map { it.path() })
        assertTrue(manifest(a)["d"]!!["deleted"].asBoolean())
    }

    @Test
    fun `a directory deletion is refused while the server holds live children`() {
        val a = setup()
        val b = secondDevice(a)
        val v = bytes("x")
        sync(a, mapOf("d" to Local.Dir))
        // A plans to delete d (empty at plan time) ...
        val plan = sync(a, emptyMap(), mapOf("d" to StateDto("DIRECTORY")), commit = false)
        // ... but B adds a child before the commit.
        sync(b, mapOf("d" to Local.Dir, "d/new.txt" to Local.File(v)), mapOf("d" to StateDto("DIRECTORY")))
        val r = a.user.post("/api/plans/${plan.plan["id"].asText()}/commit", CommitRequest(a.epoch, approvedServerDeletions = listOf("d")), "dirdel-1")
        r.expect(409)
        assertEquals("stale_plan", r.code())
        assertFalse(manifest(a)["d"]!!["deleted"].asBoolean())
        assertTrue(manifest(a).containsKey("d/new.txt"))
    }

    @Test
    fun `approvals must name displayed deletion operations of this plan and cannot outlive their plan state`() {
        val a = setup()
        val v = bytes("data")
        sync(a, mapOf("f.txt" to Local.File(v), "g.txt" to Local.File(v)))
        val plan = sync(a, mapOf("g.txt" to Local.File(v)), mapOf("f.txt" to base(v), "g.txt" to base(v)), commit = false)
        val id = plan.plan["id"].asText()
        // Not a deletion operation of this plan.
        a.user.post("/api/plans/$id/commit", CommitRequest(a.epoch, approvedServerDeletions = listOf("g.txt")), "ap-1").expect(422)
        a.user.post("/api/plans/$id/commit", CommitRequest(a.epoch, approvedServerDeletions = listOf("unknown.txt")), "ap-2").expect(422)
        // The file changes after the plan was displayed: the approval no longer applies.
        sync(secondDevice(a), mapOf("f.txt" to Local.File(bytes("changed meanwhile")), "g.txt" to Local.File(v)), mapOf("f.txt" to base(v), "g.txt" to base(v)))
        a.user.post("/api/plans/$id/commit", CommitRequest(a.epoch, approvedServerDeletions = listOf("f.txt")), "ap-3").expect(409)
        val e = assertNotNull(manifest(a)["f.txt"])
        assertFalse(e["deleted"].asBoolean())
        assertEquals(sha256(bytes("changed meanwhile")), e["sha256"].asText())
    }

    @Test
    fun `recreating a deleted path continues the version sequence`() {
        val a = setup()
        val v = bytes("one")
        sync(a, mapOf("r.txt" to Local.File(v)))
        sync(a, emptyMap(), mapOf("r.txt" to base(v)), approveServerDeletions = true)
        sync(a, mapOf("r.txt" to Local.File(bytes("two"))))        // no baseline: a plain addition over the tombstone
        assertEquals(3L, manifest(a)["r.txt"]!!["version"].asLong())
        assertFalse(manifest(a)["r.txt"]!!["deleted"].asBoolean())
    }
}
