package app.foldersync.policy

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IgnoreConformanceTest {
    private val fixtures: JsonNode = ObjectMapper().readTree(File("conformance/ignore-fixtures.json"))

    @TestFactory
    fun `shared conformance fixtures`(): List<DynamicTest> =
        fixtures["cases"].toList().map { case ->
            DynamicTest.dynamicTest(case["name"].asString()) {
                val files = case["rules"].properties().associate { (dir, f) ->
                    dir to IgnorePolicy.DirectoryRuleFiles(f[".gitignore"]?.asString(), f[".syncignore"]?.asString())
                }
                val policy = IgnorePolicy(files)
                for (e in case["expect"].toList()) {
                    val d = policy.evaluate(e["path"].asString(), e["dir"].asBoolean())
                    assertEquals(e["ignored"].asBoolean(), d.ignored, "${case["name"].asString()}: ${e["path"].asString()}")
                    val expectedRule = if (e["rule"].isNull) null else e["rule"].asString()
                    val actualRule = when {
                        !d.ignored -> null
                        d.builtin -> "builtin:.git"
                        else -> "${d.rule!!.sourceFile}:${d.rule!!.line}"
                    }
                    assertEquals(expectedRule, actualRule, "${case["name"].asString()}: ${e["path"].asString()} rule")
                }
            }
        }

    @Test
    fun `explanation names the file rule and matched ancestor`() {
        val policy = IgnorePolicy(mapOf("pkg" to IgnorePolicy.DirectoryRuleFiles(syncignore = "dist/\n")))
        val d = policy.evaluate("pkg/dist/a/b.js", false)
        assertTrue(d.ignored)
        assertEquals("pkg/.syncignore", d.rule!!.sourceFile)
        assertEquals("dist/", d.rule!!.text)
        assertEquals("pkg", d.ruleDirectory)
        assertEquals("pkg/dist", d.matchedPath)
    }

    @Test
    fun `no hidden dependency or build directory exclusions`() {
        val policy = IgnorePolicy()
        for (p in listOf("node_modules/x.js", "build/out", "target/a", "dist/b", ".idea/w.xml")) {
            assertFalse(policy.evaluate(p, false).ignored, p)
        }
        assertNotNull(policy.evaluate("a/.git/HEAD", false).takeIf { it.ignored })
    }
}
