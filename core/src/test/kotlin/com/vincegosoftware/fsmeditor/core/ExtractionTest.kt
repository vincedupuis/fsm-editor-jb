package com.vincegosoftware.fsmeditor.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extract to Submachine, as test/unit/extract.test.ts of the VS Code edition checks it. */
class ExtractionTest {
    private fun example(file: String) = Xmi.fromXmi(File(ParityTest.EXAMPLES, file).readText())

    /** Extracts [ids] into machine `Sub`, checking that both machines are valid and survive an XMI round trip. */
    private fun extract(model: FsmModel, ids: List<String>, name: String = "Sub"): ExtractResult {
        val before = Xmi.toXmi(model)
        val r = Extraction.extract(model, ids, name, "$name.fsm#sm")
        assertEquals(before, Xmi.toXmi(model), "the model is left unchanged")
        val info = mapOf("$name.fsm#sm" to SubmachineInfo(true, name, points = r.machine.machineConnectionPoints()))
        assertEquals(emptyList(), Validation.validate(r.parent, info).map { it.message })
        assertEquals(emptyList(), Validation.validate(r.machine).map { it.message })
        for (m in listOf(r.parent, r.machine)) assertEquals(essentials(m), essentials(Xmi.fromXmi(Xmi.toXmi(m))), "XMI round trip")
        return r
    }

    /** What a model says, without layout or element order. */
    private fun essentials(m: FsmModel): List<String> =
        m.vertices.sortedBy { it.id }.map { v ->
            listOf(v.id, v.type, v.name, v.parent, v.submachine, v.ref, v.pointKind, v.group, v.anchors, v.regions.map { it.id }).joinToString("|")
        } + m.transitions.sortedBy { it.id }.map { t -> listOf(t.id, t.source, t.target, t.kind, t.triggers, t.guard, t.effect).joinToString("|") }

    private fun v(m: FsmModel, id: String) = m.vertices.firstOrNull { it.id == id }
    private fun t(m: FsmModel, id: String) = m.transitions.first { it.id == id }
    private fun named(m: FsmModel, type: VertexType, name: String) = m.vertices.first { it.type == type && it.name == name }
    private fun points(m: FsmModel, type: VertexType) = m.vertices.filter { it.type == type }.map { it.name }.sorted()
    private fun label(t: Transition) = listOf(t.triggers.joinToString(","), t.guard, t.effect).joinToString("|")
    private fun from(m: FsmModel, source: String) = m.transitions.filter { it.source == source }
    private fun to(m: FsmModel, target: String) = m.transitions.filter { it.target == target }
    private fun refOf(r: ExtractResult, pointId: String) = r.parent.vertices.first { it.type == VertexType.ConnectionPointRef && it.ref == pointId }

    /** MediaPlayer without its deep history, so that On can be extracted. */
    private fun mediaPlayerWithoutHistory(): FsmModel {
        val m = example("MediaPlayer.fsm")
        m.vertices.removeAll { it.id == "v_hist" }
        m.transitions.removeAll { it.source == "v_hist" || it.target == "v_hist" }
        for (v in m.vertices) v.anchors.remove("v_hist")
        return m
    }

    @Test
    fun splitsCrossingTransitionsKeepingTheLabelOnTheSourceSide() {
        val r = extract(example("MediaPlayer.fsm"), listOf("v_paused", "v_choice"))
        val state = v(r.parent, r.stateId)!!
        assertEquals(HREF, state.submachine)
        assertEquals("r_on", state.parent)
        assertNull(v(r.parent, "v_paused"))
        assertNotNull(v(r.machine, "v_paused"))
        assertNotNull(v(r.machine, "v_choice"))
        assertEquals(listOf("toChoice", "toPaused"), points(r.machine, VertexType.EntryPoint))
        assertEquals(listOf("toFault", "toPlaying", "toStopped"), points(r.machine, VertexType.ExitPoint))

        // Incoming: the outer part keeps the label and goes to the entry reference; the inner part is plain.
        val toPaused = named(r.machine, VertexType.EntryPoint, "toPaused")
        assertEquals(refOf(r, toPaused.id).id, t(r.parent, "t8").target)
        assertEquals("pause||", label(t(r.parent, "t8")))
        assertEquals(listOf("v_paused ||"), from(r.machine, toPaused.id).map { it.target + " " + label(it) })

        // Outgoing: the inner part keeps the label and goes to the exit point; the outer part is plain.
        val toStopped = named(r.machine, VertexType.ExitPoint, "toStopped")
        assertEquals(listOf("t10 stop||", "t12 |else|openTray()", "t23 after(5m)||saveBookmark()"), to(r.machine, toStopped.id).map { it.id + " " + label(it) })
        assertEquals(listOf("v_stopped ||"), from(r.parent, refOf(r, toStopped.id).id).map { it.target + " " + label(it) })
        val toFault = named(r.machine, VertexType.ExitPoint, "toFault")
        assertEquals("|isTrayJammed()|", label(t(r.machine, "t13")))
        assertEquals(listOf("v_fault_xp"), from(r.parent, refOf(r, toFault.id).id).map { it.target })

        // Inside transitions move as they are.
        assertEquals("volumeChanged||updateVolume()", label(t(r.machine, "t22")))
        assertEquals(TransitionKind.Internal, t(r.machine, "t22").kind)
    }

