package app.foldersync

import app.foldersync.identity.Role
import app.foldersync.revisions.CommitRequest
import app.foldersync.reconciliation.ResolveRequest
import app.foldersync.reconciliation.Resolution
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import app.foldersync.support.TestUser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exit gate: a second authenticated user, an administrator, or a user of a different device cannot reach any resource
 * of another user even with every identifier known. Everything answers 404, never 403, so existence is not revealed.
 */
class OwnershipIsolationTest : SyncTest() {

    /** Built on first use: Spring injects the test's fields only after construction. */
    private inner class Fixture {
        val a = newUser()
        val owner = setup(a)
        val committedBlob: String
        val planId: String
        val uploadId: String
        val conflictId: String
        val leaseId: String

        init {
            val first = sync(owner, mapOf("a.txt" to Local.File("version one".toByteArray())))
            assertEquals(200, first.commit!!.andReturn().response.status)
            committedBlob = manifest(owner)["a.txt"]!!["blobId"].asText()

            // A second device with a different local file at the same path -> conflict.
            val conflicted = sync(secondDevice(owner), mapOf("a.txt" to Local.File("different".toByteArray())))
            conflictId = conflicted.commitBody!!["conflicts"][0]["id"].asText()

            // A plan that contains a download (so it carries a lease), left uncommitted.
            val pending = sync(secondDevice(owner), emptyMap(), commit = false)
            planId = pending.plan["id"].asText()
            leaseId = pending.plan["leaseId"].asText()

            uploadId = owner.user.post("/api/spaces/${owner.spaceId}/uploads", mapOf("size" to 4, "sha256" to sha256("data".toByteArray())), "k1").expect(201).body()["uploadId"].asText()
        }
    }

    private val fx by lazy { Fixture() }
    private val a get() = fx.a
    private val owner get() = fx.owner
    private val committedBlob get() = fx.committedBlob
    private val planId get() = fx.planId
    private val uploadId get() = fx.uploadId
    private val conflictId get() = fx.conflictId
    private val leaseId get() = fx.leaseId

    private fun intruders(): List<TestUser> = listOf(
        newUser(),
        TestUser("", "admin", "", adminSession()),
        newUser(Role.ADMIN),
    )

    @Test
    fun `other users and administrators cannot read or change any resource by id`() {
        val space = owner.spaceId
        for (x in intruders()) {
            x.get("/api/spaces/$space").expect(404)
            x.get("/api/spaces/$space/manifest").expect(404)
            x.get("/api/spaces/$space/changes").expect(404)
            x.get("/api/spaces/$space/bindings").expect(404)
            x.get("/api/spaces/$space/conflicts").expect(404)
            x.get("/api/spaces/$space/events").expect(404)
            x.get("/api/conflicts/$conflictId").expect(404)
            x.post("/api/conflicts/$conflictId/resolve", ResolveRequest(Resolution.KEEP_LOCAL, 1), "ik").expect(404)
            x.get("/api/plans/$planId").expect(404)
            x.get("/api/plans/$planId/operations").expect(404)
            x.post("/api/plans/$planId/commit", CommitRequest(owner.epoch), "ik").expect(404)
            x.post("/api/plans/$planId/acknowledgements", mapOf("entries" to emptyList<Any>())).expect(404)
            x.post("/api/plans/$planId/entries", mapOf("entries" to emptyList<Any>())).expect(404)
            x.get("/api/uploads/$uploadId").expect(404)
            x.put("/api/uploads/$uploadId/chunks/0", "data".toByteArray()).expect(404)
            x.post("/api/uploads/$uploadId/complete").expect(404)
            x.delete("/api/uploads/$uploadId").expect(404)
            x.post("/api/leases/$leaseId/renew").expect(404)
            x.delete("/api/leases/$leaseId").expect(404)
            x.get("/api/spaces/$space/blobs/$committedBlob/content").expect(404)
            x.post("/api/spaces/$space/uploads", mapOf("size" to 1, "sha256" to sha256("x".toByteArray())), "ik").expect(404)
            x.delete("/api/devices/${owner.deviceId}").expect(404)
            x.delete("/api/bindings/${owner.bindingId}").expect(404)
        }
    }

    @Test
    fun `lists never include another users resources`() {
        val x = newUser()
        assertEquals(0, x.get("/api/spaces").expect(200).body().size())
        assertEquals(0, x.get("/api/devices").expect(200).body().size())
        assertTrue(a.get("/api/spaces").expect(200).body().size() >= 1)
    }

    @Test
    fun `a binding cannot join another users device and space`() {
        val x = newUser()
        val xSpace = x.post("/api/spaces", mapOf("name" to "mine")).expect(201).body()["id"].asText()
        // x tries to bind the owner's device to x's space, and its own device to the owner's space.
        x.post("/api/spaces/$xSpace/bindings", mapOf("deviceId" to owner.deviceId)).expect(404)
        val xDevice = x.post("/api/devices", mapOf("label" to "x")).expect(201).body()["id"].asText()
        x.post("/api/spaces/${owner.spaceId}/bindings", mapOf("deviceId" to xDevice)).expect(404)
    }

    @Test
    fun `a plan cannot be created through another users binding or on another users space`() {
        val x = newUser()
        val xCtx = setup(x)
        // x's binding on the owner's space, and the owner's binding on x's space.
        x.post("/api/spaces/${owner.spaceId}/plans", mapOf("bindingId" to xCtx.bindingId, "epoch" to owner.epoch, "policyRevision" to 0, "scanComplete" to true)).expect(404)
        x.post("/api/spaces/${xCtx.spaceId}/plans", mapOf("bindingId" to owner.bindingId, "epoch" to owner.epoch, "policyRevision" to 0, "scanComplete" to true)).expect(404)
    }

    @Test
    fun `a binding of one space cannot be used on another space of the same user`() {
        val second = owner.user.post("/api/spaces", mapOf("name" to "other-" + System.nanoTime())).expect(201).body()["id"].asText()
        owner.user.post("/api/spaces/$second/plans", mapOf("bindingId" to owner.bindingId, "epoch" to owner.epoch, "policyRevision" to 0, "scanComplete" to true)).expect(404)
    }

    @Test
    fun `uploads from another space cannot be committed into a plan`() {
        // An upload staged in space two must not be consumable by a plan in space one.
        val twoSpace = owner.user.post("/api/spaces", mapOf("name" to "two-" + System.nanoTime())).expect(201).body()["id"].asText()
        val up = upload(owner.user, twoSpace, "other space bytes".toByteArray())
        val c = setup(newUser())
        val plan = createPlan(c, mapOf("b.txt" to Local.File("other space bytes".toByteArray()))).expect(201).body()
        // The upload belongs to a different owner entirely: reported as unknown, not as forbidden.
        c.user.post("/api/plans/${plan["id"].asText()}/commit", CommitRequest(c.epoch, mapOf("b.txt" to up)), "ik-cross").expect(422)
    }

    @Test
    fun `unauthenticated requests never reach resources`() {
        mvcGetStatus("/api/spaces/${owner.spaceId}/blobs/$committedBlob/content", 401)
        mvcGetStatus("/api/plans/$planId", 401)
    }

    private fun mvcGetStatus(url: String, expected: Int) {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().`is`(expected))
    }

    @Test
    fun `the owner can still use everything`() {
        owner.user.get("/api/spaces/${owner.spaceId}").expect(200)
        owner.user.get("/api/conflicts/$conflictId").expect(200)
        download(owner, committedBlob).expect(200)
        owner.user.get("/api/plans/$planId").expect(200)
    }
}
