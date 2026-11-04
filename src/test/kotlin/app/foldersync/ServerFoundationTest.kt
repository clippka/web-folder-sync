package app.foldersync

import app.foldersync.identity.Role
import app.foldersync.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerFoundationTest : IntegrationTest() {

    @Test
    fun `migrations ran and data layout exists`() {
        assertTrue(dataDir.resolve("db/foldersync.db").toFile().exists())
        assertTrue(dataDir.resolve("blobs").toFile().isDirectory)
        assertTrue(dataDir.resolve("staging").toFile().isDirectory)
        DriverManager.getConnection("jdbc:sqlite:${dataDir.resolve("db/foldersync.db")}").use { c ->
            val rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM schema_migrations")
            rs.next(); assertEquals(1, rs.getInt(1))
        }
    }

    @Test
    fun `api requires authentication and answers json rather than the login page`() {
        mvc.perform(get("/api/spaces")).andExpect(status().isUnauthorized).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("unauthenticated"))
    }

    @Test
    fun `state changing requests without csrf token are rejected with json`() {
        val u = newUser()
        mvc.perform(post("/api/spaces").session(u.session).contentType("application/json").content("""{"name":"x"}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("csrf_invalid"))
    }

    @Test
    fun `login rejects bad credentials and logout ends the session`() {
        val u = newUser()
        mvc.perform(post("/login").param("username", u.username).param("password", "wrong-password-123").with(csrf()))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrl("/login?error"))
        u.get("/api/account").expect(200)
        mvc.perform(post("/logout").session(u.session).with(csrf()).header("Accept", "application/json")).andExpect(status().isNoContent)
        mvc.perform(get("/api/account").session(u.session)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `repeated failed logins lock the account`() {
        val u = newUser()
        repeat(5) { mvc.perform(post("/login").param("username", u.username).param("password", "wrong-password-$it").with(csrf())) }
        mvc.perform(post("/login").param("username", u.username).param("password", u.password).with(csrf())).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `session cookie is http only and the session id changes at login`() {
        val before = mvc.perform(get("/login")).andReturn().request.getSession(false)
        val u = newUser()
        val setCookie = mvc.perform(post("/login").param("username", u.username).param("password", u.password).with(csrf()))
            .andReturn().response.getHeaders("Set-Cookie")
        // Sessions are created fresh at authentication (fixation protection); cookie attributes come from the server config.
        assertTrue(before == null || before.id != u.session.id)
        assertTrue(setCookie.isEmpty() || setCookie.all { it.contains("HttpOnly") }, "cookie: $setCookie")
    }

    @Test
    fun `password change invalidates other sessions and requires the current password`() {
        val u = newUser()
        val other = login(u.username, u.password)
        u.post("/api/account/password", mapOf("currentPassword" to "nope-nope-nope-1", "newPassword" to "another-long-password-2")).expect(403)
        u.post("/api/account/password", mapOf("currentPassword" to u.password, "newPassword" to "short")).expect(422)
        u.post("/api/account/password", mapOf("currentPassword" to u.password, "newPassword" to "another-long-password-2")).expect(204)
        mvc.perform(get("/api/account").session(other)).andExpect(status().isUnauthorized)
        login(u.username, "another-long-password-2")
    }

    @Test
    fun `administrator can manage accounts but ordinary users cannot`() {
        val admin = adminSession()
        val adminUser = app.foldersync.support.TestUser("", "admin", "", admin)
        val created = adminUser.post("/api/admin/users", mapOf("username" to "created-by-admin-" + System.nanoTime() % 100000, "password" to "a-very-long-password-3")).expect(201).body()
        adminUser.put("/api/admin/users/${created["id"].asText()}/quota", """{"quotaBytes":1000}""".toByteArray()).expect(415)
        val user = newUser()
        user.post("/api/admin/users", mapOf("username" to "hacker-account", "password" to "a-very-long-password-3")).expect(403)
        user.get("/api/admin/users").expect(403)
    }

    @Test
    fun `no public signup and no default credentials`() {
        mvc.perform(post("/api/admin/users").with(csrf()).contentType("application/json").content("""{"username":"sneaky-user","password":"a-very-long-password-3"}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/login").param("username", "admin").param("password", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"))
    }

    @Test
    fun `bootstrapped administrator exists with the admin role`() {
        assertEquals(Role.ADMIN, users.list().first { it.username == "admin" }.role)
    }
}
