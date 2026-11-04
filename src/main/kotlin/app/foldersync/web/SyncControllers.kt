package app.foldersync.web

import app.foldersync.identity.AppUser
import app.foldersync.journals.AckRequest
import app.foldersync.journals.JournalService
import app.foldersync.manifests.ManifestService
import app.foldersync.reconciliation.AppendEntriesRequest
import app.foldersync.reconciliation.ConflictService
import app.foldersync.reconciliation.CreatePlanRequest
import app.foldersync.reconciliation.PlanService
import app.foldersync.reconciliation.ResolveRequest
import app.foldersync.revisions.CommitRequest
import app.foldersync.revisions.CommitService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** Every handler passes the authenticated user's id down; services answer 404 for anything that user does not own. */
@RestController
@RequestMapping("/api")
class SyncController(
    private val manifests: ManifestService,
    private val plans: PlanService,
    private val commits: CommitService,
    private val journals: JournalService,
    private val conflicts: ConflictService,
) {
    @GetMapping("/spaces/{id}/manifest")
    fun manifest(
        @AuthenticationPrincipal u: AppUser, @PathVariable id: String,
        @RequestParam(required = false) revision: Long?, @RequestParam(required = false) after: String?,
        @RequestParam(required = false) limit: Int?, @RequestParam(required = false) epoch: String?,
    ) = manifests.manifest(u.id, id, revision, after, limit, epoch)

    @GetMapping("/spaces/{id}/changes")
    fun changes(
        @AuthenticationPrincipal u: AppUser, @PathVariable id: String,
        @RequestParam(defaultValue = "0") after: Long, @RequestParam(required = false) afterPath: String?,
        @RequestParam(required = false) limit: Int?, @RequestParam(required = false) epoch: String?,
    ) = manifests.changes(u.id, id, after, afterPath, limit, epoch)

    @PostMapping("/spaces/{id}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPlan(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestBody body: CreatePlanRequest) = plans.create(u.id, id, body)

    @PostMapping("/plans/{id}/entries")
    fun appendEntries(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestBody body: AppendEntriesRequest) = plans.append(u.id, id, body)

    @GetMapping("/plans/{id}")
    fun plan(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = plans.get(u.id, id)

    @GetMapping("/plans/{id}/operations")
    fun operations(
        @AuthenticationPrincipal u: AppUser, @PathVariable id: String,
        @RequestParam(defaultValue = "-1") after: Int, @RequestParam(defaultValue = "500") limit: Int,
    ) = plans.operations(u.id, id, after, limit)

    @PostMapping("/plans/{id}/commit")
    fun commit(
        @AuthenticationPrincipal u: AppUser, @PathVariable id: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?, @RequestBody body: CommitRequest,
    ) = commits.commit(u.id, id, body, key)

    @PostMapping("/plans/{id}/acknowledgements")
    fun acknowledge(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestBody body: AckRequest) = journals.acknowledge(u.id, id, body)

    @GetMapping("/spaces/{id}/conflicts")
    fun conflicts(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestParam(defaultValue = "true") unresolved: Boolean) =
        conflicts.list(u.id, id, unresolved)

    @GetMapping("/conflicts/{id}")
    fun conflict(@AuthenticationPrincipal u: AppUser, @PathVariable id: String) = conflicts.get(u.id, id)

    @GetMapping("/conflicts/{id}/preview")
    fun preview(@AuthenticationPrincipal u: AppUser, @PathVariable id: String, @RequestParam side: String) = conflicts.preview(u.id, id, side)

    @PostMapping("/conflicts/{id}/resolve")
    fun resolve(
        @AuthenticationPrincipal u: AppUser, @PathVariable id: String,
        @RequestHeader("Idempotency-Key", required = false) key: String?, @RequestBody body: ResolveRequest,
    ) = conflicts.resolve(u.id, id, body, key)
}
