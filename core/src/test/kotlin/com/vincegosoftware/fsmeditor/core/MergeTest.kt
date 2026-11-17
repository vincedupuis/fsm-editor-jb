package com.vincegosoftware.fsmeditor.core

import com.vincegosoftware.fsmeditor.core.TestFiles.example
import com.vincegosoftware.fsmeditor.core.TestFiles.exampleModel
import com.vincegosoftware.fsmeditor.core.TestFiles.read
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The diagram merge's logic (the VS Code edition's test/unit/merge.test.ts), and the sessions linking the panes (MergeTests.cs). */
class MergeTest {
    private fun v(m: FsmModel, name: String) = m.vertices.first { it.name == name }

    @Test
    fun splitsIntoTheCurrentAndIncomingSidesInBothStyles() {
        val two = "a\n<<<<<<< HEAD\nmine\n=======\ntheirs\n>>>>>>> feature\nb\n"
        assertTrue(Merge.hasConflictMarkers(two))
        val s = Merge.splitConflicts(two)
        assertEquals(Triple("a\nmine\nb\n", "a\ntheirs\nb\n", 1), Triple(s.current, s.incoming, s.conflicts))
        val s3 = Merge.splitConflicts("a\r\n<<<<<<< HEAD\r\nmine\r\n||||||| base\r\nold\r\n=======\r\ntheirs\r\n>>>>>>> feature\r\n")
        assertEquals(Triple("a\r\nmine\r\n", "a\r\ntheirs\r\n", 1), Triple(s3.current, s3.incoming, s3.conflicts))
        assertFalse(Merge.hasConflictMarkers("a\n======= not a conflict\n"))
    }

    @Test
    fun refusesMarkersThatDoNotNest() {
        assertContains(assertFailsWith<ConflictMarkerException> { Merge.splitConflicts("<<<<<<< a\nx\n") }.message!!, "no end marker")
        assertContains(assertFailsWith<ConflictMarkerException> { Merge.splitConflicts(">>>>>>> a\n") }.message!!, "ends where none is open")
    }

    @Test
    fun aModelConflictInTheRealFile() {
        val b = example("Payment.fsm")
        val current = b.replaceFirst("\"entry\": \"capture()\"", "\"entry\": \"capture(); log()\"")
        val incoming = b.replaceFirst("\"entry\": \"capture()\"", "\"entry\": \"settle()\"")
        val marked = b.replaceFirst("      \"entry\": \"capture()\",\n", "<<<<<<< HEAD\n      \"entry\": \"capture(); log()\",\n=======\n      \"entry\": \"settle()\",\n>>>>>>> feature\n")
        val split = Merge.splitConflicts(marked)
        assertEquals(current, split.current)
        assertEquals(incoming, split.incoming)
        val changes = Compare.compareModels(Format.readModel(split.current).model!!, Format.readModel(split.incoming).model!!)
        assertEquals(listOf("state 'Captured'" to listOf("entry")), changes.map { c -> c.label to c.properties.map { it.name } })
    }

    @Test
    fun aSideThatIsNotAValidMachineFileIsReported() {
        val split = Merge.splitConflicts("{\n<<<<<<< HEAD\n  \"format\": 1,\n=======\n  \"format\": 1\n>>>>>>> x\n  \"name\": \"M\", \"type\": \"behavioral\" }\n")
        assertFalse(Format.readModel(split.current).failed)
        assertTrue(Format.readModel(split.incoming).failed)
    }

    @Test
    fun takesAPropertyOfEachKind() {
        val current = exampleModel("MediaPlayer")
        val incoming = current.copy()
        v(incoming, "Off").entry = "sleep()"
        v(incoming, "Paused").doActivity = "blink(); beep()"
        val t = incoming.transitions.first { it.triggers.firstOrNull() == "eject" }
        t.guard = "hasDisc()"
        t.target = v(incoming, "Paused").id
        v(incoming, "Fault").x += 30
        incoming.documentation = "Theirs"
        var result = current.copy()
        val conflicts = Compare.compareModels(current, incoming)
        assertEquals(5, conflicts.size)
        for (c in conflicts) {
            result = Merge.adopt(result, incoming, c.key, c.oldKey ?: c.key)
            assertTrue(Compare.compareModels(result, incoming).none { it.key == c.key }, c.label)
        }
        assertEquals(emptyList(), Compare.compareModels(result, incoming))
        // Keeping current again takes it back.
        for (c in conflicts) result = Merge.adopt(result, current, c.oldKey ?: c.key, c.key)
        assertEquals(emptyList(), Compare.compareModels(result, current))
    }

