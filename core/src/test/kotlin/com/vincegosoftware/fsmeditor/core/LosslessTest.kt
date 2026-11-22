package com.vincegosoftware.fsmeditor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session doesn't offer what the file can't hold, so that the writer never has to leave something out (as the
 * VS Code edition's webviewLossless tests): the canvas and the properties panel ask it what to offer
 * ([DiagramSession.isStart] for a transition's kind, triggers, guard and conditions, the ⊕ handle, connection and
 * reconnection targets, Reverse, kinds, submachines, drop targets, Leave Parent), and its operations refuse the rest.
 */
class LosslessTest {
    private companion object {
        const val INITIAL_ONLY = "A transition leaving an initial pseudostate holds its target and an action only."
        const val HISTORY_ONLY = "A transition leaving a history pseudostate holds its target and an action only."
        const val ANCHOR = "A comment cannot be attached to an initial pseudostate or to its transition."

        /**
         * A (A → C on go, A → choice on pick), C (composite: its initial → C1, a history without a transition,
         * C1 → C.history on back, C1 → A on tick), a choice ([ok()] → A), a comment; the machine's initial → A / start().
         */
        val SAMPLE = """
            { "version": 1, "name": "M", "type": "behavioral",
              "initial": { "target": "A", "action": "start()" },
              "elements": [
                { "name": "A", "type": "state", "transitions": [
                  { "type": "external", "trigger": "go", "target": "C" },
                  { "type": "external", "trigger": "pick", "target": "ch*" } ] },
                { "name": "C", "type": "state", "initial": { "target": "C1" }, "history": {}, "elements": [
                  { "name": "C1", "type": "state", "transitions": [
                    { "type": "external", "trigger": "back", "target": "C.history" },
                    { "type": "external", "trigger": "tick", "target": "A" } ] } ] },
                { "name": "ch*", "type": "choice", "transitions": [ { "type": "external", "guard": "ok()", "target": "A" } ] },
                { "name": "note*", "type": "comment", "text": "n" }
              ] }
        """.trimIndent()
    }

    private class Fixture(text: String) {
        val s = DiagramSession(TestFiles.read(text).model!!)
        val messages = mutableListOf<String>()
        var commits = 0

        init {
            s.onMessage { messages.add(it) }
            s.onCommitted { commits++ }
        }

        val m get() = s.model
        fun named(name: String) = m.vertices.single { it.name == name }
        fun of(type: VertexType, parent: String? = null) = m.vertices.first { it.type == type && (parent == null || it.parent == parent) }
        fun leaving(v: Vertex) = m.transitions.filter { it.source == v.id }
        val topInitial get() = of(VertexType.Initial, s.rootRegion)
    }

    @Test
    fun startTransitionsAreKnownToThePanel() {
        val f = Fixture(SAMPLE)
        assertTrue(DiagramSession.isStart(f.topInitial))
        assertTrue(DiagramSession.isStart(f.of(VertexType.ShallowHistory)))
        assertFalse(DiagramSession.isStart(f.named("A")))
        assertFalse(DiagramSession.isStart(f.of(VertexType.Choice)))
    }

    @Test
    fun refusesTriggersAndAGuardInTheLabelOfAnInitialTransition() {
        val f = Fixture(SAMPLE)
        val t = f.leaving(f.topInitial)[0]
        assertEquals(INITIAL_ONLY, f.s.applyLabel(t, "go / start()"))
        assertEquals(INITIAL_ONLY, f.s.applyLabel(t, "[ready()] / start()"))
        assertEquals(0, f.commits)
        assertNull(f.s.applyLabel(t, "/ begin()"))
        assertEquals("begin()", t.action)
        assertTrue(t.triggers.isEmpty())
        TestFiles.write(f.m)
    }

    @Test
    fun offersOnlyTheKindsAPseudostateCanBe() {
        val f = Fixture(SAMPLE)
        val choice = f.of(VertexType.Choice)
        assertEquals(listOf(VertexType.Choice, VertexType.Junction), f.s.typeOptions(choice))
        assertEquals(
            listOf(VertexType.ShallowHistory, VertexType.DeepHistory, VertexType.Choice, VertexType.Junction, VertexType.Terminate),
            f.s.typeOptions(f.of(VertexType.ShallowHistory)),
        )
        f.s.changeType(choice, VertexType.Initial)
        assertEquals(VertexType.Choice, choice.type)
        assertEquals(listOf("An initial pseudostate cannot be the target of a transition."), f.messages)
        assertEquals(0, f.commits)
    }

    @Test
    fun movesTheTransitionOfAnInitialPseudostateWhenItIsDrawnAgainAndDoesntReverseIt() {
        val f = Fixture(SAMPLE)
        val init = f.topInitial
        val t = f.leaving(init)[0]
        assertTrue(f.s.canStartFrom(init))
        // Drawn again to the same state: nothing, and no message.
        assertTrue(f.s.canConnect(init, f.named("A").id))
        assertSame(t, f.s.connect(init, f.named("A").id))
        assertEquals(0, f.commits)
        // To C: its one transition goes there.
        assertTrue(f.s.canConnect(init, f.named("C").id))
        assertSame(t, f.s.connect(init, f.named("C").id))
        assertEquals(f.named("C").id, t.target)
        assertEquals(1, f.leaving(init).size)
        assertEquals(1, f.commits)
        assertTrue(f.messages.isEmpty())
        TestFiles.write(f.m)
        assertFalse(f.s.canReverse(f.leaving(init)[0]))
        assertTrue(f.s.canReverse(f.leaving(f.named("A"))[0]))
        assertFalse(f.s.canConnect(f.named("note*"), init.id))
        assertFalse(f.s.canConnect(f.named("note*"), f.leaving(init)[0].id))
        assertTrue(f.s.canConnect(f.named("note*"), f.named("A").id))
        assertFalse(f.s.canConnect(f.named("A"), init.id))
    }

    @Test
    fun refusesToAttachACommentToAnInitialPseudostateOrItsTransition() {
        val f = Fixture(SAMPLE)
        val note = f.named("note*")
        assertNull(f.s.connect(note, f.topInitial.id))
        assertNull(f.s.connect(note, f.leaving(f.topInitial)[0].id))
        assertNull(f.s.connect(f.topInitial, note.id))
        assertEquals(listOf(ANCHOR, ANCHOR, ANCHOR), f.messages)
        assertTrue(note.anchors.isEmpty())
        assertEquals(0, f.commits)
    }

    @Test
    fun refusesToDropAnInitialPseudostateIntoARegionThatHasOne() {
        val f = Fixture(SAMPLE)
        val c = f.named("C")
        c.w += 200
        c.h += 200
        f.s.reindex()
        val region = Geometry.regionRects(f.m, c)[0].rect
        val init = f.topInitial
        init.x = region.x + region.w - 60
        init.y = region.y + region.h - 60
        val moving = setOf(init.id)
        assertNull(f.s.dropHighlight(init, moving)) // not C's region
        f.s.dropMoved(listOf(init), moving)
        assertEquals(f.s.rootRegion, init.parent)
        assertTrue(f.messages.isEmpty())
        TestFiles.write(f.m)
    }

    @Test
    fun leaveParentIsNotOfferedWhenTheOuterRegionHasOne() {
        val f = Fixture(SAMPLE)
        val c = f.named("C")
        f.s.selection = linkedSetOf(f.of(VertexType.Initial, c.regions[0].id).id)
        assertFalse(f.s.canLeaveParent)
        f.s.selection = linkedSetOf(f.named("C1").id)
        assertTrue(f.s.canLeaveParent)
    }

    @Test
    fun leavesOutAPastedInitialPseudostateThatWouldBeASecond() {
        val f = Fixture(SAMPLE)
        f.s.selection = linkedSetOf(f.topInitial.id)
        val data = f.s.collectSelection()
        val count = f.m.vertices.size
        f.s.paste(data)
        assertEquals(count, f.m.vertices.size)
        assertTrue(f.messages.isEmpty())
        assertEquals(0, f.commits)
        // With a state: the state is pasted, the initial pseudostate and its transition are left out.
        f.s.selection = linkedSetOf(f.topInitial.id, f.named("A").id)
        f.s.paste(f.s.collectSelection())
        assertEquals(count + 1, f.m.vertices.size)
        assertEquals(1, f.m.vertices.count { it.type == VertexType.Initial && it.parent == f.s.rootRegion })
        TestFiles.write(f.m)
    }

    @Test
    fun refusesASubmachineForAStateWithPointsAndNoneForAStateWithReferences() {
        val f = Fixture(
            """
            { "version": 1, "name": "M", "type": "behavioral", "initial": { "target": "S" }, "elements": [
              { "name": "S", "type": "state", "points": [ { "name": "p", "type": "entryPoint" } ] },
              { "name": "P", "type": "state", "submachine": "Payment.fsm", "points": [ { "name": "retry", "type": "entryPoint" } ] } ] }
            """.trimIndent(),
        )
        assertFalse(f.s.canBeSubmachine(f.named("S")))
        assertFalse(f.s.canBePlain(f.named("P")))
        assertTrue(f.s.canBeSubmachine(f.named("P")))
        assertTrue(f.s.canBePlain(f.named("S")))
        assertFalse(f.s.setSubmachine(f.named("S"), "Payment.fsm"))
        assertFalse(f.s.setSubmachine(f.named("P"), ""))
        assertEquals("", f.named("S").submachine)
        assertEquals("Payment.fsm", f.named("P").submachine)
        assertTrue(f.messages[0].startsWith("Remove the entry and exit points first"), f.messages[0])
        assertTrue(f.messages[1].startsWith("Remove the connection point references first"), f.messages[1])
        assertEquals(0, f.commits)
    }

    @Test
    fun refusesToReconnectOrReverseATransitionWithATriggerOntoAHistoryPseudostate() {
        val f = Fixture(SAMPLE)
        val c1 = f.named("C1")
        val history = f.of(VertexType.ShallowHistory)
        val (back, tick) = f.leaving(c1)
        assertFalse(f.s.canReconnect(tick, true, history))
        assertTrue(f.s.canReconnect(tick, false, f.named("C")))
        assertFalse(f.s.canReverse(back))
        f.s.reconnect(tick, true, history)
        assertEquals(c1.id, tick.source)
        f.s.reverse(back)
        assertEquals(c1.id, back.source)
        assertEquals(listOf(HISTORY_ONLY), f.messages)
        assertEquals(0, f.commits)
    }
}
