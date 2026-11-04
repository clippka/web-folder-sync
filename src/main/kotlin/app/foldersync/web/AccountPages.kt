package app.foldersync.web

import app.foldersync.common.ApiException
import app.foldersync.config.FolderSyncProperties
import app.foldersync.identity.AppUser
import app.foldersync.identity.Role
import app.foldersync.identity.UserService
import app.foldersync.spaces.DeviceService
import app.foldersync.spaces.SpaceInsights
import app.foldersync.spaces.SpaceService
import app.foldersync.transfers.QuotaService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

private const val GIB = 1024L * 1024 * 1024

/** Runs a page action and turns a stable API error into a message shown after the redirect. */
private inline fun RedirectAttributes.attempt(ok: String, block: () -> Unit) {
    try { block(); addFlashAttribute("notice", ok) } catch (e: ApiException) { addFlashAttribute("error", e.message ?: e.code) }
}

/** Account, limits and devices of the signed-in user. Nothing here reaches another user's data. */
@Controller
class AccountPages(
    private val users: UserService,
    private val quotas: QuotaService,
    private val devices: DeviceService,
    private val props: FolderSyncProperties,
) {
    @GetMapping("/account")
    fun account(@AuthenticationPrincipal user: AppUser, model: Model): String {
        val usage = quotas.usage(user.id)
        model.addAttribute("usage", usage)
        model.addAttribute("limits", props)
        return "account"
    }

    /** A successful change ends every session of this user, including this one, so the person signs in again. */
    @PostMapping("/account/password")
    fun password(
        @AuthenticationPrincipal user: AppUser, @RequestParam currentPassword: String, @RequestParam newPassword: String, @RequestParam confirmPassword: String,
        request: HttpServletRequest, ra: RedirectAttributes,
    ): String {
        if (newPassword != confirmPassword) { ra.addFlashAttribute("error", "The new passwords do not match"); return "redirect:/account" }
        return try {
            users.changeOwnPassword(user, currentPassword, newPassword)
            request.getSession(false)?.invalidate()
            "redirect:/login?changed"
        } catch (e: ApiException) {
            ra.addFlashAttribute("error", e.message ?: e.code); "redirect:/account"
        }
    }

    @GetMapping("/devices")
    fun devices(@AuthenticationPrincipal user: AppUser, model: Model): String {
        model.addAttribute("devices", devices.list(user.id))
        return "devices"
    }

    @PostMapping("/devices/{id}/revoke")
    fun revoke(@AuthenticationPrincipal user: AppUser, @PathVariable id: String, ra: RedirectAttributes): String {
        ra.attempt("Device revoked. It can no longer plan, upload or commit.") { devices.revoke(id, user.id) }
        return "redirect:/devices"
    }
}

/** Administrator screens: accounts, quotas and password resets only. No space, entry, blob or conflict is reachable. */
@Controller
@RequestMapping("/admin")
class AdminPages(private val users: UserService, private val quotas: QuotaService) {

    data class Row(val id: String, val username: String, val role: Role, val enabled: Boolean, val quotaBytes: Long?, val usedBytes: Long, val self: Boolean)

    @GetMapping("/users")
    fun list(@AuthenticationPrincipal admin: AppUser, model: Model): String {
        model.addAttribute("rows", users.list().map { Row(it.id, it.username, it.role, it.enabled, it.quotaBytes, quotas.usage(it.id).totalBytes, it.id == admin.id) })
        return "admin-users"
    }

    @PostMapping("/users")
    fun create(@AuthenticationPrincipal admin: AppUser, @RequestParam username: String, @RequestParam password: String, @RequestParam(defaultValue = "USER") role: Role, ra: RedirectAttributes): String {
        ra.attempt("Account created.") { users.create(admin.id, username, password, role) }
        return "redirect:/admin/users"
    }

    @PostMapping("/users/{id}/password")
    fun reset(@AuthenticationPrincipal admin: AppUser, @PathVariable id: String, @RequestParam password: String, ra: RedirectAttributes): String {
        ra.attempt("Password reset. Every session of that account has ended.") { users.resetPassword(admin.id, id, password) }
        return "redirect:/admin/users"
    }

    /** Blank removes the quota. The value is in GiB; existing data is never touched. */
    @PostMapping("/users/{id}/quota")
    fun quota(@AuthenticationPrincipal admin: AppUser, @PathVariable id: String, @RequestParam(required = false) quotaGiB: String?, ra: RedirectAttributes): String {
        val bytes = quotaGiB?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val g = it.toBigDecimalOrNull()
            if (g == null || g.signum() < 0 || g > java.math.BigDecimal(8_000_000)) { ra.addFlashAttribute("error", "Enter a number of GiB, or leave it empty for no quota"); return "redirect:/admin/users" }
            g.multiply(java.math.BigDecimal(GIB)).toLong()
        }
        ra.attempt(if (bytes == null) "Quota removed." else "Quota set.") { users.setQuota(admin.id, id, bytes) }
        return "redirect:/admin/users"
    }

    @PostMapping("/users/{id}/enabled")
    fun enabled(@AuthenticationPrincipal admin: AppUser, @PathVariable id: String, @RequestParam enabled: Boolean, ra: RedirectAttributes): String {
        ra.attempt(if (enabled) "Account enabled." else "Account disabled. Its sessions have ended.") { users.setEnabled(admin.id, id, enabled) }
        return "redirect:/admin/users"
    }
}

/** Per-space screens: ignore rules with explanations and policy preview, and the activity log. */
@Controller
class SpaceInsightPages(private val spaces: SpaceService, private val insights: SpaceInsights) {

    @GetMapping("/spaces/{id}/rules")
    fun rules(
        @AuthenticationPrincipal user: AppUser, @PathVariable id: String,
        @RequestParam(required = false) path: String?, @RequestParam(defaultValue = "false") directory: Boolean, model: Model,
    ): String {
        model.addAttribute("space", spaces.require(id, user.id))
        model.addAttribute("files", insights.ruleFiles(user.id, id))
        if (!path.isNullOrBlank()) {
            model.addAttribute("asked", path)
            try { model.addAttribute("explanation", insights.explain(user.id, id, path, directory)) } catch (e: ApiException) { model.addAttribute("error", e.message) }
        }
        return "rules"
    }

    @PostMapping("/spaces/{id}/rules/preview")
    fun preview(@AuthenticationPrincipal user: AppUser, @PathVariable id: String, @RequestParam path: String, @RequestParam text: String, model: Model): String {
        model.addAttribute("space", spaces.require(id, user.id))
        model.addAttribute("files", insights.ruleFiles(user.id, id))
        model.addAttribute("proposedPath", path)
        model.addAttribute("proposedText", text)
        try { model.addAttribute("preview", insights.previewPolicy(user.id, id, path, text)) } catch (e: ApiException) { model.addAttribute("error", e.message) }
        return "rules"
    }

    @GetMapping("/spaces/{id}/activity")
    fun activity(@AuthenticationPrincipal user: AppUser, @PathVariable id: String, model: Model): String {
        model.addAttribute("space", spaces.require(id, user.id))
        model.addAttribute("items", insights.activity(user.id, id, 200))
        return "activity"
    }
}
