package app.foldersync.policy

import app.foldersync.blobs.BlobStore
import app.foldersync.common.ApiException
import app.foldersync.config.FolderSyncProperties
import app.foldersync.revisions.EntryStore
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's authoritative ignore policy for a space, built from the committed `.gitignore` / `.syncignore` entries
 * with the same matcher the browser conformance suite exercises. The server applies it to every mutation, so a client
 * with a stale or tampered policy cannot publish an excluded path. Results are cached per (space, policy revision).
 */
@Service
class SpacePolicyService(
    private val entries: EntryStore,
    private val blobStore: BlobStore,
    private val props: FolderSyncProperties,
) {
    private data class Cached(val revision: Long, val policy: IgnorePolicy)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun policyFor(spaceId: String, policyRevision: Long): IgnorePolicy {
        cache[spaceId]?.takeIf { it.revision == policyRevision }?.let { return it.policy }
        val policy = IgnorePolicy(ruleFiles(spaceId))
        cache[spaceId] = Cached(policyRevision, policy)
        return policy
    }

    /** The committed `.gitignore` / `.syncignore` texts by directory ("" is the root). */
    fun ruleFiles(spaceId: String): Map<String, IgnorePolicy.DirectoryRuleFiles> {
        val byDir = HashMap<String, IgnorePolicy.DirectoryRuleFiles>()
        for (e in entries.controlFiles(spaceId)) {
            val blobId = e.blobId ?: continue
            val file = blobStore.path(blobId)
            if (!Files.isRegularFile(file)) {
                throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable", "A policy file is missing from storage", retryable = true)
            }
            val size = Files.size(file)
            if (size > props.maxControlFileBytes) continue
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            val dir = e.path.substringBeforeLast('/', "")
            val cur = byDir[dir] ?: IgnorePolicy.DirectoryRuleFiles()
            byDir[dir] = if (e.path.substringAfterLast('/') == ".gitignore") cur.copy(gitignore = text) else cur.copy(syncignore = text)
        }
        return byDir
    }
}
