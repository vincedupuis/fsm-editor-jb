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
    fun offersATransitionLeavingAPseudostateNoTriggersAndNoOtherKind() {
        val f = Fixture(SAMPLE)
        val branch = f.leaving(f.of(VertexType.Choice))[0]
        assertFalse(DiagramSession.takesTriggers(f.of(VertexType.Choice), f.named("A")))
        assertTrue(DiagramSession.takesTriggers(f.named("A"), f.named("C")))
        assertEquals(listOf(TransitionKind.External), f.s.kindOptions(branch))
        assertEquals(DiagramSession.NO_TRIGGERS, f.s.applyLabel(branch, "go [ok()]"))
        assertNull(f.s.applyLabel(branch, "[ready()]"))
        // A, simple: internal, not local; C, composite, to C1 inside it: local too.
        assertEquals(listOf(TransitionKind.External, TransitionKind.Internal), f.s.kindOptions(f.leaving(f.named("A"))[0]))
        val inside = Transition(id = "t_in", source = f.named("C").id, target = f.named("C1").id)
        f.m.transitions.add(inside)
        assertEquals(listOf(TransitionKind.External, TransitionKind.Local, TransitionKind.Internal), f.s.kindOptions(inside))
    }

    /** A → fork → X, Y (the regions of C, X2 beside X); X, Y → join → A; C's entry point in → X; a choice → A. */
    private fun segments(type: String) = """
        { "version": 1, "name": "S", "type": "$type",
          "initial": { "target": "A" },
          "elements": [
            { "name": "A", "type": "state", "transitions": [ { "type": "external", "target": "f*" } ] },
            { "name": "f*", "type": "fork", "transitions": [ { "type": "external", "target": "X" }, { "type": "external", "target": "Y" } ] },
            { "name": "C", "type": "state", "points": [ { "name": "in", "type": "entryPoint", "transitions": [ { "type": "local", "target": "X" } ] } ],
              "regions": [
                { "name": "r1", "elements": [ { "name": "X", "type": "state", "transitions": [ { "type": "external", "target": "j*" } ] }, { "name": "X2", "type": "state" } ] },
                { "name": "r2", "elements": [ { "name": "Y", "type": "state", "transitions": [ { "type": "external", "target": "j*" } ] } ] } ] },
            { "name": "j*", "type": "join", "transitions": [ { "type": "external", "target": "A" } ] },
            { "name": "ch*", "type": "choice", "transitions": [ { "type": "external", "target": "A" } ] }
          ] }
    """.trimIndent()

    @Test
    fun offersNoGuardOrTriggersOnAForkAnEntryPointOrAJoinAndDrawsNoneTheyCantHave() {
        for (type in listOf("behavioral", "protocol")) {
            val f = Fixture(segments(type))
            fun v(id: String) = f.m.vertices.single { it.id == id }
            fun t(id: String) = f.m.transitions.single { it.id == id }
            assertFalse(DiagramSession.takesGuard(v("f*"), v("X")))
            assertFalse(DiagramSession.takesGuard(v("C.in"), v("X")))
            assertFalse(DiagramSession.takesGuard(v("X"), v("j*")))
            assertFalse(DiagramSession.takesTriggers(v("X"), v("j*")))
            assertTrue(DiagramSession.takesGuard(v("ch*"), v("A")))
            val guard = if (type == "protocol") "precondition" else "guard"
            assertEquals("A transition leaving a fork or an entry point, or entering a join, cannot have a $guard.", f.s.applyLabel(t("X#1"), "[ok()]"))
            assertEquals(DiagramSession.NO_TRIGGERS, f.s.applyLabel(t("X#1"), "go"))
            // Drawn: a fork to a choice, a choice to a join, a second transition from the entry point into r1.
            assertFalse(f.s.canConnect(v("f*"), "ch*"))
            assertFalse(f.s.canConnect(v("ch*"), "j*"))
            assertFalse(f.s.canConnect(v("C.in"), "X2"))
            assertTrue(f.s.canConnect(v("C.in"), "Y"))
            assertEquals(0, f.commits)
            // Drawn from an entry point: local, the only kind it can be (UML's state_is_external).
            val drawn = f.s.connect(v("C.in"), "Y")!!
            assertEquals(TransitionKind.Local, drawn.kind)
            assertEquals(listOf(TransitionKind.Local), f.s.kindOptions(drawn))
        }
    }

    @Test
    fun drawsNoTransitionFromAnEntryPointOutOfItsStateFromAnExitPointIntoItOrFromTheMachinesExitPoint() {
        // A (A → ch* on go), ch* ([else] → A), C (its points in and out, X inside), the machine's exit point stop.
        val f = Fixture(
            """
            { "version": 1, "name": "M", "type": "behavioral",
              "points": [ { "name": "stop", "type": "exitPoint" } ],
              "initial": { "target": "A" },
              "elements": [
                { "name": "A", "type": "state", "transitions": [ { "type": "external", "trigger": "go", "target": "ch*" } ] },
                { "name": "ch*", "type": "choice", "transitions": [ { "type": "external", "guard": "else", "target": "A" } ] },
                { "name": "C", "type": "state", "points": [ { "name": "in", "type": "entryPoint" }, { "name": "out", "type": "exitPoint" } ],
                  "elements": [ { "name": "X", "type": "state" } ] }
              ] }
            """.trimIndent(),
        )
        fun v(id: String) = f.m.vertices.single { it.id == id }
        assertFalse(f.s.canConnect(v("C.in"), "A"))
        assertTrue(f.s.canConnect(v("C.in"), "X"))
        assertFalse(f.s.canConnect(v("C.out"), "X"))
        assertTrue(f.s.canConnect(v("C.out"), "A"))
        assertFalse(f.s.canStartFrom(v("M.stop")))
        // The [else] branch can't be reversed: A would be its source.
        assertFalse(f.s.canReverse(f.leaving(v("ch*"))[0]))
        // The entry point swapped for an exit point: in and out then both leave C.
        f.m.transitions.add(Transition(id = "t_in", source = "C.in", target = "X"))
        assertFalse(VertexType.ExitPoint in f.s.typeOptions(v("C.in")))
        assertEquals(0, f.commits)
    }

    @Test
    fun offersAProtocolMachineNoHistoryAndElseOnlyOnABranchsPrecondition() {
        // A protocol machine: A (A → choice on go), the choice ([ok()] → B, → A), B.
        val f = Fixture(
            """
            { "version": 1, "name": "P", "type": "protocol", "context": "Door",
              "initial": { "target": "A" },
              "elements": [
                { "name": "A", "type": "state", "transitions": [ { "type": "external", "trigger": "go", "target": "ch*" } ] },
                { "name": "ch*", "type": "choice", "transitions": [
                  { "type": "external", "precondition": "ok()", "target": "B" },
                  { "type": "external", "target": "A" } ] },
                { "name": "B", "type": "state" }
              ] }
            """.trimIndent(),
        )
        assertEquals("Door", f.m.context)
        assertFalse(Tools.allowed(f.m, "shallowHistory"))
        assertFalse(Tools.allowed(f.m, "deepHistory"))
        assertTrue(Tools.allowed(f.m, "choice"))
        assertNull(f.s.createVertex("shallowHistory", PointD(400.0, 300.0)))
        assertEquals(listOf(VertexType.Choice, VertexType.Junction), f.s.typeOptions(f.of(VertexType.Choice)))
        assertEquals(listOf(VertexType.Initial, VertexType.Choice, VertexType.Junction), f.s.typeOptions(f.topInitial))
        assertTrue(f.s.applyLabel(f.leaving(f.named("A"))[0], "[else] go")!!.startsWith("Precondition:"))
        val branch = f.leaving(f.of(VertexType.Choice))[1]
        assertNull(f.s.applyLabel(branch, "[else]"))
        assertEquals("else", branch.precondition)
        assertTrue(TestFiles.write(f.m).model.contains("\"context\": \"Door\""))
        f.m.kind = MachineKind.Behavioral
        assertTrue(Tools.allowed(f.m, "shallowHistory"))
        // A new machine of either kind.
        assertTrue(TestFiles.write(FsmModel.createDefault("Door", MachineKind.Protocol)).model.contains("\"type\": \"protocol\""))
        assertTrue(TestFiles.write(FsmModel.createDefault("Door")).model.contains("\"type\": \"behavioral\""))
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
        assertTrue(f.s.canConnect(f.named("note*"), init.id))
        assertTrue(f.s.canConnect(f.named("note*"), f.leaving(init)[0].id))
        assertTrue(f.s.canConnect(f.named("note*"), f.named("A").id))
        assertFalse(f.s.canConnect(f.named("A"), init.id))
    }

    @Test
    fun attachesACommentToAnInitialPseudostateItsTransitionARegionAndTheMachine() {
        val f = Fixture(SAMPLE)
        val note = f.named("note*")
        val region = f.named("C").regions[0].id
        assertTrue(f.s.canConnect(note, region))
        assertTrue(f.s.canConnect(note, DiagramSession.MACHINE_ANCHOR))
        f.s.connect(note, f.topInitial.id)
        f.s.connect(note, f.leaving(f.topInitial)[0].id)
        f.s.connect(note, region)
        f.s.connect(note, DiagramSession.MACHINE_ANCHOR)
        assertTrue(f.messages.isEmpty())
        // C's region, unnamed, gets a generated name, which references it.
        val model = TestFiles.write(f.m).model
        assertTrue(model.contains("\"M.initial\","), model)
        assertTrue(model.contains("{ \"source\": \"M.initial\", \"target\": \"A\" }"), model)
        assertTrue(model.contains("\"region1*\","), model)
        assertTrue(model.contains("\"M\"\n"), model)
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
