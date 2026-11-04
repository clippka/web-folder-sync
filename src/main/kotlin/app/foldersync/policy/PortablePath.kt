package app.foldersync.policy

import java.text.Normalizer
import java.util.Locale

enum class PathError {
    EMPTY, LEADING_OR_TRAILING_SLASH, EMPTY_SEGMENT, DOT_SEGMENT, TRAVERSAL, BACKSLASH,
    CONTROL_CHARACTER, RESERVED_CHARACTER, RESERVED_NAME, TRAILING_DOT_OR_SPACE,
    SEGMENT_TOO_LONG, PATH_TOO_LONG, TOO_DEEP, INVALID_UNICODE,
}

data class PathLimits(
    val maxDepth: Int = 64,
    val maxSegmentBytes: Int = 255,
    val maxPathBytes: Int = 1024,
)

/**
 * Canonical portable relative paths: '/'-separated, no leading slash, valid on Windows, Linux and macOS.
 * Anything that cannot be represented identically everywhere is rejected before transfer.
 */
object PortablePath {
    private val reservedChars = setOf('<', '>', ':', '"', '|', '?', '*')
    private val reservedNames: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }

    /** Returns null when [path] is valid, otherwise the first problem found. */
    fun validate(path: String, limits: PathLimits = PathLimits()): PathError? {
        if (path.isEmpty()) return PathError.EMPTY
        if (path.startsWith("/") || path.endsWith("/")) return PathError.LEADING_OR_TRAILING_SLASH
        if (path.contains('\\')) return PathError.BACKSLASH
        if (hasLoneSurrogate(path)) return PathError.INVALID_UNICODE
        if (path.toByteArray(Charsets.UTF_8).size > limits.maxPathBytes) return PathError.PATH_TOO_LONG
        val segments = path.split('/')
        if (segments.size > limits.maxDepth) return PathError.TOO_DEEP
        for (seg in segments) {
            segmentError(seg, limits)?.let { return it }
        }
        return null
    }

    fun isValid(path: String, limits: PathLimits = PathLimits()): Boolean = validate(path, limits) == null

    private fun segmentError(seg: String, limits: PathLimits): PathError? {
        if (seg.isEmpty()) return PathError.EMPTY_SEGMENT
        if (seg == ".") return PathError.DOT_SEGMENT
        if (seg == "..") return PathError.TRAVERSAL
        if (seg.any { it.code < 0x20 || it.code == 0x7f }) return PathError.CONTROL_CHARACTER
        if (seg.any { it in reservedChars }) return PathError.RESERVED_CHARACTER
        if (seg.last() == '.' || seg.last() == ' ') return PathError.TRAILING_DOT_OR_SPACE
        if (seg.toByteArray(Charsets.UTF_8).size > limits.maxSegmentBytes) return PathError.SEGMENT_TOO_LONG
        val stem = seg.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT)
        if (stem in reservedNames) return PathError.RESERVED_NAME
        return null
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return true
                i++
            }
        }
        return false
    }

    /** Key under which two paths collide on a case-insensitive or normalization-insensitive file system. */
    fun collisionKey(path: String): String =
        path.split('/').joinToString("/") {
            Normalizer.normalize(Normalizer.normalize(it, Normalizer.Form.NFD).lowercase(Locale.ROOT), Normalizer.Form.NFC)
                .let { s -> Normalizer.normalize(s, Normalizer.Form.NFC) }
        }

    /** Groups of distinct paths that cannot coexist on all supported platforms. Each group has 2+ members. */
    fun findCollisions(paths: Collection<String>): List<List<String>> =
        paths.distinct().groupBy { collisionKey(it) }.values
            .filter { it.size > 1 }
            .map { it.sorted() }
            .sortedBy { it.first() }
}
