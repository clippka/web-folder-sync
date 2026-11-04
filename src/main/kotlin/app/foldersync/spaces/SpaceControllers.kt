package app.foldersync.spaces

import app.foldersync.config.FolderSyncProperties
import app.foldersync.identity.AppUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class CreateSpaceRequest(val name: String)
data class CreateDeviceRequest(val label: String? = null)
data class EnrollRequest(val deviceId: String)

/** Limits the browser needs to plan transfers; the server enforces all of them again on every request. */
data class LimitsDto(val maxFileSizeBytes: Long, val maxIncludedFiles: Int, val chunkSizeBytes: Int, val maxManifestBatch: Int,
    val deletionEnabled: Boolean, val deletionWarnCount: Int, val deletionWarnPercent: Int)
data class SpaceDto(val id: String, val name: String, val revision: Long, val policyRevision: Long, val epoch: String, val limits: LimitsDto)
data class BindingDto(val id: String, val deviceId: String, val spaceId: String, val status: BindingStatus, val enrollmentGeneration: Long, val epoch: String, val spaceRevision: Long, val policyRevision: Long)

@RestController
@RequestMapping("/api")
class SpaceController(
    private val spaces: SpaceService,
    private val devices: DeviceService,
    private val bindings: BindingService,
    private val props: FolderSyncProperties,
) {
    private fun dto(s: SpaceRecord) = SpaceDto(
        s.id, s.name, s.revision, s.policyRevision, bindings.currentEpoch(),
        LimitsDto(props.maxFileSizeBytes, props.maxIncludedFiles, props.chunkSizeBytes, props.maxManifestBatch,
            props.deletionEnabled, props.deletionWarnCount, props.deletionWarnPercent),
    )

    @GetMapping("/spaces")
    fun list(@AuthenticationPrincipal u: AppUser) = spaces.list(u.id).map(::dto)

    @PostMapping("/spaces")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal u: AppUser, @RequestBody body: CreateSpaceRequest) = dto(spaces.create(u.id, body.name))

    @GetMapping("/spaces/{id}")
    fun get(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = dto(spaces.require(id, u.id))

    @GetMapping("/devices")
    fun devices(@AuthenticationPrincipal u: AppUser) = devices.list(u.id)

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    fun createDevice(@AuthenticationPrincipal u: AppUser, @RequestBody body: CreateDeviceRequest) = devices.create(u.id, body.label)

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeDevice(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = devices.revoke(id, u.id)

    @PostMapping("/spaces/{id}/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    fun enroll(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestBody body: EnrollRequest): BindingDto {
        val b = bindings.enroll(u.id, id, body.deviceId)
        val s = spaces.require(id, u.id)
        return BindingDto(b.id, b.deviceId, b.spaceId, b.status, b.enrollmentGeneration, bindings.currentEpoch(), s.revision, s.policyRevision)
    }

    @GetMapping("/spaces/{id}/bindings")
    fun listBindings(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = bindings.listForSpace(u.id, id)

    @DeleteMapping("/bindings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnect(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = bindings.disconnect(u.id, id)
}
