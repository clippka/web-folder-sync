package app.foldersync.support

import app.foldersync.reconciliation.AppendEntriesRequest
import app.foldersync.reconciliation.CreatePlanRequest
import app.foldersync.reconciliation.PlanEntryDto
import app.foldersync.reconciliation.StateDto
import app.foldersync.revisions.CommitRequest
import org.springframework.test.web.servlet.ResultActions
import tools.jackson.databind.JsonNode

/** What the (simulated) browser sees locally for one path. */
sealed interface Local {
    class File(val bytes: ByteArray) : Local
    data object Dir : Local
    data object Unreadable : Local
}

class Ctx(val user: TestUser, val spaceId: String, val deviceId: String, val bindingId: String, var epoch: String)

class SyncOutcome(val plan: JsonNode, val ops: List<JsonNode>, val commit: ResultActions?, val commitBody: JsonNode?)

/** Drives the real HTTP contract the way the TypeScript coordinator will: plan -> upload -> commit -> acknowledge. */
abstract class SyncTest : IntegrationTest() {

    fun setup(user: TestUser = newUser(), spaceName: String = "space-" + java.util.UUID.randomUUID().toString().take(8)): Ctx {
        val space = user.post("/api/spaces", mapOf("name" to spaceName)).expect(201).body()
        val device = user.post("/api/devices", mapOf("label" to "Test laptop")).expect(201).body()
        return enroll(user, space["id"].asText(), device["id"].asText())
    }

    fun enroll(user: TestUser, spaceId: String, deviceId: String): Ctx {
        val b = user.post("/api/spaces/$spaceId/bindings", mapOf("deviceId" to deviceId)).expect(201).body()
        return Ctx(user, spaceId, deviceId, b["id"].asText(), b["epoch"].asText())
    }

    /** A second machine of the same user in the same space. */
    fun secondDevice(ctx: Ctx): Ctx {
        val device = ctx.user.post("/api/devices", mapOf("label" to "Second machine")).expect(201).body()
        return enroll(ctx.user, ctx.spaceId, device["id"].asText())
    }

    fun policyRevision(ctx: Ctx): Long = ctx.user.get("/api/spaces/${ctx.spaceId}").expect(200).body()["policyRevision"].asLong()

    fun state(l: Local?): StateDto? = when (l) {
        null, Local.Unreadable -> null
        is Local.File -> StateDto("FILE", sha256(l.bytes), l.bytes.size.toLong())
        Local.Dir -> StateDto("DIRECTORY")
    }

    fun base(bytes: ByteArray) = StateDto("FILE", sha256(bytes), bytes.size.toLong())

    /** Uploads [bytes] through the real chunk protocol and returns the completed upload id. */
    fun upload(user: TestUser, spaceId: String, bytes: ByteArray, key: String = java.util.UUID.randomUUID().toString()): String {
        val begin = user.post("/api/spaces/$spaceId/uploads", mapOf("size" to bytes.size, "sha256" to sha256(bytes)), key).expect(201).body()
        val id = begin["uploadId"].asText()
        val chunk = begin["chunkSize"].asInt()
        for (i in 0 until begin["chunkCount"].asInt()) {
            user.put("/api/uploads/$id/chunks/$i", bytes.copyOfRange(i * chunk, minOf(bytes.size, (i + 1) * chunk))).expect(200)
        }
        user.post("/api/uploads/$id/complete").expect(200)
        return id
    }

    fun createPlan(ctx: Ctx, local: Map<String, Local>, baselines: Map<String, StateDto> = emptyMap(), scanComplete: Boolean = true, reincluded: Set<String> = emptySet()): ResultActions {
        val paths = (local.keys + baselines.keys).sorted()
        val entries = paths.map { p ->
            PlanEntryDto(p, baselines[p], state(local[p]), unreadable = local[p] == Local.Unreadable, reincluded = p in reincluded)
        }
        return ctx.user.post(
            "/api/spaces/${ctx.spaceId}/plans",
            CreatePlanRequest(ctx.bindingId, ctx.epoch, 0, policyRevision(ctx), scanComplete, entries, final = true),
        )
    }

    fun allOps(ctx: Ctx, planId: String): List<JsonNode> {
        val out = ArrayList<JsonNode>()
        var after = -1
        while (true) {
            val page = ctx.user.get("/api/plans/$planId/operations", "after" to after.toString(), "limit" to "50").expect(200).body()
            page["operations"].forEach { out.add(it) }
            if (page["nextAfter"].isNull) return out
            after = page["nextAfter"].asInt()
        }
    }

    /**
     * Plans, stages every upload the plan asks for, commits, and (when asked) approves deletions. Returns everything so
     * tests can assert on any stage.
     */
    fun sync(
        ctx: Ctx, local: Map<String, Local>, baselines: Map<String, StateDto> = emptyMap(), scanComplete: Boolean = true,
        approveServerDeletions: Boolean = false, key: String = java.util.UUID.randomUUID().toString(),
        reincluded: Set<String> = emptySet(), commit: Boolean = true,
    ): SyncOutcome {
        val plan = createPlan(ctx, local, baselines, scanComplete, reincluded).expect(201).body()
        val planId = plan["id"].asText()
        val ops = allOps(ctx, planId)
        if (!commit) return SyncOutcome(plan, ops, null, null)
        val uploads = HashMap<String, String>()
        val candidates = HashMap<String, String>()
        for (op in ops) {
            val file = local[op.path()] as? Local.File ?: continue
            when (op["kind"].asText()) {
                "UPLOAD" -> if (op["state"]["type"].asText() == "FILE") uploads[op.path()] = upload(ctx.user, ctx.spaceId, file.bytes)
                "CONFLICT" -> candidates[op.path()] = upload(ctx.user, ctx.spaceId, file.bytes)
            }
        }
        val deletions = if (approveServerDeletions) ops.filter { it["kind"].asText() == "PROPOSE_SERVER_DELETION" }.map { it.path() } else emptyList()
        val result = ctx.user.post("/api/plans/$planId/commit", CommitRequest(ctx.epoch, uploads, candidates, deletions), key)
        val body = if (result.andReturn().response.status == 200) result.body() else null
        return SyncOutcome(plan, ops, result, body)
    }

    fun JsonNode.path(): String = this["path"].asText()

    fun kinds(o: SyncOutcome): Map<String, String> = o.ops.associate { it.path() to it["kind"].asText() }

    fun manifest(ctx: Ctx): Map<String, JsonNode> {
        val out = LinkedHashMap<String, JsonNode>()
        var after: String? = null
        var revision: String? = null
        while (true) {
            val params = ArrayList<Pair<String, String>>()
            after?.let { params.add("after" to it) }
            revision?.let { params.add("revision" to it) }
            params.add("limit" to "3")
            val page = ctx.user.get("/api/spaces/${ctx.spaceId}/manifest", *params.toTypedArray()).expect(200).body()
            revision = page["revision"].asText()
            page["entries"].forEach { out[it.path()] = it }
            if (page["nextAfter"].isNull) return out
            after = page["nextAfter"].asText()
        }
    }

    fun download(ctx: Ctx, blobId: String) = ctx.user.get("/api/spaces/${ctx.spaceId}/blobs/$blobId/content")
}
