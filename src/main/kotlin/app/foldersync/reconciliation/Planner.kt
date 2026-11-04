package app.foldersync.reconciliation

import app.foldersync.policy.IgnorePolicy
import app.foldersync.reconciliation.EntryState.Absent
import app.foldersync.reconciliation.Operation.*

/** Pure three-way reconciliation. No I/O, no clock, no randomness: equal inputs give equal plans. */
object Planner {

    fun plan(
        inputs: Collection<PathInput>,
        scan: ScanCompleteness,
        serverRevision: Long,
        policyRevision: Long,
    ): Plan {
        val (control, ordinary) = inputs.partition { IgnorePolicy.isControlFile(it.path) }

        val controlOps = reconcile(control, inputs, scan)
        val paused = controlOps.any { it is Conflict }
        val changesPolicy = controlOps.any { it is Upload || it is Download || it is ProposeServerDeletion || it is ProposeLocalDeletion || it is Conflict }
        if (paused || changesPolicy) {
            return Plan(PlanPhase.CONTROL, serverRevision, policyRevision, paused, rescanRequired = changesPolicy, sorted(controlOps))
        }
        val contentOps = reconcile(ordinary, inputs, scan)
        return Plan(PlanPhase.CONTENT, serverRevision, policyRevision, false, false, sorted(controlOps + contentOps))
    }

    private fun reconcile(subset: List<PathInput>, all: Collection<PathInput>, scan: ScanCompleteness): List<Operation> {
        val byPath = all.associateBy { it.path }
        val ops = LinkedHashMap<String, Operation>()
        for (i in subset) decide(i, scan)?.let { ops[i.path] = it }

        convertDirectoryDeletionConflicts(ops, byPath)
        propagateBlocks(ops, byPath)
        return ops.values.toList()
    }

    private fun decide(i: PathInput, scan: ScanCompleteness): Operation? {
        i.suspension?.let { return Suspended(i.path, it) }
        val local = when (val l = i.local) {
            LocalObservation.Unreadable -> return Blocked(i.path, BlockReason.UNREADABLE)
            is LocalObservation.Observed -> l.state
        }
        // An unobserved-but-"absent" path in an incomplete scan proves nothing.
        if (scan == ScanCompleteness.INCOMPLETE && local == Absent) return Blocked(i.path, BlockReason.INCOMPLETE_SCAN)

        val server = i.server
        var baseline = i.baseline
        if (i.reincluded && baseline != Absent && (local == Absent || server == Absent)) baseline = Absent

        val localChanged = local != baseline
        val serverChanged = server != baseline
        return when {
            !localChanged && !serverChanged -> null
            localChanged && !serverChanged -> when {
                local == Absent -> ProposeServerDeletion(i.path, server)
                typeChanged(baseline, local) -> Conflict(i.path, ConflictKind.STRUCTURAL, local, server)
                else -> Upload(i.path, local, server)
            }
            !localChanged -> when {
                server == Absent ->
                    if (scan == ScanCompleteness.INCOMPLETE) Blocked(i.path, BlockReason.INCOMPLETE_SCAN)
                    else ProposeLocalDeletion(i.path, local)
                typeChanged(baseline, server) -> Conflict(i.path, ConflictKind.STRUCTURAL, local, server)
                else -> Download(i.path, server, local)
            }
            local == server -> if (local == Absent) AcknowledgeDeletion(i.path) else Adopt(i.path, local)
            local == Absent || server == Absent -> Conflict(i.path, ConflictKind.DELETE_VS_EDIT, local, server)
            kindOf(local) != kindOf(server) -> Conflict(i.path, ConflictKind.STRUCTURAL, local, server)
            else -> Conflict(i.path, ConflictKind.CONTENT, local, server)
        }
    }

