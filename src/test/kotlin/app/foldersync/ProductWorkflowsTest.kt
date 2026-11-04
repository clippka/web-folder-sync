package app.foldersync

import app.foldersync.identity.Role
import app.foldersync.support.Local
import app.foldersync.support.SyncTest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as rb
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Step 9: account, device, administrator and insight workflows, and the security regressions around them. */
class ProductWorkflowsTest : SyncTest() {

    // ---- password and account ------------------------------------------------------------------------------------

    @Test
    fun `changing the password through the form ends the session and the old password stops working`() {
        val u = newUser()
        mvc.perform(rb.get("/account").session(u.session)).andExpect(status().isOk).andExpect(content().string(containsString("Change password")))

        val wrong = mvc.perform(rb.post("/account/password").session(u.session).with(csrf())
            .param("currentPassword", "not-the-password-1").param("newPassword", "another-long-password-2").param("confirmPassword", "another-long-password-2")).andReturn()
        assertEquals("/account", wrong.response.redirectedUrl)
        assertTrue(wrong.flashMap.containsKey("error"))

        val mismatch = mvc.perform(rb.post("/account/password").session(u.session).with(csrf())
            .param("currentPassword", u.password).param("newPassword", "another-long-password-2").param("confirmPassword", "different-long-password-3")).andReturn()
        assertTrue(mismatch.flashMap.containsKey("error"))

        val ok = mvc.perform(rb.post("/account/password").session(u.session).with(csrf())
            .param("currentPassword", u.password).param("newPassword", "another-long-password-2").param("confirmPassword", "another-long-password-2")).andReturn()
        assertEquals("/login?changed", ok.response.redirectedUrl)

        // The old password is refused; the new one works.
        val old = mvc.perform(rb.post("/login").param("username", u.username).param("password", u.password).with(csrf())).andReturn()
        assertTrue(old.response.redirectedUrl!!.contains("error"))
        login(u.username, "another-long-password-2")
    }

    @Test
    fun `a weak new password is refused with a message and nothing changes`() {
        val u = newUser()
        val r = mvc.perform(rb.post("/account/password").session(u.session).with(csrf())
            .param("currentPassword", u.password).param("newPassword", "short").param("confirmPassword", "short")).andReturn()
        assertEquals("/account", r.response.redirectedUrl)
        assertTrue(r.flashMap.containsKey("error"))
        login(u.username, u.password)
    }

    @Test
    fun `the usage endpoint reports limits and no quota by default`() {
        val u = newUser()
        val body = u.get("/api/account/usage").expect(200).body()
        assertTrue(body["quotaBytes"].isNull)
        assertEquals(104_857_600L, body["maxFileSizeBytes"].asLong())
    }

    // ---- devices -------------------------------------------------------------------------------------------------

    @Test
    fun `an owner revokes a device from the page and its work stops`() {
        val ctx = setup()
        mvc.perform(rb.get("/devices").session(ctx.user.session)).andExpect(status().isOk).andExpect(content().string(containsString("Test laptop")))
        val r = mvc.perform(rb.post("/devices/${ctx.deviceId}/revoke").session(ctx.user.session).with(csrf())).andReturn()
        assertEquals("/devices", r.response.redirectedUrl)
        assertTrue(r.flashMap.containsKey("notice"))
        // The revoked device can no longer plan.
        createPlan(ctx, mapOf("a.txt" to Local.File("a".toByteArray()))).expect(409)
    }

    @Test
    fun `revoking someone else's device through the page changes nothing`() {
        val victim = setup()
        val attacker = newUser()
        val r = mvc.perform(rb.post("/devices/${victim.deviceId}/revoke").session(attacker.session).with(csrf())).andReturn()
        assertTrue(r.flashMap.containsKey("error"))
        val plan = sync(victim, mapOf("a.txt" to Local.File("a".toByteArray())))
        assertEquals(200, plan.commit!!.andReturn().response.status)
    }

    // ---- administrator -------------------------------------------------------------------------------------------

    @Test
    fun `ordinary users cannot open administrator pages or endpoints`() {
        val u = newUser()
        mvc.perform(rb.get("/admin/users").session(u.session)).andExpect(status().isForbidden)
        mvc.perform(rb.post("/admin/users").session(u.session).with(csrf()).param("username", "x-user-1").param("password", "long-enough-password")).andExpect(status().isForbidden)
        u.get("/api/admin/users").expect(403)
    }

