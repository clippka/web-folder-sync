package app.foldersync.transfers

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.blobs.BlobService
import app.foldersync.common.ApiException
import app.foldersync.common.IdempotencyService
import app.foldersync.common.Ids
import app.foldersync.common.isSha256
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.Tx
import app.foldersync.spaces.SpaceService
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.util.Comparator

data class BeginUploadRequest(val size: Long, val sha256: String)

data class UploadDto(
    val uploadId: String,
    val spaceId: String,
    val state: String,
    val size: Long,
    val sha256: String,
    val chunkSize: Int,
    val chunkCount: Int,
    val expiresAt: Long,
    val blobId: String?,
    /** Chunk indexes already stored, so an interrupted client resumes instead of starting over. */
    val receivedChunks: List<Int> = emptyList(),
    /** True once a commit used this upload; a resuming client must then begin a new one. */
    val consumed: Boolean = false,
)

data class ChunkResult(val index: Int, val size: Int, val duplicate: Boolean)

data class UploadRow(
    val id: String, val ownerId: String, val spaceId: String, val size: Long, val sha256: String,
    val chunkSize: Int, val chunkCount: Int, val state: String, val blobId: String?, val consumed: Boolean, val expiresAt: Long,
)

/**
 * Resumable fixed-size chunk uploads. Chunks are written at their offset into one staging file under
 * `<data>/staging/<uploadId>/`; the assembled file is hashed once while streaming, then moved atomically into the blob
 * store *before* any metadata references it. The blob id equals the upload id, which lets a retried `complete` find a
 * blob whose move succeeded but whose database row did not.
 */
