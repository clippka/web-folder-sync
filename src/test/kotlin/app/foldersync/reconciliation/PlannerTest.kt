package app.foldersync.reconciliation

import app.foldersync.reconciliation.EntryState.Absent
import app.foldersync.reconciliation.EntryState.Directory
import app.foldersync.reconciliation.Operation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest {
    private fun f(h: String, size: Long = 1) = EntryState.File(h, size)
    private fun obs(s: EntryState) = LocalObservation.Observed(s)
    private fun input(path: String, b: EntryState, l: EntryState, s: EntryState, reincluded: Boolean = false, suspension: SuspensionReason? = null) =
        PathInput(path, b, obs(l), s, suspension, reincluded)

    private fun plan(vararg i: PathInput, scan: ScanCompleteness = ScanCompleteness.COMPLETE) =
        Planner.plan(i.toList(), scan, serverRevision = 7, policyRevision = 3)

    private fun single(i: PathInput, scan: ScanCompleteness = ScanCompleteness.COMPLETE): Operation? = plan(i, scan = scan).operations.singleOrNull()

    // ---- Section 5 table, row by row ----

    @Test fun `row 1 unchanged both - no action`() {
        assertEquals(null, single(input("a", f("1"), f("1"), f("1"))))
    }

    @Test fun `row 2 local changed - upload`() {
        assertEquals(Upload("a", f("2"), f("1")), single(input("a", f("1"), f("2"), f("1"))))
    }

    @Test fun `row 3 server changed - download with expected local`() {
        assertEquals(Download("a", f("2"), f("1")), single(input("a", f("1"), f("1"), f("2"))))
    }

    @Test fun `row 4 both changed identically - adopt`() {
        assertEquals(Adopt("a", f("2")), single(input("a", f("1"), f("2"), f("2"))))
    }

    @Test fun `row 5 both changed differently - conflict`() {
        assertEquals(Conflict("a", ConflictKind.CONTENT, f("2"), f("3")), single(input("a", f("1"), f("2"), f("3"))))
    }

    @Test fun `row 6 local deleted server unchanged - propose server deletion`() {
        assertEquals(ProposeServerDeletion("a", f("1")), single(input("a", f("1"), Absent, f("1"))))
    }

    @Test fun `row 7 server deleted local unchanged - propose local deletion`() {
        assertEquals(ProposeLocalDeletion("a", f("1")), single(input("a", f("1"), f("1"), Absent)))
    }

    @Test fun `row 8 local deleted server modified - conflict`() {
        assertEquals(Conflict("a", ConflictKind.DELETE_VS_EDIT, Absent, f("2")), single(input("a", f("1"), Absent, f("2"))))
    }

    @Test fun `row 9 local modified server deleted - conflict`() {
        assertEquals(Conflict("a", ConflictKind.DELETE_VS_EDIT, f("2"), Absent), single(input("a", f("1"), f("2"), Absent)))
    }

    @Test fun `row 10 both deleted - acknowledge`() {
        assertEquals(AcknowledgeDeletion("a"), single(input("a", f("1"), Absent, Absent)))
    }

    // ---- First enrollment and additions (baseline absent) ----

    @Test fun `first enrollment never infers deletion`() {
        assertEquals(Download("only-server", f("1"), Absent), single(input("only-server", Absent, Absent, f("1"))))
        assertEquals(Upload("only-local", f("1"), Absent), single(input("only-local", Absent, f("1"), Absent)))
    }

    @Test fun `first enrollment identical content is adopted without transfer`() {
        assertEquals(Adopt("a", f("1")), single(input("a", Absent, f("1"), f("1"))))
    }

    @Test fun `concurrent creation with different content is a conflict`() {
        assertIs<Conflict>(single(input("a", Absent, f("1"), f("2"))))
    }

    @Test fun `same hash but different size is not equal`() {
        assertIs<Conflict>(single(input("a", Absent, f("1", 1), f("1", 2))))
    }

    @Test fun `identical empty directories are adopted`() {
        assertEquals(Adopt("d", Directory), single(input("d", Absent, Directory, Directory)))
    }

    @Test fun `file versus directory at same path is structural conflict`() {
        assertEquals(Conflict("p", ConflictKind.STRUCTURAL, f("1"), Directory), single(input("p", Absent, f("1"), Directory)))
    }

    @Test fun `type change on one side only is structural conflict not a silent replace`() {
        assertIs<Conflict>(single(input("p", f("1"), Directory, f("1"))))
        assertIs<Conflict>(single(input("p", Directory, Directory, f("1"))))
    }

    // ---- Unsafe observations ----

    @Test fun `incomplete scan produces no deletion plans in either direction`() {
        val p = plan(
            input("gone-local", f("1"), Absent, f("1")),
            input("gone-server", f("1"), f("1"), Absent),
            input("kept", f("1"), f("1"), f("1")),
            scan = ScanCompleteness.INCOMPLETE,
        )
        assertTrue(p.operations.none { it is ProposeServerDeletion || it is ProposeLocalDeletion || it is AcknowledgeDeletion })
        assertEquals(2, p.operations.count { it is Blocked })
    }

    @Test fun `incomplete scan blocks absent-local downloads rather than guessing`() {
        assertEquals(Blocked("x", BlockReason.INCOMPLETE_SCAN), single(input("x", Absent, Absent, f("1")), ScanCompleteness.INCOMPLETE))
    }

    @Test fun `unreadable local entry blocks and never deletes`() {
        val i = PathInput("a", f("1"), LocalObservation.Unreadable, Absent)
        assertEquals(Blocked("a", BlockReason.UNREADABLE), single(i))
    }

    @Test fun `missing baseline cannot cause deletion`() {
        // State lost: baseline reads as absent. Whatever exists on either side is only ever added.
        val p = plan(input("a", Absent, Absent, f("1")), input("b", Absent, f("2"), Absent))
        assertTrue(p.operations.none { it.needsDecision })
    }

    // ---- Suspension and re-inclusion ----

    @Test fun `ignored or oversized paths are suspended and never become deletions`() {
        val p = plan(
            input("ign", f("1"), Absent, f("1"), suspension = SuspensionReason.IGNORED),
            input("big", f("1"), f("2"), f("1"), suspension = SuspensionReason.OVERSIZED),
        )
        assertTrue(p.operations.all { it is Suspended })
    }

    @Test fun `re-inclusion treats local absence during suspension as restore not deletion`() {
        val i = input("a", f("1"), Absent, f("1"), reincluded = true)
        assertEquals(Download("a", f("1"), Absent), single(i))
    }

    @Test fun `re-inclusion treats server absence during suspension as upload not deletion`() {
        assertEquals(Upload("a", f("1"), Absent), single(input("a", f("1"), f("1"), Absent, reincluded = true)))
    }

    @Test fun `re-inclusion still detects conflicts via retained baseline`() {
        assertIs<Conflict>(single(input("a", f("1"), f("2"), f("3"), reincluded = true)))
    }

    // ---- Structure ----

    @Test fun `parents are created before children and deletions run deepest first`() {
        val p = plan(
            input("d/e/f.txt", Absent, f("1"), Absent),
            input("d/e", Absent, Directory, Absent),
            input("d", Absent, Directory, Absent),
            input("x/y", Directory, Absent, Directory),
            input("x", Directory, Absent, Directory),
        )
        assertEquals(listOf("d", "d/e", "d/e/f.txt", "x/y", "x"), p.operations.map { it.path })
    }

    @Test fun `child creation under an unresolved parent conflict is blocked while unrelated paths proceed`() {
        val p = plan(
            input("p", Absent, f("1"), Directory),
            input("p/child.txt", Absent, f("2"), Absent),
            input("other.txt", Absent, f("3"), Absent),
        )
        val byPath = p.operations.associateBy { it.path }
        assertIs<Conflict>(byPath["p"])
        assertEquals(Blocked("p/child.txt", BlockReason.DEPENDS_ON_UNRESOLVED_ANCESTOR, "p"), byPath["p/child.txt"])
        assertIs<Upload>(byPath["other.txt"])
    }

    @Test fun `creation whose parent is unknown or absent at the destination is blocked`() {
        assertEquals(Blocked("d/a", BlockReason.MISSING_PARENT, "d"), single(input("d/a", Absent, f("1"), Absent)))
    }

    @Test fun `empty directory propagates both ways`() {
        val p = plan(input("empty", Absent, Directory, Absent), input("srv", Absent, Absent, Directory))
        assertEquals(listOf(Upload("empty", Directory, Absent), Download("srv", Directory, Absent)), p.operations)
    }

    @Test fun `removing the last file does not delete its directory`() {
        val p = plan(input("d", Directory, Directory, Directory), input("d/a", f("1"), Absent, f("1")))
        assertEquals(listOf<Operation>(ProposeServerDeletion("d/a", f("1"))), p.operations)
    }

    @Test fun `directory deletion with opposite-side new descendant is structural conflict`() {
        val p = plan(
            input("d", Directory, Absent, Directory),
            input("d/new.txt", Absent, Absent, f("1")),
        )
        val byPath = p.operations.associateBy { it.path }
        assertIs<Conflict>(byPath["d"])
        assertIs<Blocked>(byPath["d/new.txt"])
    }

    @Test fun `directory deletion where all children are also deleted proposes children first`() {
        val p = plan(input("d", Directory, Absent, Directory), input("d/a", f("1"), Absent, f("1")))
        assertEquals(listOf("d/a", "d"), p.operations.map { it.path })
        assertTrue(p.operations.all { it is ProposeServerDeletion })
    }

    @Test fun `directory deletion with a blocked descendant is blocked`() {
        val p = plan(
            input("d", Directory, Absent, Directory),
            PathInput("d/a", f("1"), LocalObservation.Unreadable, f("1")),
        )
        assertEquals(Blocked("d", BlockReason.DESCENDANT_UNRESOLVED), p.operations.first { it.path == "d" })
    }

    @Test fun `rename is delete plus add`() {
        val p = plan(input("old.txt", f("1"), Absent, f("1")), input("new.txt", Absent, f("1"), Absent))
        assertEquals(setOf("ProposeServerDeletion", "Upload"), p.operations.map { it::class.simpleName }.toSet())
    }

    // ---- Control files ----

    @Test fun `control file change is planned first and requires rescan before content`() {
        val p = plan(
            input(".gitignore", f("1"), f("2"), f("1")),
            input("src.txt", Absent, f("9"), Absent),
        )
        assertEquals(PlanPhase.CONTROL, p.phase)
        assertTrue(p.rescanRequired)
        assertFalse(p.paused)
        assertEquals(listOf(".gitignore"), p.operations.map { it.path })
    }

    @Test fun `control file conflict pauses the space`() {
        val p = plan(
            input("pkg/.syncignore", f("1"), f("2"), f("3")),
            input("a.txt", Absent, f("9"), Absent),
        )
        assertTrue(p.paused)
        assertEquals(PlanPhase.CONTROL, p.phase)
        assertTrue(p.operations.none { it.path == "a.txt" })
    }

    @Test fun `unchanged control files let content proceed`() {
        val p = plan(input(".gitignore", f("1"), f("1"), f("1")), input("a.txt", Absent, f("9"), Absent))
        assertEquals(PlanPhase.CONTENT, p.phase)
        assertEquals(listOf<Operation>(Upload("a.txt", f("9"), Absent)), p.operations)
    }

    @Test fun `plan carries revisions and flags decisions`() {
        val p = plan(input("a", f("1"), Absent, f("1")), input("b", Absent, f("1"), Absent))
        assertEquals(7, p.serverRevision)
        assertEquals(3, p.policyRevision)
        assertEquals(listOf("a"), p.decisions.map { it.path })
    }

    @Test fun `planning is deterministic regardless of input order`() {
        val inputs = listOf(
            input("z", Absent, f("1"), Absent), input("a", f("1"), Absent, f("1")), input("m", Absent, Directory, Absent),
        )
        assertEquals(
            Planner.plan(inputs, ScanCompleteness.COMPLETE, 1, 1),
            Planner.plan(inputs.reversed(), ScanCompleteness.COMPLETE, 1, 1),
        )
    }
}