    @Test
    fun movesTheRegionInitialAlongAndUsesDefaultEntry() {
        val r = extract(example("MediaPlayer.fsm"), listOf("v_init", "v_off"))
        val initials = r.parent.vertices.filter { it.type == VertexType.Initial && it.parent == "r_root" }
        assertEquals(1, initials.size)
        assertEquals(listOf(r.stateId), from(r.parent, initials[0].id).map { it.target })
        assertEquals(r.stateId, t(r.parent, "t3").target)
        assertEquals(emptyList(), points(r.machine, VertexType.EntryPoint))
        assertEquals(listOf("toFinal", "toOn"), points(r.machine, VertexType.ExitPoint))
        assertEquals(listOf("v_off"), from(r.machine, "v_init").map { it.target })
    }

    @Test
    fun turnsACompositeStateWithOneRegionIntoTheSubmachineState() {
        val r = extract(mediaPlayerWithoutHistory(), listOf("v_on"))
        assertEquals("v_on", r.stateId)
        val on = v(r.parent, "v_on")!!
        assertEquals(Triple("On", HREF, 0), Triple(on.name, on.submachine, on.regions.size))
        assertEquals("v_on" to "v_on", t(r.parent, "t2").target to t(r.parent, "t3").source)
        // Its own exit point becomes the new machine's, with a reference of the same id in its place.
        val reference = v(r.parent, "v_fault_xp")!!
        assertEquals(listOf(VertexType.ConnectionPointRef, "v_on", "v_fault_xp", PointKind.Exit), listOf(reference.type, reference.parent, reference.ref, reference.pointKind))
        assertEquals("v_fault_xp", t(r.parent, "t14").source)
        val point = v(r.machine, "v_fault_xp")!!
        assertEquals(listOf(VertexType.ExitPoint, "r_on", "fault"), listOf(point.type, point.parent, point.name))
        assertEquals(listOf("r_on"), r.machine.regions.map { it.id })
        assertEquals(2, v(r.machine, "v_playing")!!.regions.size)
    }

    @Test
    fun movesACompositeStateWithSeveralRegionsWhole() {
        val r = extract(example("MediaPlayer.fsm"), listOf("v_playing"), "Playing")
        assertNotEquals("v_playing", r.stateId)
        assertEquals("Playing", v(r.parent, r.stateId)!!.name)
        assertEquals(listOf("r_audio", "r_video"), v(r.machine, "v_playing")!!.regions.map { it.id })
        assertEquals(listOf("toPlaying"), points(r.machine, VertexType.EntryPoint))
        assertEquals(listOf("toPaused"), points(r.machine, VertexType.ExitPoint))
    }

    @Test
    fun keepsSubmachineStatesAndTheirReferencesInsideTheExtractedPart() {
        val r = extract(example("Order.fsm"), listOf("o_cart", "o_pay"))
        assertEquals("Payment.fsm#sm", v(r.machine, "o_pay")!!.submachine)
        assertEquals(listOf("o_ref_cancel", "o_ref_failed", "o_ref_retry"), r.machine.vertices.filter { it.parent == "o_pay" }.map { it.id }.sorted())
        // retryPayment came from outside to the entry reference 'retry': it now goes through an entry point.
        val entry = named(r.machine, VertexType.EntryPoint, "toPayment")
        assertEquals(listOf("o_ref_retry"), from(r.machine, entry.id).map { it.target })
        assertEquals(refOf(r, entry.id).id, t(r.parent, "ot5").target)
    }