@Service
class UploadService(
    private val jdbc: JdbcClient,
    private val props: FolderSyncProperties,
    private val clock: Clock,
    private val tx: Tx,
    private val quota: QuotaService,
    private val blobs: BlobService,
    private val spaces: SpaceService,
    private val idempotency: IdempotencyService,
    private val audit: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = Array(64) { Any() }
    private fun lockFor(uploadId: String, index: Int) = locks[(uploadId.hashCode() * 31 + index).mod(locks.size)]

    fun begin(ownerId: String, spaceId: String, req: BeginUploadRequest, idempotencyKey: String?): UploadDto {
        spaces.require(spaceId, ownerId)
        if (req.size < 0) throw ApiException.invalid("invalid_size", "Size must not be negative")
        if (!isSha256(req.sha256)) throw ApiException.invalid("invalid_digest", "sha256 must be 64 lowercase hex characters")
        if (req.size > props.maxFileSizeBytes) {
            throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large", "Files larger than ${props.maxFileSizeBytes} bytes are not synchronized",
                details = mapOf("limitBytes" to props.maxFileSizeBytes, "sizeBytes" to req.size))
        }
        val free = Files.getFileStore(props.stagingDir).usableSpace
        if (free - req.size < props.minFreeDiskBytes) {
            throw ApiException(HttpStatus.INSUFFICIENT_STORAGE, "insufficient_disk_space", "The server does not have enough free disk space", retryable = true)
        }
        return tx.run {
            idempotency.run(ownerId, "upload.begin:$spaceId", idempotencyKey, req, UploadDto::class.java) {
                val id = Ids.token()
                val chunkCount = ((req.size + props.chunkSizeBytes - 1) / props.chunkSizeBytes).toInt()
                val now = clock.millis()
                val expires = now + props.uploadExpiry.toMillis()
                jdbc.sql(
                    "INSERT INTO uploads (id, owner_id, space_id, expected_size, expected_sha256, chunk_size, chunk_count, state, expires_at, created_at) VALUES (?,?,?,?,?,?,?, 'OPEN', ?,?)",
                ).args(id, ownerId, spaceId, req.size, req.sha256, props.chunkSizeBytes, chunkCount, expires, now).update()
                quota.reserve(ownerId, spaceId, id, req.size)
                Files.createDirectories(props.stagingDir.resolve(id))
                UploadDto(id, spaceId, "OPEN", req.size, req.sha256, props.chunkSizeBytes, chunkCount, expires, null)
            }
        }
    }

    fun status(ownerId: String, uploadId: String): UploadDto {
        val u = require(ownerId, uploadId)
        return dto(u, received(uploadId))
    }

    fun putChunk(ownerId: String, uploadId: String, index: Int, body: InputStream): ChunkResult {
        val u = require(ownerId, uploadId)
        if (u.state == "EXPIRED" || u.state == "ABORTED" || u.state == "FAILED" || (u.state == "OPEN" && u.expiresAt <= clock.millis())) {
            throw ApiException.gone("upload_expired", "This upload expired or was cancelled; start a new upload")
        }
        if (index < 0 || index >= u.chunkCount) throw ApiException.invalid("invalid_chunk_index", "Chunk index is outside this upload")
        val expectedLen = if (index < u.chunkCount - 1) u.chunkSize else (u.size - u.chunkSize.toLong() * (u.chunkCount - 1)).toInt()
        val data = readExactly(body, expectedLen)
        val sha = hex(MessageDigest.getInstance("SHA-256").digest(data))
        synchronized(lockFor(uploadId, index)) {
            val existing = jdbc.sql("SELECT size, sha256 FROM upload_chunks WHERE upload_id = ? AND idx = ?").args(uploadId, index)
                .query { rs, _ -> rs.getInt(1) to rs.getString(2) }.optional().orElse(null)
            if (existing != null) return duplicateOrFail(index, expectedLen, sha, existing)
            if (u.state != "OPEN") throw ApiException.conflict("upload_complete", "This upload is already complete")
            val file = props.stagingDir.resolve(uploadId).resolve("data")
            Files.createDirectories(file.parent)
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                var pos = index.toLong() * u.chunkSize
                val buf = ByteBuffer.wrap(data)
                while (buf.hasRemaining()) pos += ch.write(buf, pos)
                ch.force(true)
            }
            try {
                jdbc.sql("INSERT INTO upload_chunks (upload_id, idx, size, sha256) VALUES (?,?,?,?)").args(uploadId, index, expectedLen, sha).update()
            } catch (e: DuplicateKeyException) {
                throw ApiException.conflict("chunk_conflict", "Chunk $index was stored concurrently", retryable = true)
            }
        }
        return ChunkResult(index, expectedLen, false)
    }

    private fun duplicateOrFail(index: Int, len: Int, sha: String, existing: Pair<Int, String>): ChunkResult =
        if (existing.first == len && existing.second == sha) ChunkResult(index, len, true)
        else throw ApiException.conflict("chunk_conflict", "Chunk $index was already uploaded with different content")

    fun complete(ownerId: String, uploadId: String): UploadDto {
        var u = require(ownerId, uploadId)
        if (u.state == "COMPLETE") return dto(u, emptyList())
        synchronized(lockFor(uploadId, -1)) {
            u = require(ownerId, uploadId)
            if (u.state == "COMPLETE") return dto(u, emptyList())
            if (u.state != "OPEN" || u.expiresAt <= clock.millis()) throw ApiException.gone("upload_expired", "This upload expired or was cancelled; start a new upload")

            val have = received(uploadId)
            if (have.size != u.chunkCount) {
                throw ApiException.conflict("upload_incomplete", "Chunks are missing", details = mapOf("missing" to (0 until u.chunkCount).filter { it !in have }.take(100)))
            }
            val staged = props.stagingDir.resolve(uploadId).resolve("data")
            val blobPath = blobs.store().path(uploadId)
            // A retried complete may find the file already moved into the blob store by an earlier attempt.
            val source: Path = when {
                Files.isRegularFile(staged) -> staged
                u.size == 0L -> Files.createDirectories(staged.parent).let { Files.createFile(staged) }
                Files.isRegularFile(blobPath) -> blobPath
                else -> { fail(u, "staging_lost"); throw ApiException.gone("upload_failed", "Staged data was lost; start a new upload") }
            }
            val (digest, bytes) = hashFile(source)
            if (bytes != u.size) { fail(u, "size_mismatch"); throw ApiException.invalid("size_mismatch", "Received $bytes bytes but ${u.size} were declared") }
            if (digest != u.sha256) { fail(u, "digest_mismatch"); throw ApiException.invalid("digest_mismatch", "The uploaded content does not match its declared SHA-256") }

            if (source == staged) blobs.store().publish(staged, uploadId)
            tx.run {
                blobs.register(uploadId, u.spaceId, u.ownerId, u.size, u.sha256)
                jdbc.sql("UPDATE uploads SET state = 'COMPLETE', blob_id = ? WHERE id = ? AND state = 'OPEN'").args(uploadId, uploadId).update()
            }
            deleteQuietly(props.stagingDir.resolve(uploadId))
            return dto(require(ownerId, uploadId), emptyList())
        }
    }

    fun abort(ownerId: String, uploadId: String) {
        val u = require(ownerId, uploadId)
        if (u.state != "OPEN") return
        tx.run {
            jdbc.sql("UPDATE uploads SET state = 'ABORTED' WHERE id = ? AND state = 'OPEN'").param(uploadId).update()
            quota.release(uploadId)
        }
        deleteQuietly(props.stagingDir.resolve(uploadId))
    }

    /**
     * Marks lapsed uploads expired, releases their quota reservations and removes their staging bytes. A completed
     * but never-committed upload expires too, which makes its blob collectable. Returns the number expired.
     */
    fun expireStale(): Int {
        val now = clock.millis()
        val ids = jdbc.sql("SELECT id FROM uploads WHERE state IN ('OPEN','COMPLETE') AND consumed = 0 AND expires_at <= ? LIMIT 500")
            .param(now).query { rs, _ -> rs.getString(1) }.list()
        for (id in ids) {
            tx.run {
                jdbc.sql("UPDATE uploads SET state = 'EXPIRED' WHERE id = ? AND state IN ('OPEN','COMPLETE') AND consumed = 0").param(id).update()
                quota.release(id)
            }
            deleteQuietly(props.stagingDir.resolve(id))
        }
        return ids.size
    }

    /** Staging directories with no live upload behind them (crash leftovers, failed or aborted uploads). */
    fun removeOrphanStaging(): Int {
        if (!Files.isDirectory(props.stagingDir)) return 0
        val cutoff = clock.millis() - props.orphanFileGrace.toMillis()
        var n = 0
        Files.list(props.stagingDir).use { dirs ->
            for (dir in dirs.toList()) {
                val open = jdbc.sql("SELECT COUNT(*) FROM uploads WHERE id = ? AND state = 'OPEN'").param(dir.fileName.toString()).query(Int::class.java).single()
                if (open == 0 && Files.getLastModifiedTime(dir).toMillis() < cutoff) { deleteQuietly(dir); n++ }
            }
        }
        return n
    }

    /** Called inside the commit transaction. Verifies the upload is usable for [spaceId] and marks it consumed. */
    fun consume(ownerId: String, spaceId: String, uploadId: String, size: Long, sha256: String?): String {
        val u = jdbc.sql(SELECT + " WHERE id = ? AND owner_id = ?").args(uploadId, ownerId).query(::map).optional().orElse(null)
            ?: throw ApiException.invalid("unknown_upload", "Upload $uploadId does not exist")
        if (u.spaceId != spaceId) throw ApiException.invalid("unknown_upload", "Upload $uploadId does not exist")
        if (u.state != "COMPLETE" || u.blobId == null) throw ApiException.invalid("upload_not_complete", "Upload $uploadId is not complete")
        if (u.consumed) throw ApiException.invalid("upload_already_used", "Upload $uploadId was already used")
        if (u.expiresAt <= clock.millis()) throw ApiException.invalid("upload_expired", "Upload $uploadId expired")
        if (u.size != size || (sha256 != null && u.sha256 != sha256)) {
            throw ApiException.invalid("upload_mismatch", "Upload $uploadId does not contain the content the plan expects")
        }
        jdbc.sql("UPDATE uploads SET consumed = 1 WHERE id = ?").param(uploadId).update()
        quota.convert(uploadId)
        return u.blobId
    }

    private fun fail(u: UploadRow, code: String) {
        tx.run {
            jdbc.sql("UPDATE uploads SET state = 'FAILED', failure_code = ? WHERE id = ? AND state = 'OPEN'").args(code, u.id).update()
            quota.release(u.id)
        }
        deleteQuietly(props.stagingDir.resolve(u.id))
        blobs.store().delete(u.id)
    }

    private fun require(ownerId: String, uploadId: String): UploadRow =
        jdbc.sql("$SELECT WHERE id = ? AND owner_id = ?").args(uploadId, ownerId).query(::map).optional()
            .orElseThrow { ApiException.notFound("upload") }

    private fun received(uploadId: String): List<Int> =
        jdbc.sql("SELECT idx FROM upload_chunks WHERE upload_id = ? ORDER BY idx").param(uploadId).query { rs, _ -> rs.getInt(1) }.list()

    private fun dto(u: UploadRow, received: List<Int>) =
        UploadDto(u.id, u.spaceId, u.state, u.size, u.sha256, u.chunkSize, u.chunkCount, u.expiresAt, u.blobId, received, u.consumed)

    private fun map(rs: java.sql.ResultSet, n: Int) = UploadRow(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
        rs.getString(8), rs.getString(9), rs.getInt(10) == 1, rs.getLong(11),
    )

    private fun readExactly(input: InputStream, expected: Int): ByteArray {
        val buf = ByteArray(expected)
        var read = 0
        while (read < expected) {
            val n = input.read(buf, read, expected - read)
            if (n < 0) break
            read += n
        }
        if (read < expected) throw ApiException.invalid("chunk_size_mismatch", "Chunk must contain exactly $expected bytes (received $read)")
        if (input.read() != -1) throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "chunk_size_mismatch", "Chunk must contain exactly $expected bytes (received more)")
        return buf
    }

    private fun hashFile(path: Path): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        return hex(md.digest()) to total
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun deleteQuietly(dir: Path) {
        try {
            if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: Exception) {
            log.warn("Could not remove {}: {}", dir.fileName, e.message)
        }
    }

    private companion object {
        const val SELECT = "SELECT id, owner_id, space_id, expected_size, expected_sha256, chunk_size, chunk_count, state, blob_id, consumed, expires_at FROM uploads"
    }
}
