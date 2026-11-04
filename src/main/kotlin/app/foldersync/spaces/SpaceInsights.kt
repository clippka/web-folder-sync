package app.foldersync.spaces

import app.foldersync.audit.AuditService
import app.foldersync.common.ApiException
import app.foldersync.common.args
import app.foldersync.config.FolderSyncProperties
import app.foldersync.policy.IgnorePolicy
import app.foldersync.policy.PortablePath
import app.foldersync.policy.SpacePolicyService
import app.foldersync.revisions.EntryStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

data class BoundDevice(val deviceId: String, val label: String, val status: BindingStatus, val lastSeen: Long?)
data class SpaceSummary(
    val spaceId: String,
    val revision: Long,
    val activeFiles: Int,
    val openConflicts: Int,
    val lastActivityAt: Long?,
    val devices: List<BoundDevice>,
)
data class ActivityItem(val id: Long, val ts: Long, val action: String, val text: String, val detail: String?)

data class RuleFile(val path: String, val text: String)
data class RuleExplanation(
    val path: String, val directory: Boolean, val ignored: Boolean, val builtin: Boolean,
    val ruleText: String?, val ruleFile: String?, val ruleLine: Int?, val matchedPath: String?, val explanation: String,
)
data class PolicyChange(val path: String, val directory: Boolean, val explanation: String?)
data class PolicyPreview(
    val newlyIgnored: List<PolicyChange>, val newlyIncluded: List<PolicyChange>,
    val newlyIgnoredTotal: Int, val newlyIncludedTotal: Int, val note: String,
)

/**
 * Read-only views over one space for its owner: the summary shown in lists, the metadata-only activity, and the ignore
 * policy with matched-rule explanations and a what-if preview. Every method checks ownership through [SpaceService],
 * so another user's id (administrators included) answers 404. Nothing here changes state or reads file content other
 * than the small ignore files, which are the space's own rules.
 */
