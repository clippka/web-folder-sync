package app.foldersync

import app.foldersync.blobs.BlobStore
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals

/** Default limits: 100 MiB files in 8 MiB chunks (acceptance criterion 13, server half). */
class LargeFileBoundaryTest : SyncTest() {
    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun more(r: DynamicPropertyRegistry) {
            r.add("foldersync.chunk-size-bytes") { (8 * 1024 * 1024).toString() }
        }

        const val LIMIT = 104_857_600
    }

    @Autowired lateinit var store: BlobStore

    private fun pattern(size: Int) = ByteArray(size).also { b -> for (i in b.indices) b[i] = (i * 31 + (i ushr 8)).toByte() }

    @Test
    fun `a file of exactly 104857600 bytes uploads in 8 MiB chunks and downloads identically`() {
        val a = setup()
        val data = pattern(LIMIT)
        val digest = sha256(data)
        val outcome = sync(a, mapOf("big.bin" to Local.File(data)))
        assertEquals(200, outcome.commit!!.andReturn().response.status)
        assertEquals("UPLOAD", kinds(outcome)["big.bin"])

        val entry = manifest(a)["big.bin"]!!
        assertEquals(LIMIT.toLong(), entry["size"].asLong())
        // Verify the stored file on disk by streaming it, then the download endpoint by length.
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(store.path(entry["blobId"].asText())).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        assertEquals(digest, md.digest().joinToString("") { "%02x".format(it) })
        assertEquals(LIMIT, download(a, entry["blobId"].asText()).expect(200).andReturn().response.contentLength)
    }

    @Test
    fun `one byte over the limit is reported and skipped and cannot be uploaded by chunking`() {
        val a = setup()
        val over = ByteArray(LIMIT + 1)
        val out = sync(a, mapOf("over.bin" to Local.File(over), "ok.txt" to Local.File("ok".toByteArray())))
        assertEquals("SUSPENDED", kinds(out)["over.bin"])
        assertEquals(setOf("ok.txt"), manifest(a).keys)
        val begin = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to LIMIT + 1, "sha256" to sha256(over)), "big-1").expect(413)
        assertEquals("file_too_large", begin.code())
        // Declaring the limit and sending a larger last chunk is also refused.
        val id = a.user.post("/api/spaces/${a.spaceId}/uploads", mapOf("size" to LIMIT, "sha256" to sha256(ByteArray(LIMIT))), "big-2").expect(201).body()
        assertEquals(13, id["chunkCount"].asInt())
    }
}
