package app.foldersync.transfers

import app.foldersync.identity.AppUser
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class UploadController(private val uploads: UploadService, private val leases: LeaseService) {

    @PostMapping("/spaces/{spaceId}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    fun begin(
        @AuthenticationPrincipal u: AppUser,
        @PathVariable spaceId: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody body: BeginUploadRequest,
    ) = uploads.begin(u.id, spaceId, body, key)

    @GetMapping("/uploads/{id}")
    fun status(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = uploads.status(u.id, id)

    /** The body is the raw chunk. It is read into a buffer of exactly the expected length, never more. */
    @PutMapping("/uploads/{id}/chunks/{index}")
    fun chunk(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @PathVariable index: Int, request: HttpServletRequest) =
        uploads.putChunk(u.id, id, index, request.inputStream)

    @PostMapping("/uploads/{id}/complete")
    fun complete(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = uploads.complete(u.id, id)

    @DeleteMapping("/uploads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun abort(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = uploads.abort(u.id, id)

    @PostMapping("/leases/{id}/renew")
    fun renew(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = leases.renew(u.id, id)

    @DeleteMapping("/leases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun release(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = leases.release(u.id, id)
}