    @Test
    fun `an administrator manages accounts through the page`() {
        val admin = adminSession()
        val page = mvc.perform(rb.get("/admin/users").session(admin)).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(page.contains("Create an account"))

        val name = "made" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val created = mvc.perform(rb.post("/admin/users").session(admin).with(csrf()).param("username", name).param("password", "initial-password-12").param("role", "USER")).andReturn()
        assertTrue(created.flashMap.containsKey("notice"), created.flashMap.toString())
        val record = users.list().first { it.username == name }

        val q = mvc.perform(rb.post("/admin/users/${record.id}/quota").session(admin).with(csrf()).param("quotaGiB", "2")).andReturn()
        assertTrue(q.flashMap.containsKey("notice"))
        assertEquals(2L * 1024 * 1024 * 1024, users.quotaOf(record.id))
        mvc.perform(rb.post("/admin/users/${record.id}/quota").session(admin).with(csrf()).param("quotaGiB", "")).andReturn()
        assertEquals(null, users.quotaOf(record.id))
        val bad = mvc.perform(rb.post("/admin/users/${record.id}/quota").session(admin).with(csrf()).param("quotaGiB", "-3")).andReturn()
        assertTrue(bad.flashMap.containsKey("error"))

        val s = login(name, "initial-password-12")
        mvc.perform(rb.post("/admin/users/${record.id}/password").session(admin).with(csrf()).param("password", "reset-password-34")).andReturn()
        // The reset ended the old session.
        mvc.perform(rb.get("/account").session(s)).andExpect(status().is3xxRedirection)
        login(name, "reset-password-34")

        mvc.perform(rb.post("/admin/users/${record.id}/enabled").session(admin).with(csrf()).param("enabled", "false")).andReturn()
        val denied = mvc.perform(rb.post("/login").param("username", name).param("password", "reset-password-34").with(csrf())).andReturn()
        assertTrue(denied.response.redirectedUrl!!.contains("error"))
    }

    @Test
    fun `an administrator cannot reach another user's space content through any new route`() {
        val ctx = setup()
        sync(ctx, mapOf(".gitignore" to Local.File("*.o\n".toByteArray())))
        val admin = adminSession()
        val id = ctx.spaceId
        for (url in listOf("/api/spaces/$id/summary", "/api/spaces/$id/activity", "/api/spaces/$id/policy", "/api/spaces/$id/policy/explain?path=a.o")) {
            mvc.perform(rb.get(url).session(admin)).andExpect(status().isNotFound)
        }
        mvc.perform(rb.post("/api/spaces/$id/policy/preview").session(admin).with(csrf()).contentType("application/json").content("""{"path":".gitignore","text":"x"}""")).andExpect(status().isNotFound)
        for (url in listOf("/spaces/$id", "/spaces/$id/rules", "/spaces/$id/activity")) {
            mvc.perform(rb.get(url).session(admin)).andExpect(status().isNotFound)
        }
        mvc.perform(rb.post("/spaces/$id/rules/preview").session(admin).with(csrf()).param("path", ".gitignore").param("text", "x")).andExpect(status().isNotFound)
        // Another ordinary user gets the same answer.
        val other = newUser()
        other.get("/api/spaces/$id/summary").expect(404)
        mvc.perform(rb.get("/spaces/$id/activity").session(other.session)).andExpect(status().isNotFound)
    }

    // ---- space summaries, rules and activity ---------------------------------------------------------------------

    @Test
    fun `the space list and summary show devices, files and conflicts`() {
        val a = setup()
        val b = secondDevice(a)
        sync(a, mapOf("x.txt" to Local.File("one".toByteArray())))
        // The second device edits the same path differently: a conflict is recorded.
        sync(b, mapOf("x.txt" to Local.File("two".toByteArray())))
        val summary = a.user.get("/api/spaces/${a.spaceId}/summary").expect(200).body()
        assertEquals(1, summary["activeFiles"].asInt())
        assertEquals(1, summary["openConflicts"].asInt())
        assertEquals(2, summary["devices"].size())
        mvc.perform(rb.get("/spaces").session(a.user.session)).andExpect(status().isOk)
            .andExpect(content().string(containsString("1 to review"))).andExpect(content().string(containsString("Second machine")))
        mvc.perform(rb.get("/spaces/${a.spaceId}").session(a.user.session)).andExpect(content().string(containsString("data-field=\"status\"")))
    }

