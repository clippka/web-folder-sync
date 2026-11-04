package app.foldersync.identity

import app.foldersync.audit.AuditService
import app.foldersync.config.FolderSyncProperties
import app.foldersync.config.StartupFailure
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@Configuration
class PasswordConfig {
    /** bcrypt via the delegating encoder, so hashes carry their algorithm id and can be upgraded later. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
}

/**
 * Provisions the first administrator from a mounted secret file. There is no default credential and no public signup:
 * when nothing is configured and no administrator exists, the log says so and nobody can sign in.
 */
@Component
class AdminBootstrap(private val users: UserService, private val props: FolderSyncProperties) : InitializingBean {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        if (users.count(Role.ADMIN) > 0) return
        val name = props.admin.username
        val file = props.admin.passwordFile
        if (name == null || file == null) {
            log.warn("No administrator exists. Set foldersync.admin.username and foldersync.admin.password-file to provision one.")
            return
        }
        val secret = try {
            Files.readString(file).trim()
        } catch (e: Exception) {
            throw StartupFailure("Administrator password file '$file' cannot be read: ${e.message}", e)
        }
        try {
            users.create(null, name, secret, Role.ADMIN)
        } catch (e: app.foldersync.common.ApiException) {
            throw StartupFailure("Administrator bootstrap failed (${e.code}): ${e.message}", e)
        }
        log.info("Administrator '{}' provisioned from the mounted secret", users.normalize(name))
    }
}

/**
 * Failed-login throttling per username and per client address. In memory by design: one application instance, and a
 * restart clearing the counters is acceptable for a brute-force limiter.
 */
@Component
class LoginRateLimiter(private val props: FolderSyncProperties, private val clock: Clock) {
    private class Bucket { val failures = ArrayDeque<Long>(); var lockedUntil = 0L }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun isLocked(username: String, address: String): Boolean {
        val now = clock.millis()
        return keys(username, address).any { k -> buckets[k]?.let { synchronized(it) { it.lockedUntil > now } } == true }
    }

    fun failure(username: String, address: String) {
        val now = clock.millis()
        for (k in keys(username, address)) {
            val limit = if (k.startsWith("ip:")) props.login.maxFailures * 10 else props.login.maxFailures
            val b = buckets.computeIfAbsent(k) { Bucket() }
            synchronized(b) {
                val cutoff = now - props.login.window.toMillis()
                while (b.failures.isNotEmpty() && b.failures.first() < cutoff) b.failures.removeFirst()
                b.failures.addLast(now)
                if (b.failures.size >= limit) { b.lockedUntil = now + props.login.lockout.toMillis(); b.failures.clear() }
            }
        }
        if (buckets.size > 50_000) buckets.entries.removeIf { synchronized(it.value) { it.value.lockedUntil < now && it.value.failures.isEmpty() } }
    }

    fun success(username: String) {
        buckets.remove("user:${username.lowercase()}")
    }

    private fun keys(username: String, address: String) = listOf("user:${username.trim().lowercase()}", "ip:$address")
}

/** Not a @Component: it belongs to the security filter chain only, not to the servlet container's filter list. */
class LoginRateLimitFilter(private val limiter: LoginRateLimiter) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !(request.method == "POST" && request.requestURI.removePrefix(request.contextPath) == "/login")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (limiter.isLocked(request.getParameter("username") ?: "", request.remoteAddr)) {
            response.status = 429
            response.setHeader("Retry-After", "900")
            response.contentType = "application/json"
            response.writer.write("""{"code":"too_many_attempts","message":"Too many failed sign-in attempts. Try again later.","retryable":true}""")
            return
        }
        chain.doFilter(request, response)
    }
}

@Component
class LoginEvents(private val limiter: LoginRateLimiter, private val audit: AuditService) {
    @EventListener
    fun failed(e: AuthenticationFailureBadCredentialsEvent) {
        val name = e.authentication.name ?: ""
        val details = e.authentication.details as? org.springframework.security.web.authentication.WebAuthenticationDetails
        limiter.failure(name, details?.remoteAddress ?: "unknown")
        audit.record(null, "login.failed")
    }

    @EventListener
    fun succeeded(e: AuthenticationSuccessEvent) {
        val principal = e.authentication.principal as? AppUser ?: return
        limiter.success(principal.username)
        audit.record(principal.id, "login.succeeded")
    }
}

/**
 * Drops sessions whose user was disabled, deleted or had the password changed since the session began, so a
 * password reset or account disable takes effect immediately instead of at session expiry.
 */
class SessionValidityFilter(private val users: UserService) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AppUser
        if (principal != null) {
            val state = users.sessionState(principal.id)
            if (state == null || !state.first || state.second != principal.authVersion) {
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
            }
        }
        chain.doFilter(request, response)
    }
}
