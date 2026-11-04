package app.foldersync

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(properties = ["foldersync.data-dir=build/test-data"])
@AutoConfigureMockMvc
class ShellTest(@Autowired val mvc: MockMvc) {
    @Test
    fun `shell renders with csp and loads coordinator outside swap target`() {
        mvc.get("/").andExpect {
            status { isOk() }
            header { string("Content-Security-Policy", containsString("script-src 'self'")) }
            content { string(containsString("/app/coordinator.js")) }
        }
    }

    @Test
    fun `unlisted routes are denied`() {
        mvc.get("/api/anything").andExpect { status { is4xxClientError() } }
    }
}
