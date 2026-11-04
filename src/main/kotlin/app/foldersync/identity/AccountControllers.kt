package app.foldersync.identity

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class AccountDto(val id: String, val username: String, val role: Role)
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
data class CreateUserRequest(val username: String, val password: String, val role: Role = Role.USER)
data class PasswordRequest(val password: String)
data class QuotaRequest(val quotaBytes: Long?)
data class EnabledRequest(val enabled: Boolean)

@RestController
@RequestMapping("/api/account")
class AccountController(private val users: UserService) {

    @GetMapping
    fun me(@AuthenticationPrincipal user: AppUser) = AccountDto(user.id, user.username, user.role)

    /** Ends the current session as well; every other session of this user is invalidated by the auth version bump. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@AuthenticationPrincipal user: AppUser, @RequestBody body: ChangePasswordRequest, request: HttpServletRequest) {
        users.changeOwnPassword(user, body.currentPassword, body.newPassword)
        request.getSession(false)?.invalidate()
    }
}

/** Account administration only. Nothing here reaches spaces, entries, blobs or conflicts. */
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController(private val users: UserService) {

    @GetMapping
    fun list() = users.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal admin: AppUser, @RequestBody body: CreateUserRequest) =
        users.create(admin.id, body.username, body.password, body.role)

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@AuthenticationPrincipal admin: AppUser, @PathVariable id: String, @RequestBody body: PasswordRequest) =
        users.resetPassword(admin.id, id, body.password)

    @PutMapping("/{id}/quota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun quota(@AuthenticationPrincipal admin: AppUser, @PathVariable id: String, @RequestBody body: QuotaRequest) =
        users.setQuota(admin.id, id, body.quotaBytes)

    @PostMapping("/{id}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun enabled(@AuthenticationPrincipal admin: AppUser, @PathVariable id: String, @RequestBody body: EnabledRequest) =
        users.setEnabled(admin.id, id, body.enabled)
}
