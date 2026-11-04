package app.foldersync

import app.foldersync.support.IntegrationTest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as rb
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpacePagesTest : IntegrationTest() {

    @Test
    fun `space pages need a session and send anonymous visitors to the login page`() {
        mvc.perform(rb.get("/spaces")).andExpect(status().is3xxRedirection).andExpect(header().string("Location", containsString("/login")))
    }

    @Test
    fun `an owner creates a space from the form and sees its panel with the coordinator outside the swap target`() {
        val u = newUser()
        val created = mvc.perform(rb.post("/spaces").param("name", "Projects <b>x</b>").session(u.session).with(csrf())).andReturn()
        assertEquals(302, created.response.status)
        val location = created.response.redirectedUrl!!
        assertTrue(location.startsWith("/spaces/"))

        val page = mvc.perform(rb.get(location).session(u.session))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""id="space-panel"""")))
            .andExpect(content().string(containsString("""data-action="sync"""")))
            .andExpect(content().string(containsString("""name="foldersync-user" content="${u.id}"""")))
            .andExpect(content().string(containsString("/app/coordinator.js")))
            .andReturn().response.contentAsString
        // The space name is escaped, not interpreted.
        assertTrue(page.contains("Projects &lt;b&gt;x&lt;/b&gt;"))
        assertTrue(!page.contains("<b>x</b>"))
        // The coordinator script is not inside the swap target, which is the only element that hx-select/hx-target touch.
        assertTrue(page.indexOf("/app/coordinator.js") > page.indexOf("</main>"))

        mvc.perform(rb.get("/spaces").session(u.session)).andExpect(status().isOk).andExpect(content().string(containsString("Projects &lt;b&gt;x&lt;/b&gt;")))
    }

    @Test
    fun `another user's space page answers 404 exactly like a missing one`() {
        val owner = newUser()
        val other = newUser()
        val id = owner.post("/api/spaces", mapOf("name" to "private")).expect(201).body()["id"].asText()
        mvc.perform(rb.get("/spaces/$id").session(other.session)).andExpect(status().isNotFound)
        mvc.perform(rb.get("/spaces/does-not-exist").session(other.session)).andExpect(status().isNotFound)
        mvc.perform(rb.get("/spaces/$id").session(adminSession())).andExpect(status().isNotFound)
    }

    @Test
    fun `a page never lists someone else's space`() {
        val owner = newUser()
        val other = newUser()
        owner.post("/api/spaces", mapOf("name" to "secret-plans")).expect(201)
        mvc.perform(rb.get("/spaces").session(other.session)).andExpect(status().isOk).andExpect(content().string(not(containsString("secret-plans"))))
    }

    @Test
    fun `creating a space by form needs a CSRF token`() {
        val u = newUser()
        mvc.perform(rb.post("/spaces").param("name", "nope").session(u.session)).andExpect(status().isForbidden)
    }

    @Test
    fun `the space API tells the browser the limits it must plan with`() {
        val u = newUser()
        val space = u.post("/api/spaces", mapOf("name" to "limits")).expect(201).body()
        val limits = space["limits"]
        assertEquals(104_857_600L, limits["maxFileSizeBytes"].asLong())
        assertEquals(25_000, limits["maxIncludedFiles"].asInt())
        assertEquals(1024, limits["chunkSizeBytes"].asInt(), "test profile chunk size")
        assertTrue(limits["maxManifestBatch"].asInt() > 0)
    }
}