    @Test
    fun anElementOnOneSideOnlyIsAddedWithWhatItContainsOrRemovedWithItsTransitions() {
        val current = exampleModel("MediaPlayer")
        val incoming = current.copy()
        // Theirs removed Playing (and its regions' contents) and the transitions to it.
        val gone = incoming.vertices.filter { it.name in listOf("Playing", "Stereo", "Mono", "HD", "SD") || it.parent in listOf("audio", "video") }.map { it.id }.toSet()
        incoming.vertices = incoming.vertices.filterTo(mutableListOf()) { it.id !in gone }
        incoming.transitions = incoming.transitions.filterTo(mutableListOf()) { it.source !in gone && it.target !in gone }
        val removed = Compare.compareModels(current, incoming).first { it.label == "state 'Playing'" }
        assertEquals(ChangeKind.Removed, removed.kind)
        val result = Merge.adopt(current, incoming, removed.key)
        assertTrue(result.vertices.none { it.name in listOf("Playing", "Stereo", "HD") })
        assertTrue(result.transitions.none { it.target == "Playing" || it.source == "Playing" })
        // The other way: taking current's Playing back brings its contents along.
        val back = Merge.adopt(result, current, removed.key)
        for (n in listOf("Playing", "Stereo", "Mono", "HD", "SD")) assertTrue(back.vertices.any { it.name == n }, n)
        assertEquals(v(current, "Stereo").x to v(current, "Stereo").y, v(back, "Stereo").x to v(back, "Stereo").y)
        // The result can be written: names given, nothing lost.
        val m = back.copy()
        Format.assignNames(m)
        assertEquals(emptyList(), Format.writeMachine(m).lost)
    }

    @Test
    fun takingATransitionBeforeItsTargetTargetsTheReferenceTheFileWrites() {
        fun m(body: String) = read("""{ "format": 1, "name": "M", "type": "behavioral", "initial": { "target": "A" }, $body }""").model!!
        val current = m(""""elements": [{ "name": "A", "type": "state" }]""")
        val incoming = m(""""elements": [{ "name": "A", "type": "state", "transitions": [{ "type": "external", "trigger": "go", "target": "Foo" }] }, { "name": "Foo", "type": "state" }]""")
        val changes = Compare.compareModels(current, incoming)
        var r = Merge.adopt(current, incoming, changes.first { it.what == "transition" }.key)
        fun t() = r.transitions.first { "go" in it.triggers }
        assertEquals(Format.UNRESOLVED + "Foo", t().target)
        assertContains(Format.writeMachine(r).model, "\"target\": \"Foo\"")
        r = Merge.adopt(r, incoming, changes.first { it.key == "element@Foo" }.key)
        Format.assignNames(r)
        assertTrue(Format.resolveReferences(r, Format.writeMachine(r)))
        assertEquals(v(r, "Foo").id, t().target)
    }

    // ---------------------------------------------------------------- sessions

    private class Pane(var model: FsmModel? = null) : DiffPane {
        var diff: PaneDiff? = null
        var revealed: String? = null

        override fun currentModel() = model

        override fun showDiff(diff: PaneDiff?) {
            this.diff = diff
        }

        override fun reveal(id: String?) {
            revealed = id
        }

        override fun toast(message: String) {}
    }

    /** A result document with undo and redo, as the editions hold one: a state per edit. */
    private class Host(model: FsmModel) : MergeHost {
        val history = mutableListOf(Any() to model)
        var at = 0
        val model get() = history[at].second

        override fun state(): Any = history[at].first

        override fun edit(model: FsmModel, label: String): String? {
            while (history.size > at + 1) history.removeAt(history.size - 1)
            history.add(Any() to model)
            at++
            return null
        }
    }

    @Test
    fun aDiffMarksBothPanesAndHidesLayoutChanges() {
        val a = exampleModel("Payment")
        val b = a.copy()
        v(b, "Captured").entry = "settle()"
        v(b, "Declined").x += 40
        val older = Pane(a)
        val newer = Pane(b)
        val d = DiffSession(older, newer, "Payment.fsm (HEAD)", "Payment.fsm")
        d.update()
        assertEquals(1, newer.diff!!.count)
        assertEquals("changed", newer.diff!!.marks[v(b, "Captured").id])
        assertEquals(listOf("entry: capture() → settle()"), newer.diff!!.details[v(b, "Captured").id])
        assertEquals("old", older.diff!!.role)
        d.setShowLayout(true)
        assertEquals(2, newer.diff!!.count)
        d.navigate(1)
        assertEquals(v(a, "Captured").id, older.revealed)
        d.remove(older)
        assertNull(newer.diff)
    }

    @Test
    fun mergeChoicesFollowUndoAndRedo() {
        val current = exampleModel("Payment")
        val incoming = current.copy()
        v(incoming, "Captured").entry = "settle()"
        val texts = MachineTexts(example("Payment.fsm"), null)
        val host = Host(current.copy())
        val reads = ArrayDeque(listOf(current, incoming))
        val session = MergeSession("m1", texts, texts, null, { reads.removeFirst() }, host) { host.model }
        val result = Pane()
        session.attach(result, "result")
        assertEquals(1, session.conflictCount)
        assertNull(result.diff!!.conflicts!![0].choice)
        session.choose(result.diff!!.conflicts!![0].key, "incoming")
        assertEquals("settle()", v(host.model, "Captured").entry)
        assertEquals("incoming", result.diff!!.conflicts!![0].choice)
        assertEquals("resolved", result.diff!!.marks[v(host.model, "Captured").id])
        // Undo: the result is back to the current side, and so is the choice.
        host.at--
        session.update()
        assertNull(result.diff!!.conflicts!![0].choice)
        assertEquals("conflict", result.diff!!.marks[v(host.model, "Captured").id])
        // Redo: chosen again.
        host.at++
        session.update()
        assertEquals("incoming", result.diff!!.conflicts!![0].choice)
        assertEquals("1 of 1 chosen", result.diff!!.title)
    }
}
