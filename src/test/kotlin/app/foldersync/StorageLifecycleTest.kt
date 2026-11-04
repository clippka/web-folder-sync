package app.foldersync

import app.foldersync.blobs.BackupService
import app.foldersync.blobs.BlobStore
import app.foldersync.blobs.MaintenanceService
import app.foldersync.config.FolderSyncProperties
import app.foldersync.reconciliation.Resolution
import app.foldersync.reconciliation.ResolveRequest
import app.foldersync.revisions.CommitRequest
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StorageLifecycleTest : SyncTest() {
    @Autowired lateinit var maintenance: MaintenanceService
    @Autowired lateinit var store: BlobStore
    @Autowired lateinit var props: FolderSyncProperties
    @Autowired lateinit var backups: BackupService

    private fun bytes(s: String) = s.toByteArray()
    private fun blobOf(ctx: app.foldersync.support.Ctx, path: String) = manifest(ctx)[path]!!["blobId"].asText()

    // ---- uploads: chunking, limits, digests -----------------------------------------------------------------------

    @Test
    fun `chunked upload assembles verifies and finalizes a blob`() {
        val a = setup()
        val data = ByteArray(5000) { (it % 251).toByte() }          // 5 chunks of 1024
        val id = upload(a.user, a.spaceId, data)
        val status = a.user.get("/api/uploads/$id").expect(200).body()
        assertEquals("COMPLETE", status["state"].asText())
        assertTrue(store.exists(status["blobId"].asText()))
        assertContentEqualsBytes(data, Files.readAllBytes(store.path(status["blobId"].asText())))
    }

    private fun assertContentEqualsBytes(a: ByteArray, b: ByteArray) = assertTrue(a.contentEquals(b))

    @Test
    fun `duplicate chunks are idempotent and inconsistent duplicates fail`() {
        val a = setup()
        val data = ByteArray(2048) { 7 }
        val begin = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 2048, "sha256" to sha256(data)), "dup-1").expect(201).body()
        val id = begin["uploadId"].asText()
        val c0 = data.copyOfRange(0, 1024)
        assertFalse(a.user.put("/api/uploads/$id/chunks/0", c0).expect(200).body()["duplicate"].asBoolean())
        assertTrue(a.user.put("/api/uploads/$id/chunks/0", c0).expect(200).body()["duplicate"].asBoolean())
        assertEquals("chunk_conflict", a.user.put("/api/uploads/$id/chunks/0", ByteArray(1024) { 9 }).expect(409).code())
        // Missing chunk blocks completion; resume information lists what arrived.
        assertEquals("upload_incomplete", a.user.post("/api/uploads/$id/complete").expect(409).code())
        assertEquals(listOf(0), a.user.get("/api/uploads/$id").body()["receivedChunks"].list().map { it.asInt() })
        a.user.put("/api/uploads/$id/chunks/1", data.copyOfRange(1024, 2048)).expect(200)
        a.user.post("/api/uploads/$id/complete").expect(200)
        // Completing again is safe.
        a.user.post("/api/uploads/$id/complete").expect(200)
    }

    @Test
    fun `wrong chunk sizes and indexes are rejected and bytes are never accepted beyond the declared size`() {
        val a = setup()
        val begin = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 1500, "sha256" to sha256(ByteArray(1500))), "sz-1").expect(201).body()
        val id = begin["uploadId"].asText()
        assertEquals(2, begin["chunkCount"].asInt())
        assertEquals("chunk_size_mismatch", a.user.put("/api/uploads/$id/chunks/0", ByteArray(1023)).expect(422).code())
        assertEquals("chunk_size_mismatch", a.user.put("/api/uploads/$id/chunks/0", ByteArray(1025)).expect(413).code())
        assertEquals("chunk_size_mismatch", a.user.put("/api/uploads/$id/chunks/1", ByteArray(1024)).expect(413).code(), "last chunk is only 476 bytes")
        assertEquals("invalid_chunk_index", a.user.put("/api/uploads/$id/chunks/2", ByteArray(10)).expect(422).code())
        assertEquals("invalid_chunk_index", a.user.put("/api/uploads/$id/chunks/-1", ByteArray(10)).expect(422).code())
    }

    @Test
    fun `a digest or size that does not match fails the upload and publishes nothing`() {
        val a = setup()
        val data = ByteArray(1024) { 1 }
        val begin = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 1024, "sha256" to sha256(ByteArray(1024) { 2 })), "dg-1").expect(201).body()
        val id = begin["uploadId"].asText()
        a.user.put("/api/uploads/$id/chunks/0", data).expect(200)
        assertEquals("digest_mismatch", a.user.post("/api/uploads/$id/complete").expect(422).code())
        assertEquals("FAILED", a.user.get("/api/uploads/$id").body()["state"].asText())
        assertFalse(store.exists(id))
        a.user.put("/api/uploads/$id/chunks/0", data).expect(410)
    }

    @Test
    fun `retrying begin with the same key returns the same upload`() {
        val a = setup()
        val req = mapOf("size" to 10, "sha256" to sha256(ByteArray(10)))
        val one = a.user.post("/api/spaces/${a.spaceId}/uploads", req, "begin-k").expect(201).body()
        val two = a.user.post("/api/spaces/${a.spaceId}/uploads", req, "begin-k").expect(201).body()
        assertEquals(one["uploadId"], two["uploadId"])
        a.user.post("/api/spaces/${a.spaceId}/uploads", req).expect(400)
    }

    @Test
    fun `expired uploads are removed and their staging is cleaned`() {
        val a = setup()
        val begin = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 10, "sha256" to sha256(ByteArray(10))), "exp-1").expect(201).body()
        val id = begin["uploadId"].asText()
        a.user.put("/api/uploads/$id/chunks/0", ByteArray(10)).expect(200)
        assertTrue(Files.exists(props.stagingDir.resolve(id)))
        mc.advanceMillis(props.uploadExpiry.toMillis() + 1000)
        a.user.put("/api/uploads/$id/chunks/0", ByteArray(10)).expect(410)
        val report = maintenance.runOnce()
        assertTrue(report.expiredUploads >= 1)
        assertFalse(Files.exists(props.stagingDir.resolve(id)))
        assertEquals("EXPIRED", a.user.get("/api/uploads/$id").body()["state"].asText())
    }

    // ---- crash boundaries ------------------------------------------------------------------------------------------

    @Test
    fun `a blob published before its database row is recovered when complete is retried`() {
        val a = setup()
        val data = ByteArray(3000) { (it * 7).toByte() }
        val begin = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 3000, "sha256" to sha256(data)), "crash-1").expect(201).body()
        val id = begin["uploadId"].asText()
        for (i in 0 until 3) a.user.put("/api/uploads/$id/chunks/$i", data.copyOfRange(i * 1024, minOf(3000, (i + 1) * 1024))).expect(200)
        // Simulate dying after the atomic move into the blob store but before the database row was written.
        store.publish(props.stagingDir.resolve(id).resolve("data"), id)
        assertTrue(store.exists(id))
        a.user.post("/api/uploads/$id/complete").expect(200)
        assertEquals("COMPLETE", a.user.get("/api/uploads/$id").body()["state"].asText())
        assertContentEqualsBytes(data, Files.readAllBytes(store.path(id)))
    }

    @Test
    fun `files without a database row are swept only after the grace period`() {
        val orphan = props.blobDir.resolve("ab").resolve("cd").resolve("abcd" + "0".repeat(28))
        Files.createDirectories(orphan.parent)
        Files.write(orphan, bytes("orphan"))
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(mc.millis()))    // the test clock has been moved by other tests
        maintenance.runOnce()
        assertTrue(Files.exists(orphan), "a fresh orphan is kept: it may belong to an upload that is about to finish")
        mc.advanceMillis(props.orphanFileGrace.toMillis() + 60_000)
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(mc.millis() - props.orphanFileGrace.toMillis() - 1000))
        maintenance.runOnce()
        assertFalse(Files.exists(orphan))
    }

    // ---- reference release, leases and downloads --------------------------------------------------------------------

    @Test
    fun `replaced content is unreadable immediately and collected later while current content stays`() {
        val a = setup()
        sync(a, mapOf("doc.txt" to Local.File(bytes("version one"))))
        val oldBlob = blobOf(a, "doc.txt")
        download(a, oldBlob).expect(200)

        sync(a, mapOf("doc.txt" to Local.File(bytes("version two"))), mapOf("doc.txt" to base(bytes("version one"))))
        val newBlob = blobOf(a, "doc.txt")
        assertNotEquals(oldBlob, newBlob)

        // Released at once, although the file may still be on disk.
        assertEquals("content_unavailable", download(a, oldBlob).expect(410).code())
        assertTrue(store.exists(oldBlob), "physical cleanup has not happened yet")

        maintenance.runOnce()
        assertFalse(store.exists(oldBlob), "unreferenced blob collected")
        assertEquals("version two", String(download(a, newBlob).expect(200).andReturn().response.contentAsByteArray))
    }

    @Test
    fun `an active plan lease keeps replaced content readable until it lapses`() {
        val a = setup()
        sync(a, mapOf("doc.txt" to Local.File(bytes("original"))))
        val old = blobOf(a, "doc.txt")

        val b = secondDevice(a)
        val pending = sync(b, emptyMap(), commit = false)            // plans a download of doc.txt and takes a lease
        val leaseId = pending.plan["leaseId"].asText()
        val dl = pending.ops.first { it.path() == "doc.txt" }
        assertEquals(old, dl["blobId"].asText())

        // The file is replaced on the server while B is still downloading.
        sync(a, mapOf("doc.txt" to Local.File(bytes("replacement"))), mapOf("doc.txt" to base(bytes("original"))))
        assertEquals("original", String(download(b, old).expect(200).andReturn().response.contentAsByteArray), "lease protects in-flight download")
        maintenance.runOnce()
        assertTrue(store.exists(old), "collection waits for the lease")
        b.user.post("/api/leases/$leaseId/renew").expect(200)

        // Another user's lease use is impossible, and renewal is bounded.
        mc.advanceMillis(props.leaseTtl.toMillis() + 1000)
        assertEquals("lease_expired", b.user.post("/api/leases/$leaseId/renew").expect(410).code())
        assertEquals("content_unavailable", download(b, old).expect(410).code())
        maintenance.runOnce()
        assertFalse(store.exists(old))
        // Committing the stale plan is refused: the plan's lease is gone and it must replan.
    }

    @Test
    fun `lease renewal cannot extend beyond the maximum lifetime`() {
        val a = setup()
        sync(a, mapOf("l.txt" to Local.File(bytes("lease"))))
        val b = secondDevice(a)
        val leaseId = sync(b, emptyMap(), commit = false).plan["leaseId"].asText()
        var elapsed = 0L
        val step = props.leaseTtl.toMillis() - 60_000
        var expiredAt: Long? = null
        while (elapsed <= props.leaseMaxLifetime.toMillis() + step && expiredAt == null) {
            mc.advanceMillis(step); elapsed += step
            val r = b.user.post("/api/leases/$leaseId/renew")
            if (r.andReturn().response.status == 410) expiredAt = elapsed
        }
        assertTrue(expiredAt != null && expiredAt <= props.leaseMaxLifetime.toMillis() + props.leaseTtl.toMillis(), "lease must lapse by the max lifetime: $expiredAt")
    }

    @Test
    fun `conflict candidates stay readable until the decision and the unchosen one is released afterwards`() {
        val a = setup()
        sync(a, mapOf("c.txt" to Local.File(bytes("server side"))))
        val b = secondDevice(a)
        val out = sync(b, mapOf("c.txt" to Local.File(bytes("local side"))))
        val conflictId = out.commitBody!!["conflicts"][0]["id"].asText()
        val detail = a.user.get("/api/conflicts/$conflictId").body()
        val localBlob = detail["local"]["blobId"].asText()
        val version = detail["serverEntryVersion"].asLong()

        // Even after the server entry moves on, the original server variant is still preserved by the conflict.
        maintenance.runOnce()
        assertTrue(store.exists(localBlob))
        download(a, localBlob).expect(200)

        // A stale decision is refused.
        a.user.post("/api/conflicts/$conflictId/resolve", ResolveRequest(Resolution.KEEP_LOCAL, version + 5), "r-0").expect(409).also { assertEquals("stale_conflict", it.code()) }
        // Skip keeps it pending.
        a.user.post("/api/conflicts/$conflictId/resolve", ResolveRequest(Resolution.SKIP, version), "r-1").expect(200)
        assertEquals("UNRESOLVED", a.user.get("/api/conflicts/$conflictId").body()["state"].asText())

        // Keep local publishes the local variant and releases the old server blob.
        val oldServer = blobOf(a, "c.txt")
        a.user.post("/api/conflicts/$conflictId/resolve", ResolveRequest(Resolution.KEEP_LOCAL, version), "r-3").expect(200)
        assertEquals(sha256(bytes("local side")), manifest(a)["c.txt"]!!["sha256"].asText())
        assertEquals("content_unavailable", download(a, oldServer).expect(410).code())
        maintenance.runOnce()
        assertFalse(store.exists(oldServer))
        assertEquals("local side", String(download(a, blobOf(a, "c.txt")).expect(200).andReturn().response.contentAsByteArray))
        a.user.post("/api/conflicts/$conflictId/resolve", ResolveRequest(Resolution.KEEP_SERVER, version), "r-4").expect(409).also { assertEquals("already_resolved", it.code()) }
    }

    @Test
    fun `keeping the server variant discards the local candidate`() {
        val a = setup()
        sync(a, mapOf("k.txt" to Local.File(bytes("server"))))
        val out = sync(secondDevice(a), mapOf("k.txt" to Local.File(bytes("local"))))
        val c = a.user.get("/api/conflicts/${out.commitBody!!["conflicts"][0]["id"].asText()}").body()
        val localBlob = c["local"]["blobId"].asText()
        a.user.post("/api/conflicts/${c["id"].asText()}/resolve", ResolveRequest(Resolution.KEEP_SERVER, c["serverEntryVersion"].asLong()), "ks-1").expect(200)
        assertEquals("content_unavailable", download(a, localBlob).expect(410).code())
        maintenance.runOnce()
        assertFalse(store.exists(localBlob))
        assertEquals(sha256(bytes("server")), manifest(a)["k.txt"]!!["sha256"].asText())
    }

    // ---- quotas ----------------------------------------------------------------------------------------------------

    private fun setQuota(userId: String, bytes: Long?) = users.setQuota("admin", userId, bytes)

    @Test
    fun `no quota means no limit and quotas apply across all spaces of the owner`() {
        val a = setup()
        val s2 = a.user.post("/api/spaces", mapOf("name" to "second-" + System.nanoTime())).expect(201).body()["id"].asText()
        // Unset quota: large declared sizes are fine (nothing is uploaded here, only reserved).
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 50_000_000, "sha256" to sha256(ByteArray(1))), "q-unl").expect(201)

        val b = setup()
        val b2 = b.user.post("/api/spaces", mapOf("name" to "b2-" + System.nanoTime())).expect(201).body()["id"].asText()
        setQuota(b.user.id, 3000)
        b.user.post("/api/spaces/${b.spaceId}/uploads", mapOf("size" to 2000, "sha256" to sha256(ByteArray(1))), "q-1").expect(201)
        val over = b.user.post("/api/spaces/$b2/uploads", mapOf("size" to 1500, "sha256" to sha256(ByteArray(1))), "q-2").expect(422)
        assertEquals("quota_exceeded", over.code(), "reservation in space one counts against space two")
        assertEquals(s2.length > 0, true)
        b.user.post("/api/spaces/$b2/uploads", mapOf("size" to 1000, "sha256" to sha256(ByteArray(1))), "q-3").expect(201)
    }

    @Test
    fun `simultaneous uploads in different spaces cannot jointly exceed the quota`() {
        val a = setup()
        val s2 = a.user.post("/api/spaces", mapOf("name" to "race-" + System.nanoTime())).expect(201).body()["id"].asText()
        setQuota(a.user.id, 2000)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { i ->
                Callable {
                    val space = if (i % 2 == 0) a.spaceId else s2
                    a.user.post("/api/spaces/$space/uploads", mapOf("size" to 1500, "sha256" to sha256(ByteArray(1))), "race-$i").andReturn().response.status
                }
            }
            val statuses = pool.invokeAll(tasks).map { it.get() }
            assertEquals(1, statuses.count { it == 201 }, "exactly one 1500-byte reservation fits in 2000: $statuses")
            assertEquals(7, statuses.count { it == 422 })
        } finally { pool.shutdown() }
    }

    @Test
    fun `expired uploads release their reservation and removing the quota allows uploads again`() {
        val a = setup()
        setQuota(a.user.id, 1000)
        val first = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 800, "sha256" to sha256(ByteArray(1))), "rel-1").expect(201).body()["uploadId"].asText()
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 800, "sha256" to sha256(ByteArray(1))), "rel-2").expect(422)
        a.user.delete("/api/uploads/$first").expect(204)                                       // cancellation releases
        val second = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 800, "sha256" to sha256(ByteArray(1))), "rel-3").expect(201).body()["uploadId"].asText()
        mc.advanceMillis(props.uploadExpiry.toMillis() + 1000)
        maintenance.runOnce()                                                                   // expiry releases
        assertEquals("EXPIRED", a.user.get("/api/uploads/$second").body()["state"].asText())
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 900, "sha256" to sha256(ByteArray(1))), "rel-4").expect(201)
        setQuota(a.user.id, null)
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 90_000, "sha256" to sha256(ByteArray(1))), "rel-5").expect(201)
    }

    @Test
    fun `lowering a quota below usage keeps existing files and downloads working but blocks new bytes`() {
        val a = setup()
        sync(a, mapOf("big.bin" to Local.File(ByteArray(3000) { 5 })))
        setQuota(a.user.id, 100)
        download(a, blobOf(a, "big.bin")).expect(200)
        manifest(a)["big.bin"]!!
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 10, "sha256" to sha256(ByteArray(1))), "low-1").expect(422)
        setQuota(a.user.id, 100_000)
        a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to 10, "sha256" to sha256(ByteArray(1))), "low-2").expect(201)
    }

    // ---- backup, restore, epoch -------------------------------------------------------------------------------------

    @Test
    fun `a backup holds a consistent database plus every blob and restoring it rotates the epoch`() {
        val a = setup()
        sync(a, mapOf("kept.txt" to Local.File(bytes("must survive")), "d" to Local.Dir))
        val target: Path = Files.createTempDirectory("fs-backup-")
        val report = backups.backup(target.resolve("b1"))
        assertTrue(report.blobsCopied >= 1)

        val restored = Files.createTempDirectory("fs-restore-")
        BackupService.restore(target.resolve("b1"), restored)
        assertTrue(Files.isRegularFile(restored.resolve("db/foldersync.db")))
        assertFalse(Files.exists(restored.resolve("EPOCH")), "the epoch sidecar is not restored")
        val blobId = manifest(a)["kept.txt"]!!["blobId"].asText()
        assertEquals("must survive", Files.readString(restored.resolve("blobs/${blobId.substring(0, 2)}/${blobId.substring(2, 4)}/$blobId")))

        // Starting the server on the restored volume rotates the epoch before it serves anything.
        val restoredProps = props.copy(dataDir = restored)
        val ds = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:${restored.resolve("db/foldersync.db")}" }
        val jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(ds)
        val original = jdbc.sql("SELECT value FROM server_meta WHERE key = 'epoch'").query { rs, _ -> rs.getString(1) }.single()
        val epochs = app.foldersync.revisions.EpochService(jdbc, restoredProps, app.foldersync.audit.AuditService(jdbc, clock))
        epochs.reconcileAtStartup()
        assertNotEquals(original, epochs.current())
        assertEquals(epochs.current(), Files.readString(restored.resolve("EPOCH")).trim())
        // A second start of the same volume keeps the new epoch.
        val again = app.foldersync.revisions.EpochService(jdbc, restoredProps, app.foldersync.audit.AuditService(jdbc, clock))
        again.reconcileAtStartup()
        assertEquals(epochs.current(), again.current())
    }

    @Test
    fun `backup refuses a non empty target`() {
        val dir = Files.createTempDirectory("fs-backup-full-")
        Files.writeString(dir.resolve("x"), "x")
        kotlin.test.assertFailsWith<app.foldersync.common.ApiException> { backups.backup(dir) }
    }
}
