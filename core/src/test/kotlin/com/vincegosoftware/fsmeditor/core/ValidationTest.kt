package com.vincegosoftware.fsmeditor.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Builds models in code: states, pseudostates and transitions with generated ids unless given. */
private class ModelBuilder(name: String) {
    val model = FsmModel().apply {
        this.name = name
        regions.add(Region("r_root"))
    }
    private var n = 0

    fun vertex(type: VertexType, parent: String = "r_root", id: String? = null, name: String = ""): String {
        val vid = id ?: "v${++n}"
        model.vertices.add(Vertex(vid, type, name, parent, 0.0, 0.0, 10.0, 10.0))
        return vid
    }

    /** A state; [regions] > 0 makes it composite, with region ids `<id>_r0`, `<id>_r1`, ... */
    fun state(name: String, parent: String = "r_root", regions: Int = 0): String {
        val id = vertex(VertexType.State, parent, name, name)
        model.vertices.last().regions = MutableList(regions) { Region("${id}_r$it") }
        return id
    }

    fun t(source: String, target: String, trigger: String? = null, guard: String = "", kind: TransitionKind = TransitionKind.External): String {
        val id = "t${++n}"
        model.transitions.add(Transition(id, source, target, kind).also {
            if (trigger != null) it.triggers.add(trigger)
            it.guard = guard
        })
        return id
    }

    /** Initial pseudostate of [region] targeting [target]. */
    fun init(target: String, region: String = "r_root") {
        t(vertex(VertexType.Initial, region), target)
    }
}

/** Validation rules, with the same cases and messages as the VS Code extension's test/unit/validation.test.ts. */
class ValidationTest {
    /** The issues about cycles of pseudostates, as id|severity|message. */
    private fun cycles(b: ModelBuilder) =
        Validation.validate(b.model).filter { "form a cycle" in it.message }.map { "${it.id}|${it.severity.key}|${it.message}" }

    /** A machine with states A and B, A entered first: `A -e-> <first>` and `<last> -[else]-> B` are up to the test. */
    private fun machine() = ModelBuilder("M").apply {
        state("A")
        state("B")
        init("A")
    }

    @Test
    fun reportsTwoChoicesLeadingToEachOtherOnceOnTheFirstWithTheShortestCycle() {
        val b = machine()
        val c1 = b.vertex(VertexType.Choice, id = "c1", name = "c1")
        val c2 = b.vertex(VertexType.Choice, id = "c2", name = "c2")
        b.t("A", c1, trigger = "e")
        b.t(c1, c2, guard = "g()")
        b.t(c1, "B", guard = "else")
        b.t(c2, c1, guard = "h()")
        b.t(c2, "B", guard = "else")
        assertEquals(listOf("c1|error|Choices form a cycle without a state: 'c1' -> 'c2' -> 'c1'."), cycles(b))
    }

    @Test
    fun reportsAChoiceTargetingItself() {
        val b = machine()
        val c = b.vertex(VertexType.Choice, id = "c", name = "c")
        b.t("A", c, trigger = "e")
        b.t(c, c, guard = "g()")
        b.t(c, "B", guard = "else")
        assertEquals(listOf("c|error|Choices form a cycle without a state: 'c' -> 'c'."), cycles(b))
    }

    @Test
    fun namesTheKindsOfPseudostatesInTheCycleAndUnnamedOnesByTheirKind() {
        val b = machine()
        val c = b.vertex(VertexType.Choice, id = "c")
        val j = b.vertex(VertexType.Junction, id = "j")
        b.t("A", j, trigger = "e")
        b.t(j, c)
        b.t(c, j, guard = "g()")
        b.t(c, "B", guard = "else")
        assertEquals(listOf("c|error|Junctions and choices form a cycle without a state: choice -> junction -> choice."), cycles(b))
    }

    @Test
    fun reportsOneIssueForCyclesSharingPseudostatesThroughTheFirstTransitions() {
        val b = machine()
        val j1 = b.vertex(VertexType.Junction, id = "j1", name = "j1")
        val j2 = b.vertex(VertexType.Junction, id = "j2", name = "j2")
        val j3 = b.vertex(VertexType.Junction, id = "j3", name = "j3")
        val j4 = b.vertex(VertexType.Junction, id = "j4", name = "j4")
        b.t("A", j1, trigger = "e")
        b.t(j1, j2, guard = "a()")
        b.t(j1, j3, guard = "b()")
        b.t(j1, "B", guard = "else")
        b.t(j2, j4)
        b.t(j4, j1)
        b.t(j3, j1)
        assertEquals(listOf("j1|error|Junctions form a cycle without a state: 'j1' -> 'j3' -> 'j1'."), cycles(b))
    }

