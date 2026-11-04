package app.foldersync.reconciliation

/** Explicit state of one path. Absence is a state, not a missing value. */
sealed interface EntryState {
    data object Absent : EntryState
    data class File(val sha256: String, val size: Long) : EntryState
    data object Directory : EntryState

    val isPresent: Boolean get() = this !is Absent
}

/** What the local scan could say about a path. */
sealed interface LocalObservation {
    data class Observed(val state: EntryState) : LocalObservation
    /** Permission or read failure: nothing may be inferred, least of all deletion. */
    data object Unreadable : LocalObservation
}

enum class ScanCompleteness { COMPLETE, INCOMPLETE }

enum class SuspensionReason { IGNORED, OVERSIZED }

/**
 * Everything the planner knows about one path.
 * [baseline] is the last acknowledged shared state; use [EntryState.Absent] when there is none (first enrollment,
 * lost state), which makes deletions impossible to infer for that path.
 * [reincluded] marks a path that was suspended and is included again: absence accumulated meanwhile is never deletion intent.
 */
data class PathInput(
    val path: String,
    val baseline: EntryState,
    val local: LocalObservation,
    val server: EntryState,
    val suspension: SuspensionReason? = null,
    val reincluded: Boolean = false,
)

enum class ConflictKind { CONTENT, DELETE_VS_EDIT, STRUCTURAL }

enum class BlockReason { INCOMPLETE_SCAN, UNREADABLE, DEPENDS_ON_UNRESOLVED_ANCESTOR, MISSING_PARENT, DESCENDANT_UNRESOLVED }

sealed interface Operation {
    val path: String
    /** True when the user must decide before this can execute. */
    val needsDecision: Boolean get() = false

    /** Local state becomes the server state. [expectedServer] guards against a stale plan. */
    data class Upload(override val path: String, val state: EntryState, val expectedServer: EntryState) : Operation
    /** Server state is applied locally. [expectedLocal] is rechecked immediately before mutation. */
    data class Download(override val path: String, val state: EntryState, val expectedLocal: EntryState) : Operation
    /** Both sides already agree; record the shared baseline without transfer. */
    data class Adopt(override val path: String, val state: EntryState) : Operation
    /** Both sides deleted the path. */
    data class AcknowledgeDeletion(override val path: String) : Operation

    data class ProposeServerDeletion(override val path: String, val expectedServer: EntryState) : Operation {
        override val needsDecision get() = true
    }
    data class ProposeLocalDeletion(override val path: String, val expectedLocal: EntryState) : Operation {
        override val needsDecision get() = true
    }
    data class Conflict(override val path: String, val kind: ConflictKind, val local: EntryState, val server: EntryState) : Operation {
        override val needsDecision get() = true
    }
    data class Blocked(override val path: String, val reason: BlockReason, val cause: String? = null) : Operation
    /** Ignored or oversized: copies and baselines are preserved, nothing happens. */
    data class Suspended(override val path: String, val reason: SuspensionReason) : Operation
}

enum class PlanPhase { CONTROL, CONTENT }

/**
 * Immutable plan. A CONTROL plan contains only ignore-file operations; when [rescanRequired] is set, the policy
 * may have changed and ordinary content must be planned only after rescanning under the new policy revision.
 * [paused] means a control-file conflict stops the whole space.
 */
data class Plan(
    val phase: PlanPhase,
    val serverRevision: Long,
    val policyRevision: Long,
    val paused: Boolean,
    val rescanRequired: Boolean,
    val operations: List<Operation>,
) {
    val decisions: List<Operation> get() = operations.filter { it.needsDecision }
}
