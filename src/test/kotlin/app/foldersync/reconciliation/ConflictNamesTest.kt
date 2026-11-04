package app.foldersync.reconciliation

import app.foldersync.policy.PortablePath
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConflictNamesTest {
    private val day = 1_762_214_400_000L // 2025-11-04T00:00:00Z

    @Test
    fun `name is deterministic and keeps directory and extension`() {
        val a = ConflictNames.sibling("docs/plan.final.md", "Laptop", day, emptyList())
        assertEquals("docs/plan.final (conflict Laptop 2025-11-04).md", a)
        assertEquals(a, ConflictNames.sibling("docs/plan.final.md", "Laptop", day, emptyList()))
    }

    @Test
    fun `dotfiles keep their name as the stem and missing labels are neutral`() {
        assertEquals(".env (conflict other device 2025-11-04)", ConflictNames.sibling(".env", null, day, emptyList()))
        assertEquals("x (conflict other device 2025-11-04)", ConflictNames.sibling("x", "  ", day, emptyList()))
    }

    @Test
    fun `unsafe label characters are removed`() {
        val s = ConflictNames.sibling("a.txt", "../Bob's <PC>: \"1\"", day, emptyList())!!
        assertTrue(PortablePath.isValid(s), s)
        assertTrue(!s.contains("<") && !s.contains(":") && s.count { it == '/' } == 0, s)
    }

    @Test
    fun `taken names including case and unicode lookalikes are skipped`() {
        val first = ConflictNames.sibling("a.txt", "PC", day, emptyList())!!
        val second = ConflictNames.sibling("a.txt", "PC", day, listOf(first.uppercase()))!!
        assertNotEquals(first, second)
        assertEquals("a (conflict PC 2025-11-04 2).txt", second)
        val third = ConflictNames.sibling("a.txt", "PC", day, listOf(first, second))
        assertNotNull(third)
        assertEquals("a (conflict PC 2025-11-04 3).txt", third)
    }

    @Test
    fun `long names are shortened to fit a valid segment`() {
        val long = "x".repeat(250) + ".txt"
        val s = ConflictNames.sibling("dir/$long", "Laptop", day, emptyList())!!
        assertTrue(PortablePath.isValid(s), s)
        assertTrue(s.endsWith(" (conflict Laptop 2025-11-04).txt"))
        val multibyte = "é".repeat(200) + ".txt"
        assertTrue(PortablePath.isValid(ConflictNames.sibling(multibyte, "Laptop", day, emptyList())!!))
    }
}