    @Test
    fun reanchorsOutsideCommentsAndKeepsGroupsAmongTheExtractedElements() {
        val m = example("MediaPlayer.fsm")
        v(m, "v_note")!!.anchors = mutableListOf("v_hist", "v_paused", "t22")
        v(m, "v_paused")!!.group = "g1"
        v(m, "v_choice")!!.group = "g1"
        val r = extract(m, listOf("v_paused")) // the group brings the choice along
        assertNotNull(v(r.machine, "v_choice"))
        assertEquals(listOf("v_hist", r.stateId), v(r.parent, "v_note")!!.anchors)
        assertEquals("g1" to "g1", v(r.machine, "v_paused")!!.group to v(r.machine, "v_choice")!!.group)
    }

    @Test
    fun usesDefaultEntryOnlyWhenTheInitialTransitionHasNoGuardOrEffect() {
        for (effect in listOf("", "log()")) {
            val m = FsmModel()
            m.name = "M"
            m.regions.add(Region("r_root"))
            m.vertices.add(Vertex("O", VertexType.State, "O", "r_root"))
            m.vertices.add(Vertex("S", VertexType.State, "S", "r_root").also { it.regions.add(Region("S_r0")) })
            m.vertices.add(Vertex("X", VertexType.State, "X", "S_r0"))
            m.vertices.add(Vertex("i1", VertexType.Initial, "", "r_root"))
            m.vertices.add(Vertex("i2", VertexType.Initial, "", "S_r0"))
            m.transitions.add(Transition("t1", "i1", "O"))
            m.transitions.add(Transition("t2", "i2", "X").also { it.effect = effect })
            m.transitions.add(Transition("t3", "O", "X").also { it.triggers.add("e") })
            val r = extract(m, listOf("S"))
            val e = t(r.parent, "t3")
            if (effect.isNotEmpty()) assertEquals(VertexType.ConnectionPointRef, v(r.parent, e.target)!!.type)
            else assertEquals("S", e.target)
        }
    }

    @Test
    fun proposesTheNameOfTheExtractedState() {
        val m = example("MediaPlayer.fsm")
        assertEquals("Playing", Extraction.proposedName(m, listOf("v_playing")))
        assertEquals("Paused", Extraction.proposedName(m, listOf("v_paused", "v_choice")))
        assertEquals("Submachine", Extraction.proposedName(m, listOf("v_paused", "v_stopped")))
    }

    @Test
    fun refusesWhatASubmachineCannotExpress() {
        fun refused(model: FsmModel, ids: List<String>, message: String) {
            val e = assertFailsWith<ExtractException> { Extraction.extract(model, ids, "Sub", HREF) }
            assertTrue(message in e.message!!, e.message)
        }
        val m = example("MediaPlayer.fsm")
        refused(m, listOf("v_on"), "history pseudostate across the boundary")
        refused(m, listOf("v_fault_xp"), "Points on a state's border can't be extracted without their state ('fault')")
        refused(m, listOf("v_note"), "Select the states and pseudostates to extract")
        refused(m, listOf("v_off", "v_paused"), "Select elements of one region")
        refused(m, emptyList(), "Select the states and pseudostates to extract")
        assertNull(Extraction.problem(m, listOf("v_paused")))
        assertEquals("Select elements of one region to extract them.", Extraction.problem(m, listOf("v_off", "v_paused")))

        val b = FsmModel()
        b.name = "M"
        b.regions.add(Region("r_root"))
        b.vertices.add(Vertex("S", VertexType.State, "S", "r_root").also { it.regions.add(Region("S_r0")) })
        b.vertices.add(Vertex("X", VertexType.State, "X", "S_r0"))
        b.vertices.add(Vertex("Y", VertexType.State, "Y", "S_r0"))
        b.transitions.add(Transition("t1", "S", "Y", TransitionKind.Local).also { it.triggers.add("e") })
        refused(b, listOf("Y"), "Make the transition from 'S' to 'Y' external first")
    }

    private companion object {
        const val HREF = "Sub.fsm#sm"
    }
}
