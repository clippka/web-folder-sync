package app.foldersync

import app.foldersync.reconciliation.Resolution
import app.foldersync.reconciliation.ResolveRequest
import app.foldersync.revisions.CommitRequest
import app.foldersync.support.Ctx
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import app.foldersync.support.TestUser
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Process restart: the application context is discarded between the two tests and rebuilt on the same data directory,
 * which is what recreating the container on the same volume does.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RestartTest : SyncTest() {
    companion object {
        lateinit var username: String
        lateinit var password: String
        lateinit var spaceId: String
        lateinit var bindingId: String
        lateinit var deviceId: String
        lateinit var epoch: String
        lateinit var conflictId: String
        lateinit var openUploadId: String
        lateinit var pendingPlanId: String
        lateinit var committedBlob: String
        lateinit var candidateBlob: String
        val half = ByteArray(2048) { (it % 13).toByte() }

        @JvmStatic
        @DynamicPropertySource
        fun more(r: DynamicPropertyRegistry) {
        }
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun `before the restart`() {
        val a = setup()
        username = a.user.username; password = a.user.password
        spaceId = a.spaceId; bindingId = a.bindingId; deviceId = a.deviceId; epoch = a.epoch

        sync(a, mapOf("kept.txt" to Local.File("survives restart".toByteArray()), "dir" to Local.Dir))
        committedBlob = manifest(a)["kept.txt"]!!["blobId"].asText()

        val other = secondDevice(a)
        val conflicted = sync(other, mapOf("kept.txt" to Local.File("local candidate".toByteArray())))
        conflictId = conflicted.commitBody!!["conflicts"][0]["id"].asText()
        candidateBlob = a.user.get("/api/conflicts/$conflictId").body()["local"]["blobId"].asText()

        // An upload with one of two chunks stored, and a plan that is ready but not committed.
        val begin = a.user.post("/api/spaces/$spaceId/uploads", mapOf("size" to 2048 + 100, "sha256" to sha256(half + ByteArray(100))), "restart-up").expect(201).body()
        openUploadId = begin["uploadId"].asText()
        a.user.put("/api/uploads/$openUploadId/chunks/0", half.copyOfRange(0, 1024)).expect(200)
        pendingPlanId = createPlan(a, mapOf("new.txt" to Local.File("pending".toByteArray()))).expect(201).body()["id"].asText()
    }

    @Test
    @Order(2)
    fun `after the restart everything is still there and usable`() {
        val user = TestUser("", username, password, login(username, password))
        val ctx = Ctx(user, spaceId, deviceId, bindingId, epoch)

        assertEquals(epoch, user.get("/api/spaces/$spaceId").expect(200).body()["epoch"].asText(), "same lineage: clients keep their baselines")
        assertEquals("survives restart", String(download(ctx, committedBlob).expect(200).andReturn().response.contentAsByteArray))
        assertTrue(manifest(ctx).keys.containsAll(setOf("kept.txt", "dir")))

        val c = user.get("/api/conflicts/$conflictId").expect(200).body()
        assertEquals("UNRESOLVED", c["state"].asText())
        assertEquals("local candidate", String(download(ctx, candidateBlob).expect(200).andReturn().response.contentAsByteArray))

        // The interrupted upload resumes from the stored chunk; the staged plan commits.
        assertEquals(listOf(0), user.get("/api/uploads/$openUploadId").expect(200).body()["receivedChunks"].list().map { it.asInt() })
        user.put("/api/uploads/$openUploadId/chunks/1", half.copyOfRange(1024, 2048)).expect(200)
        user.put("/api/uploads/$openUploadId/chunks/2", ByteArray(100)).expect(200)
        user.post("/api/uploads/$openUploadId/complete").expect(200)

        val up = upload(user, spaceId, "pending".toByteArray())
        user.post("/api/plans/$pendingPlanId/commit", CommitRequest(epoch, mapOf("new.txt" to up)), "after-restart").expect(200)
        assertTrue(manifest(ctx).containsKey("new.txt"))

        // The conflict can still be decided.
        user.post("/api/conflicts/$conflictId/resolve", ResolveRequest(Resolution.KEEP_SERVER, c["serverEntryVersion"].asLong()), "after-restart-r").expect(200)
    }
}
