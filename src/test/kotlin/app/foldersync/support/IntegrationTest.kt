package app.foldersync.support

import app.foldersync.identity.Role
import app.foldersync.identity.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as rb
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A clock tests can move forward to exercise expiry without sleeping. */
class MutableClock(start: Long = System.currentTimeMillis()) : Clock() {
    private val now = AtomicLong(start)
    fun advanceMillis(ms: Long) { now.addAndGet(ms) }
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = Instant.ofEpochMilli(now.get())
    override fun millis() = now.get()
}

@TestConfiguration
class TestClockConfig {
    @Bean @Primary
    fun testClock(): Clock = MutableClock()
}

const val ADMIN_PASSWORD = "admin-test-password-1"

/**
 * One Spring context (and one fresh data directory) per test JVM. Tests isolate themselves by creating their own
 * users, so they can run in any order against the shared database.
 */
@SpringBootTest(
    properties = [
        "foldersync.cleanup-interval=PT24H", "foldersync.chunk-size-bytes=1024", "foldersync.min-free-disk-bytes=0",
        "foldersync.orphan-file-grace=PT1H", "server.servlet.session.cookie.secure=false",
    ],
)
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestClockConfig::class)
abstract class IntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var users: UserService
    @Autowired lateinit var clock: Clock

    companion object {
        val dataDir: Path = Files.createTempDirectory("foldersync-it-")
        private val secret: Path = Files.createTempFile("foldersync-admin-", ".secret").also { Files.writeString(it, ADMIN_PASSWORD) }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("foldersync.data-dir") { dataDir.toString() }
            r.add("foldersync.admin.username") { "admin" }
            r.add("foldersync.admin.password-file") { secret.toString() }
        }
    }

    val mc: MutableClock get() = clock as MutableClock

    fun newUser(role: Role = Role.USER): TestUser {
        val name = "user" + UUID.randomUUID().toString().replace("-", "").take(12)
        val pw = "correct-horse-battery-1"
        val u = users.create(null, name, pw, role)
        return TestUser(u.id, name, pw, login(name, pw))
    }

    fun login(username: String, password: String): MockHttpSession {
        val result = mvc.perform(rb.post("/login").param("username", username).param("password", password).with(csrf())).andReturn()
        check(result.response.status == 302 && result.response.redirectedUrl?.contains("error") != true) { "login failed for $username: ${result.response.status} ${result.response.redirectedUrl}" }
        return result.request.getSession(false) as MockHttpSession
    }

    fun adminSession(): MockHttpSession = login("admin", ADMIN_PASSWORD)

    // ---- request helpers: JSON in/out with session and CSRF ----------------------------------------------------

    fun TestUser.get(url: String, vararg params: Pair<String, String>): ResultActions =
        mvc.perform(rb.get(url).session(session).also { b -> params.forEach { b.param(it.first, it.second) } })

    fun TestUser.post(url: String, body: Any? = null, key: String? = null): ResultActions =
        mvc.perform(rb.post(url).session(session).with(csrf()).apply {
            if (body != null) contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
            if (key != null) header("Idempotency-Key", key)
        })

    fun TestUser.put(url: String, body: ByteArray): ResultActions =
        mvc.perform(rb.put(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body))

    fun TestUser.delete(url: String): ResultActions = mvc.perform(rb.delete(url).session(session).with(csrf()))

    fun ResultActions.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    /** Array elements as a Kotlin list (JsonNode's own Iterable confuses type inference for map/filter chains). */
    fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

    fun ResultActions.code(): String = body()["code"].asText()

    fun ResultActions.expect(status: Int): ResultActions = andExpect(status().`is`(status))

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

class TestUser(val id: String, val username: String, val password: String, var session: MockHttpSession)