    @Test
    fun reportsSeparateCyclesSeparatelyEvenWhenOneLeadsToTheOther() {
        val b = machine()
        val j1 = b.vertex(VertexType.Junction, id = "j1", name = "j1")
        val j2 = b.vertex(VertexType.Junction, id = "j2", name = "j2")
        b.t("A", j1, trigger = "e")
        b.t(j1, j1, guard = "a()")
        b.t(j1, j2, guard = "else")
        b.t(j2, j2, guard = "a()")
        b.t(j2, "B", guard = "else")
        assertEquals(
            listOf(
                "j1|error|Junctions form a cycle without a state: 'j1' -> 'j1'.",
                "j2|error|Junctions form a cycle without a state: 'j2' -> 'j2'.",
            ),
            cycles(b),
        )
    }

    @Test
    fun reportsCyclesThroughHistoryDefaultsForksAndTheEntryAndExitPointsOfStates() {
        // A history default leading back to the history through a choice.
        var b = machine()
        b.state("S", regions = 1)
        b.state("S1", "S_r0")
        val h = b.vertex(VertexType.ShallowHistory, "S_r0", id = "h")
        val c = b.vertex(VertexType.Choice, "S_r0", id = "c", name = "c")
        b.t("A", h, trigger = "e")
        b.t(h, c)
        b.t(c, h, guard = "g()")
        b.t(c, "S1", guard = "else")
        assertEquals(listOf("h|error|Choices and history pseudostates form a cycle without a state: shallow history -> 'c' -> shallow history."), cycles(b))

        // An entry point of a state and a choice inside it.
        b = machine()
        b.state("S", regions = 1)
        b.state("S1", "S_r0")
        val ep = b.vertex(VertexType.EntryPoint, "S", id = "ep", name = "ep")
        val c2 = b.vertex(VertexType.Choice, "S_r0", id = "c", name = "c")
        b.t("A", ep, trigger = "e")
        b.t(ep, c2)
        b.t(c2, ep, guard = "g()")
        b.t(c2, "S1", guard = "else")
        assertEquals(listOf("ep|error|Choices and entry points form a cycle without a state: 'ep' -> 'c' -> 'ep'."), cycles(b))

        // An exit point of a state and a junction outside it.
        b = machine()
        b.state("S", regions = 1)
        b.state("S1", "S_r0")
        val xp = b.vertex(VertexType.ExitPoint, "S", id = "xp", name = "xp")
        val j = b.vertex(VertexType.Junction, id = "j", name = "j")
        b.t("S1", xp, trigger = "e")
        b.t(xp, j)
        b.t(j, xp, guard = "g()")
        b.t(j, "B", guard = "else")
        assertEquals(listOf("xp|error|Junctions and exit points form a cycle without a state: 'xp' -> 'j' -> 'xp'."), cycles(b))

        // A fork and a choice.
        b = machine()
        b.state("S", regions = 2)
        b.state("S1", "S_r0")
        b.state("S2", "S_r1")
        val f = b.vertex(VertexType.Fork, id = "f", name = "f")
        val c3 = b.vertex(VertexType.Choice, id = "c", name = "c")
        b.t("A", c3, trigger = "e")
        b.t(c3, f, guard = "g()")
        b.t(c3, "B", guard = "else")
        b.t(f, "S1")
        b.t(f, c3)
        assertEquals(listOf("f|error|Choices and forks form a cycle without a state: 'f' -> 'c' -> 'f'."), cycles(b))
    }

    @Test
    fun acceptsLoopsThroughStatesTheMachinesOwnEntryPointsJoinsAndInternalTransitions() {
        val b = machine()
        // Through a state.
        val c = b.vertex(VertexType.Choice, id = "c", name = "c")
        b.t("A", c, trigger = "e")
        b.t(c, "A", guard = "g()")
        b.t(c, "B", guard = "else")
        // An internal transition (an error on a pseudostate) does not go anywhere.
        b.t(c, c, kind = TransitionKind.Internal)
        // Through an entry point of the machine, which ends the transition.
        val ep = b.vertex(VertexType.EntryPoint, id = "ep", name = "ep")
        val j = b.vertex(VertexType.Junction, id = "j", name = "j")
        b.t(ep, j)
        b.t(j, ep, guard = "g()")
        b.t(j, "B", guard = "else")
        // Through a join.
        b.state("P", regions = 2)
        b.state("P1", "P_r0")
        b.state("P2", "P_r1")
        val jn = b.vertex(VertexType.Join, id = "jn", name = "jn")
        val c2 = b.vertex(VertexType.Choice, id = "c2", name = "c2")
        b.t("P1", jn)
        b.t("P2", jn)
        b.t(jn, c2)
        b.t(c2, jn, guard = "g()")
        b.t(c2, "B", guard = "else")
        assertEquals(emptyList(), cycles(b))
    }
}
