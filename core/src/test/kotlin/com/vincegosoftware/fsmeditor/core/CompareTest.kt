package com.vincegosoftware.fsmeditor.core

import com.vincegosoftware.fsmeditor.core.TestFiles.exampleModel
import com.vincegosoftware.fsmeditor.core.TestFiles.old
import com.vincegosoftware.fsmeditor.core.TestFiles.read
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Comparing two versions of a machine (the VS Code edition's test/unit/compare.test.ts; CompareTests.cs). */
class CompareTest {
    private fun v(m: FsmModel, name: String) = m.vertices.first { it.name == name }

    private fun summary(older: FsmModel, newer: FsmModel) =
        Compare.compareModels(older, newer).map { Triple(it.kind.name.lowercase(), it.label, Compare.describeChange(it, true)) }

    @Test
    fun findsNothingBetweenEqualMachines() = assertEquals(emptyList(), Compare.compareModels(exampleModel("MediaPlayer"), exampleModel("MediaPlayer")))

    @Test
    fun aLayoutOnlyChangeIsHiddenByDefaultAndMovingAStateChangesNothingInsideIt() {
        val a = exampleModel("MediaPlayer")
        val b = a.copy()
        for (x in b.vertices) {
            if (x.name == "On" || a.vertices.firstOrNull { it.id == x.parent }?.name == "On" || x.parent in listOf("On.", "audio", "video") ||
                x.name in listOf("Stopped", "Paused", "Playing", "Stereo", "Mono", "HD", "SD", "choice1*")
            ) {
                x.x += 25
            }
        }
        for (t in b.transitions) {
            if (t.points.isNotEmpty() && b.vertices.first { it.id == t.source }.x != a.vertices.first { it.id == t.source }.x &&
                b.vertices.firstOrNull { it.id == t.target }?.x != a.vertices.firstOrNull { it.id == t.target }?.x
            ) {
                t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + 25, it.y) }
            }
        }
        val changes = Compare.compareModels(a, b)
        val on = changes.first { it.label == "state 'On'" }
        assertEquals(listOf("x: 340 → 365"), Compare.describeChange(on, true))
        assertFalse(Compare.isModelChange(on))
        assertEquals(emptyList(), changes.filter { Compare.isModelChange(it) })
        assertTrue(changes.none { it.label == "state 'Stopped'" })
    }

    @Test
    fun aRenameIsOneElementRemovedAndOneAdded() {
        val a = exampleModel("Order")
        val b = a.copy()
        v(b, "Shipped").name = "Delivered"
        assertEquals(
            listOf(
                ChangeKind.Added to "state 'Delivered'", ChangeKind.Added to "transition Delivered -> final1",
                ChangeKind.Removed to "state 'Shipped'", ChangeKind.Removed to "transition Shipped -> final1",
            ),
            Compare.compareModels(a, b).filter { it.kind != ChangeKind.Changed }.map { it.kind to it.label },
        )
        val t = Compare.compareModels(a, b).first { it.kind == ChangeKind.Changed && it.what == "transition" }
        assertEquals(listOf("target: Shipped → Delivered"), Compare.describeChange(t, false))
    }

    @Test
    fun aChangeInEachKindOfPropertyIsListed() {
        val a = exampleModel("MediaPlayer")
        val b = a.copy()
        v(b, "Off").entry = "sleep()"
        v(b, "Paused").deferrable = mutableListOf("eject")
        val t = b.transitions.first { it.triggers.firstOrNull() == "eject" }
        t.guard = "hasDisc()"
        t.action = "beep()"
        b.transitions.first { it.triggers.firstOrNull() == "powerOff" }.kind = TransitionKind.Local
        v(b, "comment1*").text = "Other text"
        b.documentation = "Changed"
        assertEquals(
            listOf(
                Triple("changed", "the state machine", listOf("documentation: Example UML state machine: composite and orthogonal state… → Changed")),
                Triple("changed", "comment", listOf("text: Deep history restores the\nlast active playback substates\n… → Other text")),
                Triple("changed", "state 'Off'", listOf("entry: powerDown() → sleep()")),
                Triple("changed", "state 'Paused'", listOf("defer: (none) → eject")),
                Triple("changed", "transition On -> Off (powerOff)", listOf("kind: external → local")),
                Triple("changed", "transition Stopped -> choice1 (eject)", listOf("guard: (none) → hasDisc()", "action: (none) → beep()")),
            ),
            summary(a, b),
        )
    }

    @Test
    fun aRetargetedTransitionIsChangedNotRemovedAndAdded() {
        val a = exampleModel("Payment")
        val b = a.copy()
        b.transitions.first { it.triggers.firstOrNull() == "declined" }.target = v(b, "Captured").id
        assertEquals(listOf(Triple("changed", "transition Authorizing -> Captured (declined)", listOf("target: Declined → Captured"))), summary(a, b))
    }

    @Test
    fun theDiffOfAConversionShowsNothing() {
        val converted = Conversion.convertGroup(listOf(ConvertInput("/x/MediaPlayer.fsm", old("MediaPlayer"))))[0]
        assertEquals(emptyList(), Compare.compareModels(converted.model!!, exampleModel("MediaPlayer")))
    }

    @Test
    fun movingAnElementToAnotherRegionOfItsStateIsAChangeOfTheModel() {
        fun mk(inFirst: Boolean): FsmModel {
            val r1 = if (inFirst) """{ "name": "X", "type": "state" }, { "name": "Y", "type": "state" }""" else """{ "name": "Y", "type": "state" }"""
            val r2 = if (inFirst) """{ "name": "Z", "type": "state" }""" else """{ "name": "Z", "type": "state" }, { "name": "X", "type": "state" }"""
            return read("""{ "format": 1, "name": "M", "type": "behavioral", "elements": [{ "name": "S", "type": "state", "regions": [{ "name": "R1", "elements": [$r1] }, { "name": "R2", "elements": [$r2] }] }] }""").model!!
        }
        val x = Compare.compareModels(mk(true), mk(false)).filter { Compare.isModelChange(it) }
        assertEquals(
            listOf("state 'X'" to listOf("in: 'S', region 'R1' → 'S', region 'R2'")),
            x.map { c -> c.label to c.properties.filter { !it.layout }.map { "${it.name}: ${it.old} → ${it.new}" } },
        )
    }
}
