package app.foldersync.reconciliation

import app.foldersync.policy.PathLimits
import app.foldersync.policy.PortablePath
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Deterministic sibling names for "Keep both". The name is derived only from the original path, the device label, the
 * conflict's recorded time and the set of paths already taken, so repeating the computation on the same state yields
 * the same name. Every result is a valid portable path that does not collide (case- or normalization-insensitively)
 * with an existing live path.
 */
object ConflictNames {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private const val MAX_ATTEMPTS = 1000

    fun sibling(path: String, deviceLabel: String?, createdAtMillis: Long, taken: Collection<String>, limits: PathLimits = PathLimits()): String? {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val label = cleanLabel(deviceLabel)
        val date = dateFormat.format(Instant.ofEpochMilli(createdAtMillis))
        val occupied = taken.mapTo(HashSet()) { PortablePath.collisionKey(it) }
        for (n in 1..MAX_ATTEMPTS) {
            val tag = " (conflict $label $date${if (n > 1) " $n" else ""})"
            val candidate = build(dir, stem, tag, ext, limits) ?: return null
            if (PortablePath.collisionKey(candidate) !in occupied && PortablePath.isValid(candidate, limits)) return candidate
        }
        return null
    }

    private fun cleanLabel(label: String?): String {
        val kept = (label ?: "").filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().replace(Regex(" +"), " ")
        return kept.take(30).trim().ifEmpty { "other device" }
    }

    /** Shortens the stem (never the tag or extension) until the file name fits the segment limit. */
    private fun build(dir: String, stem: String, tag: String, ext: String, limits: PathLimits): String? {
        var s = stem
        fun bytes(x: String) = x.toByteArray(Charsets.UTF_8).size
        while (s.isNotEmpty() && bytes(s + tag + ext) > limits.maxSegmentBytes) s = s.substring(0, s.offsetByCodePoints(s.length, -1))
        s = s.trimEnd(' ', '.')
        if (s.isEmpty() || bytes(s + tag + ext) > limits.maxSegmentBytes) return null
        val file = s + tag + ext
        return if (dir.isEmpty()) file else "$dir/$file"
    }
}
