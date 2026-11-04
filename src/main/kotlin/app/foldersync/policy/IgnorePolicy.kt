package app.foldersync.policy

/** One parsed gitignore line. [text] is the original line, [line] is 1-based. */
class IgnoreRule internal constructor(
    val sourceFile: String,
    val line: Int,
    val text: String,
    val negated: Boolean,
    private val dirOnly: Boolean,
    private val basenameOnly: Boolean,
    private val regex: Regex,
) {
    /** [relative] is the path relative to the directory holding the rule file. */
    fun matches(relative: String, isDirectory: Boolean): Boolean {
        if (dirOnly && !isDirectory) return false
        val target = if (basenameOnly) relative.substringAfterLast('/') else relative
        return regex.matches(target)
    }

    companion object {
        fun parseFile(sourceFile: String, content: String): List<IgnoreRule> =
            content.split('\n').mapIndexedNotNull { idx, raw -> parseLine(sourceFile, idx + 1, raw.removeSuffix("\r")) }

        fun parseLine(sourceFile: String, lineNo: Int, raw: String): IgnoreRule? {
            var p = raw
            if (p.startsWith("#")) return null
            while (p.endsWith(" ") && !p.endsWith("\\ ")) p = p.dropLast(1)
            if (p.isEmpty()) return null
            val negated = p.startsWith("!")
            if (negated) p = p.drop(1)
            val dirOnly = p.endsWith("/")
            if (dirOnly) p = p.dropLast(1)
            if (p.isEmpty()) return null
            val anchored = p.contains('/')
            if (p.startsWith("/")) p = p.drop(1)
            if (p.isEmpty()) return null
            return IgnoreRule(sourceFile, lineNo, raw, negated, dirOnly, !anchored, Regex(globToRegex(p)))
        }

        private fun globToRegex(p: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> {
                        if (i + 1 < p.length) { sb.append(lit(p[i + 1])); i += 2 } else { sb.append(lit('\\')); i++ }
                    }
                    c == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                        val atStart = i == 0 || p[i - 1] == '/'
                        val end = i + 2
                        when {
                            atStart && end == p.length -> {
                                sb.append(if (i == 0) ".*" else ".+"); i = end
                            }
                            atStart && p[end] == '/' -> { sb.append("(?:.*/)?"); i = end + 1 }
                            else -> { sb.append("[^/]*"); i = end }
                        }
                    }
                    c == '*' -> { sb.append("[^/]*"); i++ }
                    c == '?' -> { sb.append("[^/]"); i++ }
                    c == '[' -> {
                        val close = findClassEnd(p, i)
                        if (close < 0) { sb.append(lit('[')); i++ } else { sb.append(charClass(p.substring(i + 1, close))); i = close + 1 }
                    }
                    else -> { sb.append(lit(c)); i++ }
                }
            }
            return sb.toString()
        }

        private fun findClassEnd(p: String, open: Int): Int {
            var j = open + 1
            if (j < p.length && (p[j] == '!' || p[j] == '^')) j++
            if (j < p.length && p[j] == ']') j++
            while (j < p.length) {
                if (p[j] == '\\') { j += 2; continue }
                if (p[j] == ']') return j
                j++
            }
            return -1
        }

        private fun charClass(body: String): String {
            var b = body
            val neg = b.startsWith("!") || b.startsWith("^")
            if (neg) b = b.drop(1)
            val sb = StringBuilder(if (neg) "[^/" else "[")
            var k = 0
            while (k < b.length) {
                val ch = b[k]
                when {
                    ch == '\\' && k + 1 < b.length -> { sb.append(classLit(b[k + 1])); k += 2 }
                    ch == '-' && k > 0 && k < b.length - 1 -> { sb.append('-'); k++ }
                    else -> { sb.append(classLit(ch)); k++ }
                }
            }
            if (!neg) sb.append("&&[^/]")
            return sb.append(']').toString()
        }

        private fun classLit(c: Char): String = if (c.isLetterOrDigit() || c.code > 127) c.toString() else "\\$c"
        private fun lit(c: Char): String = if (c.isLetterOrDigit() || c.code > 127) c.toString() else "\\$c"
    }
}

/** Why a path is, or is not, excluded. [rule] is null for the built-in `.git` exclusion. */
data class IgnoreDecision(
    val ignored: Boolean,
    val rule: IgnoreRule? = null,
    /** Directory whose rule file contains [rule]; "" for the root. */
    val ruleDirectory: String? = null,
    /** Path (file or ancestor directory) the rule matched. */
    val matchedPath: String? = null,
    val builtin: Boolean = false,
) {
    companion object {
        val INCLUDED = IgnoreDecision(false)
    }
}

/**
 * Effective ignore policy built from the `.gitignore` / `.syncignore` files found in traversable directories.
 * Evaluation order is root to leaf; at each level `.gitignore` then `.syncignore`; the last matching rule wins,
 * so deeper levels and `.syncignore` override. Global Git configuration and `.git/info/exclude` are not read.
 */
class IgnorePolicy(ruleFiles: Map<String, DirectoryRuleFiles> = emptyMap()) {
    data class DirectoryRuleFiles(val gitignore: String? = null, val syncignore: String? = null)

    private val rulesByDir: Map<String, List<IgnoreRule>> = ruleFiles.mapValues { (dir, f) ->
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        buildList {
            f.gitignore?.let { addAll(IgnoreRule.parseFile("${prefix}.gitignore", it)) }
            f.syncignore?.let { addAll(IgnoreRule.parseFile("${prefix}.syncignore", it)) }
        }
    }

    fun evaluate(path: String, isDirectory: Boolean): IgnoreDecision {
        val segments = path.split('/')
        if (segments.any { it == GIT_DIR }) return IgnoreDecision(ignored = true, builtin = true, matchedPath = path)
        for (i in 1..segments.size) {
            val prefix = segments.take(i).joinToString("/")
            val isDir = i < segments.size || isDirectory
            val hit = lastMatch(prefix, isDir)
            if (hit != null && !hit.second.negated) {
                val isSelf = i == segments.size
                if (isSelf && segments.last() in CONTROL_FILE_NAMES) return IgnoreDecision.INCLUDED
                return IgnoreDecision(true, hit.second, hit.first, prefix)
            }
        }
        return IgnoreDecision.INCLUDED
    }

    private fun lastMatch(path: String, isDir: Boolean): Pair<String, IgnoreRule>? {
        val parent = path.substringBeforeLast('/', "")
        val dirs = ArrayList<String>()
        dirs.add("")
        if (parent.isNotEmpty()) {
            var acc = ""
            for (s in parent.split('/')) { acc = if (acc.isEmpty()) s else "$acc/$s"; dirs.add(acc) }
        }
        var result: Pair<String, IgnoreRule>? = null
        for (d in dirs) {
            val rules = rulesByDir[d] ?: continue
            val rel = if (d.isEmpty()) path else path.removePrefix("$d/")
            for (r in rules) if (r.matches(rel, isDir)) result = d to r
        }
        return result
    }

    companion object {
        const val GIT_DIR = ".git"
        val CONTROL_FILE_NAMES = setOf(".gitignore", ".syncignore")
        fun isControlFile(path: String): Boolean = path.substringAfterLast('/') in CONTROL_FILE_NAMES
    }
}