    @Test
    fun `a path is explained with the matching rule file and line`() {
        val ctx = setup()
        sync(ctx, mapOf(".gitignore" to Local.File("# comment\n*.o\nbuild/\n".toByteArray())))
        val hit = ctx.user.get("/api/spaces/${ctx.spaceId}/policy/explain", "path" to "src/main.o").expect(200).body()
        assertTrue(hit["ignored"].asBoolean())
        assertEquals(".gitignore", hit["ruleFile"].asText())
        assertEquals(2, hit["ruleLine"].asInt())
        assertEquals("*.o", hit["ruleText"].asText())

        val inside = ctx.user.get("/api/spaces/${ctx.spaceId}/policy/explain", "path" to "build/x/y.txt").expect(200).body()
        assertTrue(inside["ignored"].asBoolean())
        assertEquals("build", inside["matchedPath"].asText())

        assertFalse(ctx.user.get("/api/spaces/${ctx.spaceId}/policy/explain", "path" to "src/main.c").expect(200).body()["ignored"].asBoolean())
        assertTrue(ctx.user.get("/api/spaces/${ctx.spaceId}/policy/explain", "path" to "a/.git/config").expect(200).body()["builtin"].asBoolean())
        ctx.user.get("/api/spaces/${ctx.spaceId}/policy/explain", "path" to "../etc/passwd").expect(422)

        mvc.perform(rb.get("/spaces/${ctx.spaceId}/rules").param("path", "src/main.o").session(ctx.user.session))
            .andExpect(status().isOk).andExpect(content().string(containsString("Excluded by line 2 of .gitignore")))
    }

    @Test
    fun `a rule change preview lists affected paths and changes nothing`() {
        val ctx = setup()
        sync(ctx, mapOf("keep.txt" to Local.File("k".toByteArray()), "notes.log" to Local.File("l".toByteArray()), "sub" to Local.Dir, "sub/a.log" to Local.File("a".toByteArray())))
        val before = manifest(ctx).keys
        val preview = ctx.user.post("/api/spaces/${ctx.spaceId}/policy/preview", mapOf("path" to ".gitignore", "text" to "*.log\n")).expect(200).body()
        assertEquals(2, preview["newlyIgnoredTotal"].asInt())
        assertEquals(setOf("notes.log", "sub/a.log"), preview["newlyIgnored"].list().map { it["path"].asText() }.toSet())
        assertEquals(0, preview["newlyIncludedTotal"].asInt())
        assertEquals(before, manifest(ctx).keys, "a preview must not alter the space")
        assertEquals(0L, policyRevision(ctx))

        ctx.user.post("/api/spaces/${ctx.spaceId}/policy/preview", mapOf("path" to "notes.txt", "text" to "x")).expect(422)
        mvc.perform(rb.post("/spaces/${ctx.spaceId}/rules/preview").session(ctx.user.session).with(csrf()).param("path", ".gitignore").param("text", "*.log\n"))
            .andExpect(status().isOk).andExpect(content().string(containsString("Would stop being synchronized (2)")))
    }

    @Test
    fun `the activity view is metadata only and escapes its text`() {
        val ctx = setup(spaceName = "Act <script>x</script>")
        sync(ctx, mapOf("secret-name.txt" to Local.File("TOP-SECRET-CONTENT".toByteArray())))
        val items = ctx.user.get("/api/spaces/${ctx.spaceId}/activity").expect(200).body().list()
        assertTrue(items.isNotEmpty())
        val json = items.joinToString { it.toString() }
        assertFalse(json.contains("TOP-SECRET-CONTENT"))
        assertFalse(json.contains(":\\\\") || json.contains("C:/"), "no absolute local path")
        mvc.perform(rb.get("/spaces/${ctx.spaceId}/activity").session(ctx.user.session)).andExpect(status().isOk)
            .andExpect(content().string(not(containsString("<script>x"))))
    }

    // ---- security regressions ------------------------------------------------------------------------------------

