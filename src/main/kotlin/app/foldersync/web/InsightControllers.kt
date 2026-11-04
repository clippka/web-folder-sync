package app.foldersync.web

import app.foldersync.config.FolderSyncProperties
import app.foldersync.identity.AppUser
import app.foldersync.spaces.SpaceInsights
import app.foldersync.transfers.QuotaService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class PreviewPolicyRequest(val path: String, val text: String)
data class UsageDto(val committedBytes: Long, val reservedBytes: Long, val usedBytes: Long, val quotaBytes: Long?, val maxFileSizeBytes: Long, val maxIncludedFiles: Int)

/** Read-only views for the space owner. Another user's space id answers 404 for everyone, administrators included. */
@RestController
@RequestMapping("/api")
class InsightController(
    private val insights: SpaceInsights,
    private val quotas: QuotaService,
    private val props: FolderSyncProperties,
) {
    @GetMapping("/spaces/{id}/summary")
    fun summary(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = insights.summary(u.id, id)

    @GetMapping("/spaces/{id}/activity")
    fun activity(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestParam(defaultValue = "100") limit: Int) =
        insights.activity(u.id, id, limit)

    @GetMapping("/spaces/{id}/policy")
    fun policy(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = insights.ruleFiles(u.id, id)

    @GetMapping("/spaces/{id}/policy/explain")
    fun explain(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestParam path: String, @RequestParam(defaultValue = "false") directory: Boolean) =
        insights.explain(u.id, id, path, directory)

    /** Computes only; it stores nothing. A POST so the proposed text is not put in a URL, still CSRF-protected. */
    @PostMapping("/spaces/{id}/policy/preview")
    fun preview(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestBody body: PreviewPolicyRequest) =
        insights.previewPolicy(u.id, id, body.path, body.text)

    @GetMapping("/account/usage")
    fun usage(@AuthenticationPrincipal u: AppUser): UsageDto {
        val x = quotas.usage(u.id)
        return UsageDto(x.committedBytes, x.reservedBytes, x.totalBytes, x.quotaBytes, props.maxFileSizeBytes, props.maxIncludedFiles)
    }
}
