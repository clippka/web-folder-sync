package app.foldersync.common

import app.foldersync.common.args
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock

/**
 * Makes retries after a lost response safe. The outcome of the first execution is stored under
 * (owner, scope, key) in the *same transaction* as the work, so a duplicate request either sees the stored outcome
 * and replays it, or finds nothing because the work never committed and runs it again. Re-using a key with a
 * different request body is an error, not a replay.
 *
 * Callers must invoke [run] inside a transaction.
 */
@Service
class IdempotencyService(private val jdbc: JdbcClient, private val mapper: JsonMapper, private val clock: Clock) {

    fun <T : Any> run(ownerId: String, scope: String, key: String?, request: Any, type: Class<T>, block: () -> T): T {
        if (key.isNullOrBlank() || key.length > 128) {
            throw ApiException.bad("idempotency_key_required", "An Idempotency-Key header of 1-128 characters is required")
        }
        val fingerprint = fingerprint(request)
        val stored = jdbc.sql("SELECT fingerprint, response FROM idempotency_keys WHERE owner_id = ? AND scope = ? AND key = ?")
            .args(ownerId, scope, key).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
        if (stored != null) {
            if (stored.first != fingerprint) {
                throw ApiException.conflict("idempotency_key_reuse", "This Idempotency-Key was already used for a different request")
            }
            return mapper.readValue(stored.second, type)
        }
        val result = block()
        jdbc.sql("INSERT INTO idempotency_keys (owner_id, scope, key, fingerprint, response, created_at) VALUES (?,?,?,?,?,?)")
            .args(ownerId, scope, key, fingerprint, mapper.writeValueAsString(result), clock.millis()).update()
        return result
    }

    private fun fingerprint(request: Any): String =
        MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(request).toByteArray()).joinToString("") { "%02x".format(it) }

    /** Removes outcomes older than [maxAgeMillis]; retries after that long are not expected. */
    fun prune(maxAgeMillis: Long): Int =
        jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < ?").param(clock.millis() - maxAgeMillis).update()
}
