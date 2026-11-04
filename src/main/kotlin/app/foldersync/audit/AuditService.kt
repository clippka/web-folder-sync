package app.foldersync.audit

import app.foldersync.common.args
import app.foldersync.policy.PortablePath
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Metadata-only audit trail. Detail values are short strings; a relative path is accepted only if it is a valid
 * portable path, so absolute local paths cannot be recorded. Contents, passwords and session credentials never
 * reach this class.
 */
@Service
class AuditService(private val jdbc: JdbcClient, private val clock: Clock) {

    fun record(actorUserId: String?, action: String, spaceId: String? = null, detail: Map<String, String> = emptyMap()) {
        val cleaned = detail.entries.joinToString(";") { (k, v) -> "${sanitizeKey(k)}=${sanitizeValue(k, v)}" }
        jdbc.sql("INSERT INTO audit_log (ts, actor_user_id, action, space_id, detail) VALUES (?,?,?,?,?)")
            .args(clock.millis(), actorUserId, action, spaceId, cleaned.ifEmpty { null })
            .update()
    }

    data class Entry(val id: Long, val ts: Long, val actorUserId: String?, val action: String, val spaceId: String?, val detail: String?)

    fun recent(spaceId: String, limit: Int = 100): List<Entry> =
        jdbc.sql("SELECT id, ts, actor_user_id, action, space_id, detail FROM audit_log WHERE space_id = ? ORDER BY id DESC LIMIT ?")
            .args(spaceId, limit.coerceIn(1, 500))
            .query { rs, _ -> Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }
            .list()

    private fun sanitizeKey(k: String) = k.filter { it.isLetterOrDigit() || it == '_' }.take(32)

    private fun sanitizeValue(key: String, v: String): String {
        if (key == "path") return if (PortablePath.isValid(v)) v else "[invalid-path]"
        return v.filter { it.code >= 0x20 && it != ';' && it != '=' }.take(120)
    }
}
