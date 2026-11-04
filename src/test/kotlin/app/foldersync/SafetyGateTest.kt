package app.foldersync

import app.foldersync.revisions.CommitRequest
import app.foldersync.revisions.EpochService
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step 8 safety-gate evidence, run with the gate open: situations in which a deletion must never be proposed, applied
 * twice, or authorized by an approval that was not shown for the current state.
 */
class SafetyGateTest : SyncTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun gateOpen(r: DynamicPropertyRegistry) {
            r.add("foldersync.deletion-enabled") { "true" }
        }
    }

    @Autowired lateinit var epochs: EpochService

    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun `no deletion is proposed after first enrollment, an incomplete scan or a missing baseline, and nothing can be approved`() {
        val a = setup()
        val v = bytes("data")
        sync(a, mapOf("a.txt" to Local.File(v), "b.txt" to Local.File(v)))

        // A newly joined machine with an empty folder: no baselines, so absence means nothing.
        val joiner = secondDevice(a)
        val joined = sync(joiner, emptyMap(), commit = false)
        assertTrue(joined.ops.none { it["kind"].asText().contains("DELETION") })

        // A scan that could not read everything must not delete, even for paths that have baselines.
        val partial = sync(a, emptyMap(), mapOf("a.txt" to base(v), "b.txt" to base(v)), scanComplete = false, commit = false)
        assertTrue(partial.ops.none { it["kind"].asText().contains("DELETION") })
        val unreadable = sync(a, mapOf("a.txt" to Local.Unreadable), mapOf("a.txt" to base(v), "b.txt" to base(v)), commit = false)
        assertFalse(kinds(unreadable)["a.txt"].orEmpty().contains("DELETION"))

        // Approving a path no operation proposes for deletion is refused outright and changes nothing.
        a.user.post("/api/plans/${partial.plan["id"].asText()}/commit", CommitRequest(a.epoch, approvedServerDeletions = listOf("a.txt")), "fake-1").expect(422)
        assertFalse(manifest(a)["a.txt"]!!["deleted"].asBoolean())
        assertFalse(manifest(a)["b.txt"]!!["deleted"].asBoolean())
    }

    @Test
    fun `an approved deletion replays with its key and produces a single tombstone revision`() {
        val a = setup()
        val v = bytes("data")
        sync(a, mapOf("gone.txt" to Local.File(v)))
        val plan = sync(a, emptyMap(), mapOf("gone.txt" to base(v)), commit = false)
        val id = plan.plan["id"].asText()
        val req = CommitRequest(a.epoch, approvedServerDeletions = listOf("gone.txt"))

        val first = a.user.post("/api/plans/$id/commit", req, "del-key").expect(200).body()
        val replay = a.user.post("/api/plans/$id/commit", req, "del-key").expect(200).body()   // lost response, same key
        assertEquals(first["revision"], replay["revision"])
        assertEquals(2L, manifest(a)["gone.txt"]!!["version"].asLong(), "create plus exactly one delete")

        // A different key on the same plan is a second commit and is refused: an approval is spent with its plan.
        assertEquals("plan_already_committed", a.user.post("/api/plans/$id/commit", req, "other-key").expect(409).code())
    }

    @Test
    fun `an approval made before an epoch change cannot delete anything afterwards`() {
        val a = setup()
        val v = bytes("data")
        sync(a, mapOf("keep.txt" to Local.File(v)))
        val plan = sync(a, emptyMap(), mapOf("keep.txt" to base(v)), commit = false)
        epochs.rotate("restore drill")           // a restored backup: the old lineage no longer counts
        val r = a.user.post("/api/plans/${plan.plan["id"].asText()}/commit", CommitRequest(a.epoch, approvedServerDeletions = listOf("keep.txt")), "restored-1")
        r.expect(409)
        assertEquals("epoch_mismatch", r.code())
        assertFalse(manifest(a)["keep.txt"]!!["deleted"].asBoolean())
    }

    @Test
    fun `a stale local deletion proposal cannot be used to hide a newer edit on another device`() {
        val a = setup()
        val b = secondDevice(a)
        val v = bytes("original")
        sync(a, mapOf("f.txt" to Local.File(v)))
        sync(b, mapOf("f.txt" to Local.File(v)), mapOf("f.txt" to base(v)))
        sync(a, emptyMap(), mapOf("f.txt" to base(v)), approveServerDeletions = true)    // A deletes

        val plan = sync(b, mapOf("f.txt" to Local.File(v)), mapOf("f.txt" to base(v)), commit = false)
        assertEquals("PROPOSE_LOCAL_DELETION", kinds(plan)["f.txt"])
        // Meanwhile somebody recreates the file on the server with new content.
        sync(a, mapOf("f.txt" to Local.File(bytes("recreated"))))
        // B's approval for the old proposal still commits only an audit record; the entry is never touched by it.
        a.user.post("/api/plans/${plan.plan["id"].asText()}/commit", CommitRequest(b.epoch, approvedLocalDeletions = listOf("f.txt")), "stale-local-1")
        assertEquals(sha256(bytes("recreated")), manifest(a)["f.txt"]!!["sha256"].asText())
        assertFalse(manifest(a)["f.txt"]!!["deleted"].asBoolean())
    }
}
