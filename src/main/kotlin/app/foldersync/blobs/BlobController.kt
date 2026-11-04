package app.foldersync.blobs

import app.foldersync.common.ApiException
import app.foldersync.identity.AppUser
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/spaces/{spaceId}/blobs")
class BlobController(private val blobs: BlobService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Streams stored bytes. The database is consulted before streaming and no transaction or connection is held while
     * the file is sent. Content is always an attachment of unknown type; nothing stored is ever rendered by the browser.
     */
    @GetMapping("/{blobId}/content")
    fun content(@AuthenticationPrincipal user: AppUser, @PathVariable spaceId: String, @PathVariable blobId: String): ResponseEntity<Resource> {
        val blob = blobs.authorizeDownload(spaceId, blobId, user.id)
        val path = blobs.store().path(blob.id)
        if (!java.nio.file.Files.isRegularFile(path)) {
            log.error("Blob {} is referenced but its file is missing", blob.id)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable", "Stored content is unavailable", retryable = true)
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("content.bin").build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Content-SHA256", blob.sha256)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .body(FileSystemResource(path))
    }
}
