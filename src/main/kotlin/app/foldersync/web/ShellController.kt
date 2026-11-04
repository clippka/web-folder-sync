package app.foldersync.web

import app.foldersync.identity.AppUser
import app.foldersync.spaces.SpaceInsights
import app.foldersync.spaces.SpaceService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/** Exposes the signed-in user to the layout. The id namespaces the browser's IndexedDB; it is not a secret. */
@ControllerAdvice(basePackageClasses = [ShellController::class])
class ShellAdvice {
    @ModelAttribute("currentUserId")
    fun userId(@AuthenticationPrincipal user: AppUser?): String = user?.id ?: ""

    @ModelAttribute("isAdmin")
    fun admin(@AuthenticationPrincipal user: AppUser?): Boolean = user?.isAdmin == true

    @ModelAttribute("currentUsername")
    fun username(@AuthenticationPrincipal user: AppUser?): String = user?.username ?: ""
}

@Controller
class ShellController(private val spaces: SpaceService, private val insights: SpaceInsights) {
    @GetMapping("/")
    fun home(): String = "home"

    /** Plain form post handled by Spring Security; no script runs on this page. */
    @GetMapping("/help")
    fun help(): String = "help"

    @GetMapping("/login")
    fun login(): String = "login"

    @GetMapping("/spaces")
    fun spaceList(@AuthenticationPrincipal user: AppUser, model: Model): String {
        model.addAttribute("rows", insights.summaries(user.id))
        return "spaces"
    }

    @PostMapping("/spaces")
    fun createSpace(@AuthenticationPrincipal user: AppUser, @RequestParam name: String): String {
        val created = spaces.create(user.id, name)
        return "redirect:/spaces/${created.id}"
    }

    /** Another user's space answers 404 exactly like a missing one. */
    @GetMapping("/spaces/{id}")
    fun space(@AuthenticationPrincipal user: AppUser, @PathVariable id: String, model: Model): String {
        model.addAttribute("space", spaces.require(id, user.id))
        model.addAttribute("summary", insights.summary(user.id, id))
        return "space"
    }
}
