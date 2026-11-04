package app.foldersync.identity

import app.foldersync.common.args
import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.common.Ids
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.text.Normalizer
import java.time.Clock
import java.util.Locale

enum class Role { ADMIN, USER }

/** Authenticated principal. [authVersion] ties a session to the password it was created with. */
class AppUser(
    val id: String,
    username: String,
    password: String?,
    enabled: Boolean,
    val role: Role,
    val authVersion: Long,
    authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_${role.name}")),
) : User(username, password ?: "", enabled, true, true, true, authorities) {
    val isAdmin: Boolean get() = role == Role.ADMIN

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class UserRecord(
    val id: String,
    val username: String,
    val role: Role,
    val enabled: Boolean,
    val quotaBytes: Long?,
    val createdAt: Long,
)

object Passwords {
    const val MIN_LENGTH = 12
    /** bcrypt only considers the first 72 bytes; refuse longer input instead of silently truncating it. */
    const val MAX_BYTES = 72

    fun validate(password: String) {
        if (password.length < MIN_LENGTH) throw ApiException.invalid("weak_password", "Password must be at least $MIN_LENGTH characters")
        if (password.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ApiException.invalid("weak_password", "Password must be at most $MAX_BYTES bytes")
    }
}

@Service
class UserService(
    private val jdbc: JdbcClient,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
    private val audit: AuditService,
) : UserDetailsService {

    fun normalize(username: String): String = Normalizer.normalize(username.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private val usernamePattern = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

    fun create(actorId: String?, username: String, password: String, role: Role): UserRecord {
        val name = normalize(username)
        if (!usernamePattern.matches(name)) throw ApiException.invalid("invalid_username", "Username must be 3-64 characters: letters, digits, '.', '_' or '-'")
        Passwords.validate(password)
        val id = Ids.uuid()
        val now = clock.millis()
        try {
            jdbc.sql("INSERT INTO users (id, username, password_hash, role, enabled, created_at, password_changed_at) VALUES (?,?,?,?,1,?,?)")
                .args(id, name, encoder.encode(password), role.name, now, now).update()
        } catch (e: DuplicateKeyException) {
            throw ApiException.conflict("username_taken", "That username already exists")
        } catch (e: DataIntegrityViolationException) {
            throw ApiException.conflict("username_taken", "That username already exists")
        }
        audit.record(actorId, "user.created", detail = mapOf("userId" to id, "role" to role.name))
        return UserRecord(id, name, role, true, null, now)
    }

    override fun loadUserByUsername(username: String): UserDetails =
        jdbc.sql("SELECT id, username, password_hash, role, enabled, auth_version FROM users WHERE username = ?")
            .param(normalize(username))
            .query { rs, _ ->
                AppUser(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(5) == 1, Role.valueOf(rs.getString(4)), rs.getLong(6))
            }.optional().orElseThrow { UsernameNotFoundException("unknown user") }

    fun get(id: String): UserRecord? =
        jdbc.sql("SELECT id, username, role, enabled, quota_bytes, created_at FROM users WHERE id = ?").param(id).query(::map).optional().orElse(null)

    fun list(): List<UserRecord> =
        jdbc.sql("SELECT id, username, role, enabled, quota_bytes, created_at FROM users ORDER BY username").query(::map).list()

    fun count(role: Role): Int =
        jdbc.sql("SELECT COUNT(*) FROM users WHERE role = ?").param(role.name).query(Int::class.java).single()

    /** Current (enabled, authVersion) for session validation, or null when the user no longer exists. */
    fun sessionState(id: String): Pair<Boolean, Long>? =
        jdbc.sql("SELECT enabled, auth_version FROM users WHERE id = ?").param(id)
            .query { rs, _ -> (rs.getInt(1) == 1) to rs.getLong(2) }.optional().orElse(null)

    fun changeOwnPassword(user: AppUser, current: String, new: String) {
        val hash = jdbc.sql("SELECT password_hash FROM users WHERE id = ?").param(user.id).query(String::class.java).optional().orElse(null)
            ?: throw ApiException.notFound("user")
        if (!encoder.matches(current, hash)) throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "wrong_password", "The current password is incorrect")
        setPassword(user.id, new)
        audit.record(user.id, "user.password_changed")
    }

    fun resetPassword(actorId: String, targetId: String, new: String) {
        get(targetId) ?: throw ApiException.notFound("user")
        setPassword(targetId, new)
        audit.record(actorId, "user.password_reset", detail = mapOf("userId" to targetId))
    }

    private fun setPassword(id: String, password: String) {
        Passwords.validate(password)
        // Bumping auth_version invalidates every existing session of that user.
        jdbc.sql("UPDATE users SET password_hash = ?, password_changed_at = ?, auth_version = auth_version + 1 WHERE id = ?")
            .args(encoder.encode(password), clock.millis(), id).update()
    }

    fun setEnabled(actorId: String, targetId: String, enabled: Boolean) {
        if (actorId == targetId && !enabled) throw ApiException.conflict("cannot_disable_self", "You cannot disable your own account")
        val n = jdbc.sql("UPDATE users SET enabled = ?, auth_version = auth_version + 1 WHERE id = ?").args(if (enabled) 1 else 0, targetId).update()
        if (n == 0) throw ApiException.notFound("user")
        audit.record(actorId, if (enabled) "user.enabled" else "user.disabled", detail = mapOf("userId" to targetId))
    }

    /** Sets, changes or (with null) removes the per-user byte quota. Existing data is never touched. */
    fun setQuota(actorId: String, targetId: String, quotaBytes: Long?) {
        if (quotaBytes != null && quotaBytes < 0) throw ApiException.invalid("invalid_quota", "Quota must not be negative")
        val n = jdbc.sql("UPDATE users SET quota_bytes = ? WHERE id = ?").args(quotaBytes, targetId).update()
        if (n == 0) throw ApiException.notFound("user")
        audit.record(actorId, "user.quota_changed", detail = mapOf("userId" to targetId, "quota" to (quotaBytes?.toString() ?: "none")))
    }

    fun quotaOf(userId: String): Long? =
        jdbc.sql("SELECT quota_bytes FROM users WHERE id = ?").param(userId).query { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } }.optional().orElse(null)

    private fun map(rs: java.sql.ResultSet, n: Int) = UserRecord(
        rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getInt(4) == 1,
        rs.getLong(5).takeUnless { rs.wasNull() }, rs.getLong(6),
    )
}