    @Test
    fun `every state-changing route rejects a request without a CSRF token`() {
        val ctx = setup()
        val s = ctx.user.session
        val posts = listOf(
            "/spaces", "/account/password", "/devices/${ctx.deviceId}/revoke", "/spaces/${ctx.spaceId}/rules/preview",
            "/api/spaces", "/api/devices", "/api/spaces/${ctx.spaceId}/bindings", "/api/spaces/${ctx.spaceId}/plans", "/api/account/password",
            "/api/spaces/${ctx.spaceId}/policy/preview", "/api/plans/x/entries", "/api/plans/x/commit", "/api/plans/x/acknowledgements",
            "/api/conflicts/x/resolve", "/api/uploads", "/logout-not-a-route",
        )
        for (url in posts) {
            val r = mvc.perform(rb.post(url).session(s).contentType("application/json").content("{}")).andReturn().response
            assertEquals(403, r.status, "POST $url must be refused without a token, was ${r.status}")
        }
        for (url in listOf("/api/devices/${ctx.deviceId}", "/api/bindings/${ctx.bindingId}")) {
            assertEquals(403, mvc.perform(rb.delete(url).session(s)).andReturn().response.status, "DELETE $url")
        }
        val admin = adminSession()
        for (url in listOf("/admin/users", "/admin/users/x/password", "/admin/users/x/quota", "/admin/users/x/enabled", "/api/admin/users")) {
            assertEquals(403, mvc.perform(rb.post(url).session(admin).contentType("application/json").content("{}")).andReturn().response.status, "POST $url")
        }
        // The device was not revoked by any of the refused requests.
        sync(ctx, mapOf("ok.txt" to Local.File("x".toByteArray())))
    }

    @Test
    fun `JSON routes answer 401 JSON without a session and pages redirect to sign in`() {
        for (url in listOf("/api/account/usage", "/api/spaces/x/summary", "/api/spaces/x/activity", "/api/spaces/x/policy", "/api/spaces/x/events")) {
            mvc.perform(rb.get(url)).andExpect(status().isUnauthorized).andExpect(content().string(containsString("unauthenticated")))
        }
        for (url in listOf("/account", "/devices", "/spaces/x/rules", "/spaces/x/activity")) {
            mvc.perform(rb.get(url)).andExpect(status().is3xxRedirection).andExpect(header().string("Location", containsString("/login")))
        }
        mvc.perform(rb.get("/admin/users")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `pages carry the restrictive content security policy and no sniffing`() {
        val u = newUser()
        for (url in listOf("/account", "/devices", "/spaces", "/help")) {
            mvc.perform(rb.get(url).session(u.session)).andExpect(status().isOk)
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        }
    }

    @Test
    fun `pages load only locally bundled scripts and use no inline scripts`() {
        val u = newUser()
        val ctx = setup(u)
        for (url in listOf("/spaces/${ctx.spaceId}", "/account", "/devices", "/spaces/${ctx.spaceId}/rules", "/help")) {
            val html = mvc.perform(rb.get(url).session(u.session)).andReturn().response.contentAsString
            val scripts = Regex("<script[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
            assertTrue(scripts.isNotEmpty())
            for (tag in scripts) {
                assertTrue(Regex("""src="/app/[^"]+"""").containsMatchIn(tag), "script must be a local bundle: $tag")
            }
            assertFalse(Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "no inline event handlers on $url")
        }
    }

    @Test
    fun `file and space names are escaped in the rule and space pages`() {
        val ctx = setup(spaceName = "<img src=x onerror=alert(1)>")
        sync(ctx, mapOf(".gitignore" to Local.File("<b>x</b>\n".toByteArray())))
        for (url in listOf("/spaces", "/spaces/${ctx.spaceId}", "/spaces/${ctx.spaceId}/rules")) {
            val html = mvc.perform(rb.get(url).session(ctx.user.session)).andReturn().response.contentAsString
            assertFalse(html.contains("<img src=x"), "unescaped name on $url")
            assertFalse(html.contains("<b>x</b>"), "unescaped rule text on $url")
        }
    }

    @Test
    fun `an expired or logged-out session loses access to new routes at once`() {
        val u = newUser()
        mvc.perform(rb.post("/logout").session(u.session).with(csrf())).andExpect(status().is3xxRedirection)
        mvc.perform(rb.get("/api/account/usage").session(u.session)).andExpect(status().isUnauthorized)
        mvc.perform(rb.get("/account").session(u.session)).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `the help page is public and explains the limits that matter`() {
        mvc.perform(rb.get("/help")).andExpect(status().isOk)
            .andExpect(content().string(containsString("no history, trash")))
            .andExpect(content().string(containsString("server operator can read them")))
            .andExpect(content().string(containsString("Nothing syncs on its own")))
            .andExpect(content().string(containsString("suspended")))
            .andExpect(content().string(containsString("editor saves the same file")))
    }

    @Test
    fun `an admin role is required for admin pages but grants nothing else`() {
        val adminUser = newUser(Role.ADMIN)
        mvc.perform(rb.get("/admin/users").session(adminUser.session)).andExpect(status().isOk)
        val other = setup()
        mvc.perform(rb.get("/spaces/${other.spaceId}").session(adminUser.session)).andExpect(status().isNotFound)
    }
}
