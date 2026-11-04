package app.foldersync.web

import app.foldersync.identity.LoginRateLimitFilter
import app.foldersync.identity.LoginRateLimiter
import app.foldersync.identity.SessionValidityFilter
import app.foldersync.identity.UserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

/**
 * Deny by default. Only the shell, login, bundled assets and minimal health are public; `/api/...` needs a session and
 * answers with JSON (never the login page); the admin API needs the administrator role, which grants account
 * management only and no access to anyone's sync content (every space query is filtered by owner).
 */
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity, users: UserService, limiter: LoginRateLimiter): SecurityFilterChain {
        val api = PathPatternRequestMatcher.withDefaults().matcher("/api/**")

        http.authorizeHttpRequests {
            it.requestMatchers("/", "/login", "/help", "/error", "/app/**", "/actuator/health/**").permitAll()
            it.requestMatchers("/spaces", "/spaces/**", "/account", "/account/**", "/devices", "/devices/**").authenticated()
            it.requestMatchers("/admin/**").hasRole("ADMIN")
            it.requestMatchers("/api/admin/**").hasRole("ADMIN")
            it.requestMatchers("/api/**").authenticated()
            it.anyRequest().denyAll()
        }

        // The token lives in a script-readable cookie so the TypeScript coordinator and HTMX can echo it in
        // X-XSRF-TOKEN. Every state-changing request is checked, including those under /api.
        http.csrf {
            it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            it.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
        }

        http.formLogin {
            it.loginPage("/login").defaultSuccessUrl("/", true).failureUrl("/login?error")
        }
        http.logout {
            it.logoutUrl("/logout").deleteCookies("FSSESSION", "XSRF-TOKEN").invalidateHttpSession(true).logoutSuccessHandler(logoutSuccess())
        }
        http.sessionManagement { it.sessionFixation { f -> f.changeSessionId() } }

        http.exceptionHandling {
            it.defaultAuthenticationEntryPointFor(jsonUnauthorized(), api)
            // Pages (anything that is not /api) send anonymous visitors to the sign-in form.
            it.defaultAuthenticationEntryPointFor(LoginUrlAuthenticationEntryPoint("/login"), PathPatternRequestMatcher.withDefaults().matcher("/**"))
            it.defaultAccessDeniedHandlerFor(jsonDenied(), api)
        }

        http.headers {
            it.contentSecurityPolicy { csp ->
                csp.policyDirectives("default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
            }
            it.referrerPolicy { r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
            it.frameOptions { f -> f.deny() }
        }

        http.addFilterBefore(LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter::class.java)
        http.addFilterBefore(SessionValidityFilter(users), AuthorizationFilter::class.java)
        return http.build()
    }

    private fun jsonUnauthorized() = AuthenticationEntryPoint { _, res, _ ->
        writeError(res, HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in required")
    }

    private fun jsonDenied() = AccessDeniedHandler { _: HttpServletRequest, res: HttpServletResponse, ex: AccessDeniedException ->
        if (ex is CsrfException) writeError(res, HttpStatus.FORBIDDEN, "csrf_invalid", "Missing or invalid CSRF token")
        else writeError(res, HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to do that")
    }

    private fun logoutSuccess() = LogoutSuccessHandler { req, res, _ ->
        if (req.getHeader("Accept")?.contains(MediaType.APPLICATION_JSON_VALUE) == true) {
            res.status = HttpStatus.NO_CONTENT.value()
        } else {
            res.sendRedirect(req.contextPath + "/login?logout")
        }
    }

    private fun writeError(res: HttpServletResponse, status: HttpStatus, code: String, message: String) {
        res.status = status.value()
        res.contentType = MediaType.APPLICATION_JSON_VALUE
        res.characterEncoding = "UTF-8"
        res.writer.write("""{"code":"$code","message":"$message","retryable":false}""")
    }
}