    private fun kindOf(s: EntryState) = when (s) { is EntryState.File -> 1; EntryState.Directory -> 2; Absent -> 0 }
    private fun typeChanged(a: EntryState, b: EntryState) = a != Absent && b != Absent && kindOf(a) != kindOf(b)

    /**
     * A directory deletion proposed on one side while the other side has new or changed content beneath it
     * is a structural conflict, and an unresolved descendant leaves the deletion blocked.
     */
    private fun convertDirectoryDeletionConflicts(ops: MutableMap<String, Operation>, byPath: Map<String, PathInput>) {
        val deletions = ops.values.filter { it is ProposeServerDeletion || it is ProposeLocalDeletion }
            .filter { byPath[it.path]?.baseline == EntryState.Directory }
        for (d in deletions) {
            val under = ops.values.filter { it.path.startsWith(d.path + "/") }
            val conflicting = under.any { it is Upload || it is Download || it is Adopt || it is Conflict }
            val blocked = under.any { it is Blocked }
            val input = byPath.getValue(d.path)
            when {
                conflicting -> ops[d.path] = Conflict(
                    d.path, ConflictKind.STRUCTURAL,
                    (input.local as? LocalObservation.Observed)?.state ?: Absent, input.server,
                )
                blocked -> ops[d.path] = Blocked(d.path, BlockReason.DESCENDANT_UNRESOLVED)
            }
        }
    }

    /** Shallow to deep: a conflict or block stops its subtree; creations need an existing or planned parent. */
    private fun propagateBlocks(ops: MutableMap<String, Operation>, byPath: Map<String, PathInput>) {
        val ordered = ops.keys.sortedWith(compareBy({ depth(it) }, { it }))
        for (p in ordered) {
            val op = ops.getValue(p)
            if (op is Blocked || op is Suspended || op is Conflict) continue
            val ancestor = unresolvedAncestor(p, ops)
            if (ancestor != null) {
                ops[p] = Blocked(p, BlockReason.DEPENDS_ON_UNRESOLVED_ANCESTOR, ancestor); continue
            }
            val parent = p.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                if (op is Upload && !parentAvailable(parent, ops, byPath, onServer = true)) ops[p] = Blocked(p, BlockReason.MISSING_PARENT, parent)
                if (op is Download && !parentAvailable(parent, ops, byPath, onServer = false)) ops[p] = Blocked(p, BlockReason.MISSING_PARENT, parent)
            }
        }
    }

    private fun unresolvedAncestor(path: String, ops: Map<String, Operation>): String? {
        var cur = path.substringBeforeLast('/', "")
        var found: String? = null
        while (cur.isNotEmpty()) {
            val o = ops[cur]
            if (o is Conflict || o is Blocked) found = cur // keep the shallowest
            cur = cur.substringBeforeLast('/', "")
        }
        return found
    }

    private fun parentAvailable(parent: String, ops: Map<String, Operation>, byPath: Map<String, PathInput>, onServer: Boolean): Boolean {
        val op = ops[parent]
        if (onServer && op is Upload) return op.state == EntryState.Directory
        if (!onServer && op is Download) return op.state == EntryState.Directory
        if (op != null && op !is Adopt) return false
        val input = byPath[parent] ?: return false
        return if (onServer) input.server == EntryState.Directory
        else (input.local as? LocalObservation.Observed)?.state == EntryState.Directory
    }

    private fun depth(p: String) = p.count { it == '/' }

    private fun sorted(ops: List<Operation>): List<Operation> {
        fun group(o: Operation) = when (o) {
            is Upload, is Download, is Adopt, is AcknowledgeDeletion -> 0
            is ProposeServerDeletion, is ProposeLocalDeletion -> 1
            is Conflict -> 2
            is Blocked -> 3
            is Suspended -> 4
        }
        return ops.sortedWith(
            compareBy<Operation> { group(it) }
                .thenBy { if (group(it) == 1) -depth(it.path) else depth(it.path) }
                .thenBy { it.path },
        )
    }
}