@Service
class SpaceInsights(
    private val jdbc: JdbcClient,
    private val spaces: SpaceService,
    private val entries: EntryStore,
    private val policies: SpacePolicyService,
    private val audit: AuditService,
    private val props: FolderSyncProperties,
) {
    fun summary(ownerId: String, spaceId: String): SpaceSummary {
        val s = spaces.require(spaceId, ownerId)
        val conflicts = jdbc.sql("SELECT COUNT(*) FROM conflicts WHERE space_id = ? AND owner_id = ? AND state = 'UNRESOLVED'")
            .args(spaceId, ownerId).query(Int::class.java).single()
        val last = jdbc.sql("SELECT MAX(created_at) FROM revisions WHERE space_id = ?").param(spaceId)
            .query { rs, _ -> rs.getLong(1).takeUnless { rs.wasNull() } }.optional().orElse(null)
        val devices = jdbc.sql(
            """SELECT d.id, d.label, b.status, d.last_seen FROM bindings b JOIN devices d ON d.id = b.device_id
               WHERE b.space_id = ? AND b.owner_id = ? AND b.status = 'ACTIVE' ORDER BY d.label""",
        ).args(spaceId, ownerId).query { rs, _ ->
            BoundDevice(rs.getString(1), rs.getString(2), BindingStatus.valueOf(rs.getString(3)), rs.getLong(4).takeUnless { rs.wasNull() })
        }.list()
        return SpaceSummary(spaceId, s.revision, entries.activeFileCount(spaceId), conflicts, last, devices)
    }

    fun summaries(ownerId: String): List<Pair<SpaceRecord, SpaceSummary>> = spaces.list(ownerId).map { it to summary(ownerId, it.id) }

    fun activity(ownerId: String, spaceId: String, limit: Int = 100): List<ActivityItem> {
        spaces.require(spaceId, ownerId)
        return audit.recent(spaceId, limit).map { ActivityItem(it.id, it.ts, it.action, describe(it.action), it.detail) }
    }

    private fun describe(action: String): String = when (action) {
        "space.created" -> "Space created"
        "binding.enrolled" -> "A folder was connected"
        "binding.disconnected" -> "A folder was disconnected"
        "run.started" -> "A sync run started"
        "run.planned" -> "A sync plan was prepared"
        "conflict.created" -> "A conflict was recorded"
        "conflict.resolved" -> "A conflict was resolved"
        "conflict.candidate_discarded" -> "An unchosen conflict version was discarded"
        "deletion.server_applied" -> "A confirmed deletion was applied on the server"
        "deletion.local_approved", "deletion.local_applied" -> "A confirmed deletion was applied in a folder"
        else -> action.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    // ---- ignore rules ------------------------------------------------------------------------------------------

    fun ruleFiles(ownerId: String, spaceId: String): List<RuleFile> {
        val s = spaces.require(spaceId, ownerId)
        return policies.ruleFiles(s.id).flatMap { (dir, f) ->
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            listOfNotNull(f.gitignore?.let { RuleFile("$prefix.gitignore", it) }, f.syncignore?.let { RuleFile("$prefix.syncignore", it) })
        }.sortedBy { it.path }
    }

    fun explain(ownerId: String, spaceId: String, rawPath: String, directory: Boolean): RuleExplanation {
        val s = spaces.require(spaceId, ownerId)
        val path = rawPath.trim().trim('/')
        if (PortablePath.validate(path) != null) throw ApiException.invalid("invalid_path", "That is not a valid synchronized path")
        return explainWith(policies.policyFor(s.id, s.policyRevision), path, directory)
    }

    private fun explainWith(policy: IgnorePolicy, path: String, directory: Boolean): RuleExplanation {
        val d = policy.evaluate(path, directory)
        val r = d.rule
        val text = when {
            d.builtin -> "Git metadata is never synchronized: every path containing a .git segment is excluded and cannot be re-included."
            !d.ignored -> "No rule excludes this path, so it is synchronized."
            else -> {
                val via = if (d.matchedPath != null && d.matchedPath != path) " It lies inside the excluded folder \"${d.matchedPath}\"." else ""
                "Excluded by line ${r?.line} of ${r?.sourceFile}: ${r?.text}.$via Excluded paths are left untouched on both sides."
            }
        }
        return RuleExplanation(path, directory, d.ignored, d.builtin, r?.text, r?.sourceFile, r?.line, d.matchedPath, text)
    }

    /**
     * What would change if [controlPath] (a `.gitignore` or `.syncignore`) had [proposedText]? Compares the current
     * and proposed policy over every live entry. Nothing is stored, and excluded files are never deleted on either
     * side; this only tells the user which paths would stop or start being synchronized.
     */
    fun previewPolicy(ownerId: String, spaceId: String, controlPath: String, proposedText: String, limit: Int = 200): PolicyPreview {
        val s = spaces.require(spaceId, ownerId)
        val path = controlPath.trim().trim('/')
        if (PortablePath.validate(path) != null || !IgnorePolicy.isControlFile(path)) {
            throw ApiException.invalid("invalid_control_file", "The file must be a .gitignore or .syncignore at a valid path")
        }
        if (proposedText.toByteArray(Charsets.UTF_8).size > props.maxControlFileBytes) {
            throw ApiException.invalid("control_file_too_large", "The rule file is too large")
        }
        val current = policies.ruleFiles(s.id)
        val dir = path.substringBeforeLast('/', "")
        val cur = current[dir] ?: IgnorePolicy.DirectoryRuleFiles()
        val proposedDir = if (path.substringAfterLast('/') == ".gitignore") cur.copy(gitignore = proposedText) else cur.copy(syncignore = proposedText)
        val before = IgnorePolicy(current)
        val after = IgnorePolicy(current + (dir to proposedDir))
        val newlyIgnored = ArrayList<PolicyChange>()
        val newlyIncluded = ArrayList<PolicyChange>()
        var ignoredTotal = 0
        var includedTotal = 0
        for (e in entries.all(s.id)) {
            if (e.deleted) continue
            val isDir = e.type == "DIRECTORY"
            val a = before.evaluate(e.path, isDir)
            val b = after.evaluate(e.path, isDir)
            if (!a.ignored && b.ignored) {
                ignoredTotal++
                if (newlyIgnored.size < limit) newlyIgnored.add(PolicyChange(e.path, isDir, explainWith(after, e.path, isDir).explanation))
            }
            if (a.ignored && !b.ignored) {
                includedTotal++
                if (newlyIncluded.size < limit) newlyIncluded.add(PolicyChange(e.path, isDir, null))
            }
        }
        val note = "This is a preview only. Newly excluded paths stay as they are on the server and in every folder; they are simply no longer synchronized. " +
            "Paths that become included again are compared with their kept records, so a missing copy is never treated as a deletion."
        return PolicyPreview(newlyIgnored, newlyIncluded, ignoredTotal, includedTotal, note)
    }
}
