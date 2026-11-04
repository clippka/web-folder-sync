package app.foldersync.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortablePathTest {
    @Test
    fun `accepts ordinary source paths`() {
        for (p in listOf("a", "src/main/App.kt", "docs/ünï.md", "dir with space/f.txt", ".gitignore", "a.b/c.d")) {
            assertNull(PortablePath.validate(p), p)
        }
    }

    @Test
    fun `rejects invalid paths with a specific error`() {
        val cases = mapOf(
            "" to PathError.EMPTY,
            "/abs" to PathError.LEADING_OR_TRAILING_SLASH,
            "a/" to PathError.LEADING_OR_TRAILING_SLASH,
            "a//b" to PathError.EMPTY_SEGMENT,
            "a/./b" to PathError.DOT_SEGMENT,
            "../x" to PathError.TRAVERSAL,
            "a/../b" to PathError.TRAVERSAL,
            "a\\b" to PathError.BACKSLASH,
            "a\u0000b" to PathError.CONTROL_CHARACTER,
            "a\nb" to PathError.CONTROL_CHARACTER,
            "a:b" to PathError.RESERVED_CHARACTER,
            "what?" to PathError.RESERVED_CHARACTER,
            "con" to PathError.RESERVED_NAME,
            "dir/NUL.txt" to PathError.RESERVED_NAME,
            "com1.log" to PathError.RESERVED_NAME,
            "name." to PathError.TRAILING_DOT_OR_SPACE,
            "name " to PathError.TRAILING_DOT_OR_SPACE,
            "bad\uD800x" to PathError.INVALID_UNICODE,
        )
        for ((path, err) in cases) assertEquals(err, PortablePath.validate(path), path.replace("\u0000", "\\0"))
    }

    @Test
    fun `enforces length and depth limits`() {
        assertEquals(PathError.SEGMENT_TOO_LONG, PortablePath.validate("x".repeat(256)))
        assertNull(PortablePath.validate("x".repeat(255)))
        assertEquals(PathError.TOO_DEEP, PortablePath.validate((1..65).joinToString("/") { "d" }))
        assertEquals(PathError.PATH_TOO_LONG, PortablePath.validate((1..5).joinToString("/") { "y".repeat(250) }))
    }

    @Test
    fun `detects case and unicode normalization collisions`() {
        val composed = "café.txt"
        val decomposed = "café.txt"
        val groups = PortablePath.findCollisions(listOf("README.md", "readme.md", composed, decomposed, "other.txt", "Dir/a", "dir/A"))
        assertEquals(
            listOf(listOf(composed, decomposed).sorted(), listOf("Dir/a", "dir/A"), listOf("README.md", "readme.md")).sortedBy { it.first() },
            groups,
        )
    }
}
