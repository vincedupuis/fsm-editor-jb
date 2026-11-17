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
    private fun example(file: String) = Load.loadModel(TestFiles.example(file), TestFiles.example("$file.layout"))

    /** Extracts [ids] into machine `Sub`, checking that both machines are valid and are saved without loss. */
    private fun extract(model: FsmModel, ids: List<String>, name: String = "Sub"): ExtractResult {
        val before = TestFiles.write(model).model
        val r = Extraction.extract(model, ids, name, "$name.fsm")
        assertEquals(before, TestFiles.write(model).model, "the model is left unchanged")
        val info = mapOf("$name.fsm" to SubmachineInfo(true, name, points = r.machine.machineConnectionPoints()))
        assertEquals(emptyList(), Validation.validate(r.parent, info).map { it.message })
        assertEquals(emptyList(), Validation.validate(r.machine).map { it.message })
        for (m in listOf(r.parent, r.machine)) {
            val w = TestFiles.write(m)
            val back = TestFiles.read(w.model, w.layout).model!!
            assertEquals(w.model, TestFiles.write(back).model, "saved and read")
            assertEquals(w.layout, TestFiles.write(back).layout, "saved and read")
        }
        return r
    }

    /** What a model says, without layout or element order. */
    private fun essentials(m: FsmModel): List<String> =
        m.vertices.sortedBy { it.id }.map { v ->
            listOf(v.id, v.type, v.name, v.parent, v.submachine, v.ref, v.pointKind, v.group, v.anchors, v.regions.map { it.id }).joinToString("|")
        } + m.transitions.sortedBy { it.id }.map { t -> listOf(t.id, t.source, t.target, t.kind, t.triggers, t.guard, t.action).joinToString("|") }

    private fun v(m: FsmModel, id: String) = m.vertices.firstOrNull { it.id == id }
    private fun t(m: FsmModel, id: String) = m.transitions.first { it.id == id }
    private fun named(m: FsmModel, type: VertexType, name: String) = m.vertices.first { it.type == type && it.name == name }
    private fun points(m: FsmModel, type: VertexType) = m.vertices.filter { it.type == type }.map { it.name }.sorted()
    private fun label(t: Transition) = listOf(t.triggers.joinToString(","), t.guard, t.action).joinToString("|")
    private fun from(m: FsmModel, source: String) = m.transitions.filter { it.source == source }
    private fun to(m: FsmModel, target: String) = m.transitions.filter { it.target == target }
    /** The reference on the submachine state for the new machine's point [pointId] (references name their points). */
    private fun refOf(r: ExtractResult, pointId: String): Vertex {
        val name = r.machine.vertices.first { it.id == pointId }.name
        return r.parent.vertices.first { it.type == VertexType.ConnectionPointRef && it.ref == name }
    }

    /** MediaPlayer without its deep history, so that On can be extracted. */
    private fun mediaPlayerWithoutHistory(): FsmModel {
        val m = example("MediaPlayer.fsm")
        m.vertices.removeAll { it.id == "On.deepHistory" }
        m.transitions.removeAll { it.source == "On.deepHistory" || it.target == "On.deepHistory" }
        for (v in m.vertices) v.anchors.remove("On.deepHistory")
        return m
    }

    @Test
    fun splitsCrossingTransitionsKeepingTheLabelOnTheSourceSide() {
        val r = extract(example("MediaPlayer.fsm"), listOf("Paused", "choice1*"))
        val state = v(r.parent, r.stateId)!!
        assertEquals(HREF, state.submachine)
        assertEquals("On.", state.parent)
        assertNull(v(r.parent, "Paused"))
        assertNotNull(v(r.machine, "Paused"))
        assertNotNull(v(r.machine, "choice1*"))
        assertEquals(listOf("toChoice", "toPaused"), points(r.machine, VertexType.EntryPoint))
        assertEquals(listOf("toFault", "toPlaying", "toStopped"), points(r.machine, VertexType.ExitPoint))

        // Incoming: the outer part keeps the label and goes to the entry reference; the inner part is plain.
        val toPaused = named(r.machine, VertexType.EntryPoint, "toPaused")
        assertEquals(refOf(r, toPaused.id).id, t(r.parent, "Playing#1").target)
        assertEquals("pause||", label(t(r.parent, "Playing#1")))
        assertEquals(listOf("Paused ||"), from(r.machine, toPaused.id).map { it.target + " " + label(it) })

        // Outgoing: the inner part keeps the label and goes to the exit point; the outer part is plain.
        val toStopped = named(r.machine, VertexType.ExitPoint, "toStopped")
        assertEquals(listOf("Paused#2 stop||", "Paused#4 after(5m)||saveBookmark()", "choice1*#1 |else|openTray()"), to(r.machine, toStopped.id).map { it.id + " " + label(it) })
        assertEquals(listOf("Stopped ||"), from(r.parent, refOf(r, toStopped.id).id).map { it.target + " " + label(it) })
        val toFault = named(r.machine, VertexType.ExitPoint, "toFault")
        assertEquals("|isTrayJammed()|", label(t(r.machine, "choice1*#2")))
        assertEquals(listOf("On.fault"), from(r.parent, refOf(r, toFault.id).id).map { it.target })

        // Inside transitions move as they are.
        assertEquals("volumeChanged||updateVolume()", label(t(r.machine, "Paused#3")))
        assertEquals(TransitionKind.Internal, t(r.machine, "Paused#3").kind)
    }

    @Test
    fun movesTheRegionInitialAlongAndUsesDefaultEntry() {
        val r = extract(example("MediaPlayer.fsm"), listOf("MediaPlayer.initial", "Off"))
        val initials = r.parent.vertices.filter { it.type == VertexType.Initial && it.parent == "MediaPlayer." }
        assertEquals(1, initials.size)
        assertEquals(listOf(r.stateId), from(r.parent, initials[0].id).map { it.target })
        assertEquals(r.stateId, t(r.parent, "On#1").target)
        assertEquals(emptyList(), points(r.machine, VertexType.EntryPoint))
        assertEquals(listOf("toFinal", "toOn"), points(r.machine, VertexType.ExitPoint))
        assertEquals(listOf("Off"), from(r.machine, "MediaPlayer.initial").map { it.target })
    }

    @Test
    fun turnsACompositeStateWithOneRegionIntoTheSubmachineState() {
        val r = extract(mediaPlayerWithoutHistory(), listOf("On"))
        assertEquals("On", r.stateId)
        val on = v(r.parent, "On")!!
        assertEquals(Triple("On", HREF, 0), Triple(on.name, on.submachine, on.regions.size))
        assertEquals("On" to "On", t(r.parent, "Off#1").target to t(r.parent, "On#1").source)
        // Its own exit point becomes the new machine's, with a reference to it by name in its place.
        val reference = v(r.parent, "On.fault")!!
        assertEquals(listOf(VertexType.ConnectionPointRef, "On", "fault", PointKind.Exit), listOf(reference.type, reference.parent, reference.ref, reference.pointKind))
        assertEquals("On.fault", t(r.parent, "On.fault#1").source)
        val point = v(r.machine, "On.fault")!!
        assertEquals(listOf(VertexType.ExitPoint, "On.", "fault"), listOf(point.type, point.parent, point.name))
        assertEquals(listOf("On."), r.machine.regions.map { it.id })
        assertEquals(2, v(r.machine, "Playing")!!.regions.size)
    }

    @Test
    fun movesACompositeStateWithSeveralRegionsWhole() {
        // Its name stays in the new machine, which can't have it too.
        assertEquals("PlayingMachine", Extraction.proposedName(example("MediaPlayer.fsm"), listOf("Playing")))
        val r = extract(example("MediaPlayer.fsm"), listOf("Playing"), "PlayingMachine")
        assertNotEquals("Playing", r.stateId)
        assertEquals("PlayingMachine", v(r.parent, r.stateId)!!.name)
        assertEquals(listOf("audio", "video"), v(r.machine, "Playing")!!.regions.map { it.id })
        assertEquals(listOf("toPlaying"), points(r.machine, VertexType.EntryPoint))
        assertEquals(listOf("toPaused"), points(r.machine, VertexType.ExitPoint))
    }

    @Test
    fun keepsSubmachineStatesAndTheirReferencesInsideTheExtractedPart() {
        val r = extract(example("Order.fsm"), listOf("Cart", "Payment"))
        assertEquals("Payment.fsm", v(r.machine, "Payment")!!.submachine)
        assertEquals(listOf("Payment.cancelled", "Payment.failed", "Payment.retry"), r.machine.vertices.filter { it.parent == "Payment" }.map { it.id }.sorted())
        // retryPayment came from outside to the entry reference 'retry': it now goes through an entry point.
        val entry = named(r.machine, VertexType.EntryPoint, "toPayment")
        assertEquals(listOf("Payment.retry"), from(r.machine, entry.id).map { it.target })
        assertEquals(refOf(r, entry.id).id, t(r.parent, "PaymentFailed#1").target)
    }

    @Test
    fun reanchorsOutsideCommentsAndKeepsGroupsAmongTheExtractedElements() {
        val m = example("MediaPlayer.fsm")
        v(m, "comment1*")!!.anchors = mutableListOf("On.deepHistory", "Paused", "Paused#3")
        v(m, "Paused")!!.group = "g1"
        v(m, "choice1*")!!.group = "g1"
        val r = extract(m, listOf("Paused")) // the group brings the choice along
        assertNotNull(v(r.machine, "choice1*"))
        assertEquals(listOf("On.deepHistory", r.stateId), v(r.parent, "comment1*")!!.anchors)
        assertEquals("g1" to "g1", v(r.machine, "Paused")!!.group to v(r.machine, "choice1*")!!.group)
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
            m.transitions.add(Transition("t2", "i2", "X").also { it.action = effect })
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
        assertEquals("PlayingMachine", Extraction.proposedName(m, listOf("Playing")))
        assertEquals("PausedMachine", Extraction.proposedName(m, listOf("Paused", "choice1*")))
        assertEquals("Submachine", Extraction.proposedName(m, listOf("Paused", "Stopped")))
    }

    @Test
    fun refusesWhatASubmachineCannotExpress() {
        fun refused(model: FsmModel, ids: List<String>, message: String) {
            val e = assertFailsWith<ExtractException> { Extraction.extract(model, ids, "Sub", HREF) }
            assertTrue(message in e.message!!, e.message)
        }
        val m = example("MediaPlayer.fsm")
        refused(m, listOf("On"), "history pseudostate across the boundary")
        refused(m, listOf("On.fault"), "Points on a state's border can't be extracted without their state ('fault')")
        refused(m, listOf("comment1*"), "Select the states and pseudostates to extract")
        refused(m, listOf("Off", "Paused"), "Select elements of one region")
        refused(m, emptyList(), "Select the states and pseudostates to extract")
        assertNull(Extraction.problem(m, listOf("Paused")))
        assertEquals("Select elements of one region to extract them.", Extraction.problem(m, listOf("Off", "Paused")))

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
        const val HREF = "Sub.fsm"
    }
}
