package com.vincegosoftware.fsmeditor.core

import com.vincegosoftware.fsmeditor.core.TestFiles.example
import com.vincegosoftware.fsmeditor.core.TestFiles.exampleModel
import com.vincegosoftware.fsmeditor.core.TestFiles.old
import com.vincegosoftware.fsmeditor.core.TestFiles.read
import com.vincegosoftware.fsmeditor.core.TestFiles.write
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The file format (the VS Code edition's test/unit/format.test.ts; FormatTests.cs of the Visual Studio edition). */
class FormatTest {
    private val exampleNames = listOf("MediaPlayer", "Order", "Payment")

    /** A model of most features: composite and orthogonal states, points, history, layout of every kind. */
    private fun featured(): FsmModel {
        val b = TestBuilder("Featured")
        b.state("A") { v ->
            v.x = 40.04; v.y = 60.06; v.w = 140.0; v.h = 60.0; v.entry = "a(); b()"; v.color = "#112233"
            v.itemColors = linkedMapOf("entry" to "#445566"); v.group = "g1"
        }
        b.state("B") { v -> v.x = 230.0; v.y = 60.0; v.w = 120.0; v.h = 60.0; v.group = "g1"; v.deferrable = mutableListOf("x", "y") }
        b.state("C", regions = 2) { v ->
            v.x = 400.33; v.y = 40.0; v.w = 340.0; v.h = 260.0; v.regionLayout = RegionLayout.Horizontal
            v.itemColors = linkedMapOf("background" to "#eeeeee")
        }
        b.model.vertices.first { it.id == "C" }.regions[0].size = 170.15
        b.state("C1", "C_r0") { v -> v.x = 420.0; v.y = 120.0; v.w = 100.0; v.h = 50.0 }
        b.state("C2", "C_r1") { v -> v.x = 600.0; v.y = 120.0; v.w = 100.0; v.h = 50.0 }
        val init = b.vertex(VertexType.Initial, "C_r0") { v -> v.x = 430.0; v.y = 90.0; v.w = 20.0; v.h = 20.0 }
        b.t(init, "C1") { t -> t.action = "go()"; t.points = mutableListOf(PointD(440.0, 140.05)) }
        b.init("C2", "C_r1")
        b.vertex(VertexType.DeepHistory, "C_r1", "H") { v -> v.x = 660.0; v.y = 70.0; v.w = 26.0; v.h = 26.0 }
        b.t("H", "C2")
        b.vertex(VertexType.ExitPoint, "C", "Cx") { v -> v.name = "out"; v.x = 732.0; v.y = 150.0; v.w = 16.0; v.h = 16.0; v.color = "#00ff00" }
        b.t("Cx", "A")
        b.vertex(VertexType.EntryPoint, "r_root", "M_in") { v -> v.name = "start"; v.x = 10.0; v.y = 10.0; v.w = 16.0; v.h = 16.0 }
        b.t("M_in", "A")
        b.vertex(VertexType.Choice, "r_root", "ch") { v -> v.x = 260.0; v.y = 200.0; v.w = 28.0; v.h = 28.0 }
        b.init("A")
        b.t("A", "B") { t ->
            t.triggers = mutableListOf("go", "after(1s)"); t.guard = "ok()"; t.action = "log()"; t.points = mutableListOf(PointD(200.0, 50.0))
            t.labelOffset = PointD(3.25, -8.0); t.color = "#aa0000"; t.itemColors = linkedMapOf("guard" to "#00aa00")
        }
        b.t("B", "ch") { t -> t.triggers = mutableListOf("pick") }
        b.t("ch", "C") { t -> t.guard = "p()" }
        b.t("ch", "H") { t -> t.guard = "else" }
        b.t("A", "A") { t -> t.kind = TransitionKind.Internal; t.triggers = mutableListOf("tick"); t.action = "t()" }
        b.t("A", "A") { t -> t.triggers = mutableListOf("again"); t.points = mutableListOf(PointD(160.0, 50.0), PointD(190.0, 50.0), PointD(190.0, 90.0)) }
        b.t("C1", "C") { t -> t.kind = TransitionKind.Local; t.triggers = mutableListOf("up"); t.points = mutableListOf(PointD(470.0, 280.0)) }
        b.vertex(VertexType.Comment, "r_root", "n") { v ->
            v.text = "Note \"quoted\"\n\twith a tab"; v.anchors = mutableListOf("A", "Cx", "H"); v.x = 40.0; v.y = 300.0; v.w = 160.0; v.h = 64.0
        }
        val ab = b.model.transitions.first { it.source == "A" && it.target == "B" }
        b.model.vertices.first { it.id == "n" }.anchors.add(ab.id)
        b.model.documentation = "Doc with \"quotes\" and é"
        return b.model.normalize()
    }

    // ---------------------------------------------------------------- examples

    @ParameterizedTest
    @ValueSource(strings = ["MediaPlayer", "Order", "Payment"])
    fun examplesReadAndWriteTheSameBytes(name: String) {
        val r = read(example("$name.fsm"), example("$name.fsm.layout"))
        assertEquals(0, r.placed)
        val w = write(r.model!!)
        assertEquals(example("$name.fsm"), w.model)
        assertEquals(example("$name.fsm.layout"), w.layout)
    }

    @Test
    fun examplesAreTheOldOnesConverted() {
        val results = Conversion.convertGroup(exampleNames.map { ConvertInput("/x/$it.fsm", old(it)) })
        for ((i, n) in exampleNames.withIndex()) {
            assertEquals(example("$n.fsm"), results[i].modelText)
            assertEquals(example("$n.fsm.layout"), results[i].layoutText)
            assertEquals(emptyList(), results[i].renames)
        }
        assertEquals(listOf("the context 'MediaPlayer' of the state machine"), results[0].losses)
        assertContains(example("MediaPlayer.fsm"), "{ \"type\": \"internal\", \"trigger\": \"volumeChanged\", \"action\": \"updateVolume()\" }")
        assertContains(example("Order.fsm"), "\"submachine\": \"Payment.fsm\"")
    }

    @Test
    fun examplesAreValid() {
        val payment = SubmachineInfo(
            true, "Payment",
            points = listOf(ConnectionPointInfo("retry", "retry", PointKind.Entry), ConnectionPointInfo("failed", "failed", PointKind.Exit), ConnectionPointInfo("cancelled", "cancelled", PointKind.Exit)),
        )
        for (name in exampleNames) assertEquals(emptyList(), Validation.validate(read(example("$name.fsm")).model!!, mapOf("Payment.fsm" to payment)), name)
    }

    // ---------------------------------------------------------------- writing

    @Test
    fun writeReadWriteGivesTheSameBytesAndOneSaveSettles() {
        val w1 = write(featured())
        val w2 = write(read(w1.model, w1.layout).model!!)
        assertEquals(w1.model, w2.model)
        assertEquals(w1.layout, w2.layout)
        assertEquals(w2.layout, write(read(w2.model, w2.layout).model!!).layout)
    }

    @Test
    fun keepsTheDrawing() {
        val w = write(featured())
        val r = read(w.model, w.layout).model!!
        fun byName(n: String) = r.vertices.first { it.name == n }
        assertEquals(40.0 to 60.1, byName("A").x to byName("A").y)
        assertEquals(Triple(400.3, 340.0, RegionLayout.Horizontal), Triple(byName("C").x, byName("C").w, byName("C").regionLayout))
        assertEquals(170.2, byName("C").regions[0].size)
        assertEquals(mapOf("entry" to "#445566"), byName("A").itemColors)
        assertEquals(byName("A").group, byName("B").group)
        val t = r.transitions.first { "go" in it.triggers }
        assertEquals(listOf(PointD(200.0, 50.0)), t.points)
        assertEquals(PointD(3.3, -8.0), t.labelOffset)
        assertEquals("#aa0000", t.color)
        assertEquals(mapOf("guard" to "#00aa00"), t.itemColors)
        assertEquals(listOf(PointD(160.0, 50.0), PointD(190.0, 50.0), PointD(190.0, 90.0)), r.transitions.first { "again" in it.triggers }.points)
        val point = r.vertices.first { it.type == VertexType.ExitPoint }
        assertEquals(listOf<Any>("out", 732.0, 150.0, "#00ff00"), listOf(point.name, point.x, point.y, point.color))
    }

    @Test
    fun writesTheFormattingOfTheFormat() {
        val w = write(featured())
        assertTrue(Regex("(?m)^ {2}\"documentation\": \"Doc with \\\\\"quotes\\\\\" and é\",$").containsMatchIn(w.model))
        assertContains(w.model, "\"text\": \"Note \\\"quoted\\\"\\n\\twith a tab\"")
        assertTrue(Regex("(?m)^ {8}\\{ \"type\": \"external\", \"trigger\": \"go, after\\(1s\\)\", \"guard\": \"ok\\(\\)\", \"action\": \"log\\(\\)\", \"target\": \"B\" \\},$").containsMatchIn(w.model))
        assertTrue(Regex("(?m)^ {12}\\{ \"x\": 200, \"y\": 50 \\}$").containsMatchIn(w.layout))
        assertTrue(Regex("(?m)^ {6}\"group\": \"g1\",$").containsMatchIn(w.layout))
        assertTrue(w.model.endsWith("}\n") && '\r' !in w.model)
        assertTrue(Regex("\"name\": \"choice1\\*\", \"type\": \"choice\"|\"name\": \"choice1\\*\",\\n\\s+\"type\": \"choice\"").containsMatchIn(w.model))
        assertContains(w.model, "\"name\": \"region1*\"")
    }

    /** The lines that differ between two texts of the same length in lines. */
    private fun diffLines(a: String, b: String): List<Pair<String, String>> {
        val x = a.split('\n')
        val y = b.split('\n')
        assertEquals(x.size, y.size)
        return x.indices.filter { x[it] != y[it] }.map { x[it] to y[it] }
    }

    @Test
    fun positionsAreRelative() {
        val m = featured()
        val before = write(m).layout
        val moved = m.copy()
        // Moving C moves what it contains and the bend points between them, as the editor does.
        val inside = setOf("C", "C1", "C2", "H", "Cx") + moved.vertices.filter { it.parent == "C_r0" || it.parent == "C_r1" }.map { it.id }
        for (v in moved.vertices.filter { it.id in inside }) {
            v.x += 33
            v.y -= 7
        }
        for (t in moved.transitions.filter { it.source in inside && it.target in inside }) t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + 33, it.y - 7) }
        assertEquals(listOf("      \"x\": 400.3," to "      \"x\": 433.3,", "      \"y\": 40," to "      \"y\": 33,"), diffLines(before, write(moved).layout))
        // Moving B alone (one end of A -> B and of B -> choice): only B's own place.
        val one = m.copy()
        one.vertices.first { it.id == "B" }.x += 50
        assertEquals(listOf("      \"x\": 230," to "      \"x\": 280,"), diffLines(before, write(one).layout))
    }

    @Test
    fun keepsAReferenceThatNamesNothing() {
        val text = example("Order.fsm").replace("\"target\": \"Shipped\"", "\"target\": \"Shiped\"")
        val r = read(text)
        val t = r.model!!.transitions.firstOrNull { it.target == Format.UNRESOLVED + "Shiped" }
        assertNotNull(t)
        assertTrue(Validation.validate(r.model).any { it.id == t.id && it.message == "Transition target 'Shiped' does not exist." })
        assertEquals(text, write(r.model).model)
    }

    @Test
    fun reportsWhatTheFormatCanNotHold() {
        val b = TestBuilder("Lost")
        b.state("A")
        b.init("A")
        b.vertex(VertexType.Initial, id = "i2")
        assertEquals(listOf("an unnamed initial pseudostate: a region has at most one"), Format.writeMachine(b.model).lost)
    }

    // ---------------------------------------------------------------- reading

    @Test
    fun reportsProblemsWithTheirLines() {
        val r = Format.readModel("{\n  \"format\": 1,\n  \"name\": \"X\",\n  \"type\": \"behavioral\",\n  \"elements\": [\n    { \"name\": \"A\", \"type\": \"state\", \"gaurd\": \"x\" }\n  ]\n}\n")
        assertNull(r.model)
        assertEquals(listOf("line 6: Unknown field 'gaurd' in a state."), r.problems!!.map { it.toString() })
        assertEquals(
            listOf("line 1: This file is in format 3: this version reads format 1 only."),
            Format.readModel("{ \"format\": 3, \"name\": \"X\", \"type\": \"behavioral\" }").problems!!.map { it.toString() },
        )
    }

    @Test
    fun readsCrlfAndASchemaFieldWhichIsNotWrittenBack() {
        val text = example("Payment.fsm").replaceFirst("{\n", "{\n  \"\$schema\": \"../schemas/fsm.schema.json\",\n").replace("\n", "\r\n")
        assertEquals(example("Payment.fsm"), write(read(text).model!!).model)
    }

    @Test
    fun placesElementsWithoutALayoutEntryTheSameWayOnEveryOpen() {
        val layout = example("MediaPlayer.fsm.layout")
        val start = layout.indexOf("    {\n      \"name\": \"Fault\"")
        val end = layout.indexOf("\n    }", start) + 6
        val without = layout.substring(0, start - 2) + layout.substring(end)
        val r1 = read(example("MediaPlayer.fsm"), without)
        val r2 = read(example("MediaPlayer.fsm"), without)
        assertNull(r1.layoutProblem)
        assertEquals(1, r1.placed)
        val fault = r1.model!!.vertices.first { it.name == "Fault" }
        assertEquals(listOf(r2.model!!.vertices.first { it.name == "Fault" }.x, 20.0, 140.0, 60.0), listOf(fault.x, fault.y, fault.w, fault.h))
        // No layout file: everything, level by level; the model file is untouched.
        val none = read(example("MediaPlayer.fsm"))
        assertEquals(none.model!!.vertices.size, none.placed)
        assertEquals(example("MediaPlayer.fsm"), write(none.model).model)
    }

    @Test
    fun ignoresLayoutEntriesThatMatchNothingAndAnUnreadableLayoutFile() {
        val extra = example("Payment.fsm.layout").replaceFirst("\"elements\": [", "\"elements\": [\n    { \"name\": \"Gone\", \"x\": 1, \"y\": 2 },")
        assertEquals(example("Payment.fsm.layout"), write(read(example("Payment.fsm"), extra).model!!).layout)
        val r = read(example("Payment.fsm"), "{ \"format\": 1, \"elements\": 3 }")
        assertEquals("line 1: Field 'elements' must be a list.", r.layoutProblem)
        assertEquals(r.model!!.vertices.size, r.placed)
    }

    @Test
    fun derivesTheIdsFsmCliGives() {
        val m = exampleModel("MediaPlayer")
        m.vertices.first { it.name == "Fault" }.id = "v_new"
        m.transitions.first { it.source == "Paused" && "stop" in it.triggers }.id = "t_new"
        for (x in m.transitions) {
            if (x.source == "Fault") x.source = "v_new"
            if (x.target == "Fault") x.target = "v_new"
        }
        val d = Format.derivedIds(m)
        assertEquals("Fault", d["v_new"])
        assertEquals("Paused#2", d["t_new"])
        assertEquals("On.deepHistory", d["On.deepHistory"])
    }

    @Test
    fun appliesALayoutToAModelReadAlone() {
        val r = Format.readModel(example("Order.fsm"))
        Format.applyLayout(r, example("Order.fsm.layout"))
        assertEquals(0, r.placed)
        assertEquals(example("Order.fsm.layout"), write(r.model!!).layout)
    }

    // ---------------------------------------------------------------- review fixes

    private fun modelText(body: String) = "{\n  \"format\": 1,\n  \"name\": \"M\",\n  \"type\": \"behavioral\",\n$body\n}\n"

    @Test
    fun resolvesCommentAnchors() {
        val r = read(
            modelText(
                """  "initial": { "target": "A" },
  "elements": [
    { "name": "A", "type": "state", "transitions": [{ "type": "external", "trigger": "go", "target": "B" }, { "type": "internal", "trigger": "tick" }] },
    { "name": "B", "type": "state" },
    { "name": "c", "type": "comment", "anchors": ["A", { "source": "A", "trigger": "go", "target": "B" }, { "source": "A", "trigger": "tick" }, "Nope", { "source": "A", "trigger": "x", "target": "B" }] }
  ]""",
            ),
        )
        val anchors = r.model!!.vertices.first { it.name == "c" }.anchors
        assertEquals(listOf("A", "A#1", "A#2"), anchors.take(3))
        assertEquals(Format.UNRESOLVED + "Nope", anchors[3])
        assertTrue(anchors[4].startsWith(Format.UNRESOLVED_ANCHOR))
        val w = Format.writeMachine(r.model)
        assertEquals(emptyList(), w.lost)
        assertContains(w.model, "\"Nope\"")
        assertContains(w.model, "{ \"source\": \"A\", \"trigger\": \"x\", \"target\": \"B\" }")
    }

    @Test
    fun keepsTheLayoutOfEachOfTwoTransitionsWithTheSameTriggerAndTarget() {
        val b = TestBuilder("M")
        b.vertex(VertexType.Choice, id = "c") { v -> v.name = "c"; v.x = 10.0; v.y = 10.0; v.w = 28.0; v.h = 28.0 }
        b.state("Next") { v -> v.x = 200.0; v.y = 10.0 }
        b.init("Next")
        b.t("c", "Next") { t -> t.guard = "x()"; t.action = "a()" }
        b.t("c", "Next") { t -> t.guard = "else"; t.action = "b()"; t.points = mutableListOf(PointD(100.0, 120.0)); t.color = "#ff0000" }
        val w = write(b.model)
        val back = read(w.model, w.layout).model!!.transitions.filter { it.source == "c" }
        assertEquals(listOf(Triple("x()", 0, ""), Triple("else", 1, "#ff0000")), back.map { Triple(it.guard, it.points.size, it.color) })
        assertEquals(w.layout, write(read(w.model, w.layout).model!!).layout)
    }

    @Test
    fun findsALayoutEntryByTheTriggerAsTheModelFileWritesIt() {
        val text = modelText(
            """  "initial": { "target": "A" },
  "elements": [{ "name": "A", "type": "state", "transitions": [{ "type": "external", "trigger": "a,b", "target": "B" }] }, { "name": "B", "type": "state" }]""",
        )
        val layout = """{ "format": 1, "elements": [{ "name": "A", "x": 10, "y": 10, "transitions": [{ "trigger": "a,b", "target": "B", "bends": [{ "x": 5, "y": 5 }] }] }] }"""
        assertEquals(listOf(PointD(5.0, 5.0)), read(text, layout).model!!.transitions.first { it.source == "A" }.points)
    }

    @Test
    fun anInitialPseudostatesColorIsItsTransitions() {
        val b = TestBuilder("M")
        b.state("A") { v -> v.x = 100.0; v.y = 10.0 }
        b.init("A")
        b.model.transitions[0].color = "#00ff00"
        val w = write(b.model)
        val r = read(w.model, w.layout).model!!
        assertEquals("", r.vertices.first { it.type == VertexType.Initial }.color)
        assertEquals("#00ff00", r.transitions[0].color)
    }

    @Test
    fun placesAStateGrownToHoldWhatItContainsBeforeTheElementsBesideIt() {
        val r = read(
            modelText(
                """  "initial": { "target": "A" },
  "elements": [
    { "name": "A", "type": "state", "initial": { "target": "A1" }, "elements": [{ "name": "A1", "type": "state" }, { "name": "A2", "type": "state" }, { "name": "A3", "type": "state" }] },
    { "name": "B", "type": "state" }
  ]""",
            ),
        )
        val a = r.model!!.vertices.first { it.name == "A" }
        val bs = r.model.vertices.first { it.name == "B" }
        assertTrue(bs.x >= a.x + a.w, "B (x ${bs.x}) is beside A (${a.x}..${a.x + a.w})")
    }

    @Test
    fun writesPlacesThatAreNumbersUnderAStateWhosePlaceIsNotOne() {
        val b = TestBuilder("M")
        b.state("A", regions = 1) { v -> v.x = Double.POSITIVE_INFINITY; v.y = 10.0; v.w = 300.0; v.h = 200.0 }
        b.state("B", "A_r0") { v -> v.x = 100.0; v.y = 50.0 }
        val m = b.model.copy()
        Format.assignNames(m)
        val w = Format.writeMachine(m)
        assertEquals(listOf("the place of state 'A', which is not a number"), w.lost)
        assertNull(read(w.model, w.layout).layoutProblem)
    }

    @Test
    fun keepsANameUsedTwiceWithoutFixAndNumbersItWhenConverting() {
        val b = TestBuilder("M")
        b.state("Idle")
        b.state("Idle", id = "Idle2")
        val m = b.model.copy()
        assertEquals(emptyList(), Format.assignNames(m))
        assertEquals(listOf("Idle", "Idle"), m.vertices.map { it.name })
        assertEquals(listOf("Idle2"), Format.assignNames(m, true).map { it.to })
    }

    @Test
    fun resolvesATargetOrAnAnchorThatNamedNothingOnceItNamesSomething() {
        val r = read(
            modelText(
                """  "initial": { "target": "A" },
  "elements": [
    { "name": "A", "type": "state", "transitions": [{ "type": "external", "trigger": "go", "target": "Foo" }] },
    { "name": "c", "type": "comment", "anchors": ["Foo", { "source": "A", "trigger": "go", "target": "Foo" }] }
  ]""",
            ),
        ).model!!
        val t = r.transitions.first { it.source == "A" }
        assertEquals(Format.UNRESOLVED + "Foo", t.target)
        assertFalse(Format.resolveReferences(r, Format.writeMachine(r)))
        r.vertices.add(Vertex("v_foo", VertexType.State, "Foo", r.regions[0].id, 300.0, 10.0, 140.0, 60.0))
        assertTrue(Format.resolveReferences(r, Format.writeMachine(r)))
        assertEquals("v_foo", t.target)
        assertEquals(listOf("v_foo", t.id), r.vertices.first { it.name == "c" }.anchors)
    }

    // ---------------------------------------------------------------- JSON

    @Test
    fun jsonReaderGivesLinesAndTheEditorsMessages() {
        val n = Json.parse("\uFEFF{\n  \"a\": [1, 2.5e1, -0],\n  \"b\": { \"c\": \"\\u00e9\\ud83d\\ude00\" }\n}")
        assertEquals(2, n.field("a")!!.line)
        assertEquals(25.0, n.field("a")!!.value.items!![1].number)
        assertEquals("é😀", n.field("b")!!.value.field("c")!!.value.str)
        fun fails(text: String, line: Int, message: String) {
            val e = assertFailsWith<JsonSyntaxException> { Json.parse(text) }
            assertEquals(line to message, e.line to e.message)
        }
        fails("{ \"a\": 1, \"a\": 2 }", 1, "Field 'a' is given twice.")
        fails("{\n\"a\": 01 }", 2, "Invalid number.")
        fails("{ \"a\": 1e400 }", 1, "Invalid number.")
        fails("[\"a\tb\"]", 1, "Control character '\\u0009' in a text: write it as an escape.")
        fails("[\"\\ud800\"]", 1, "Invalid escape in a text.")
        fails("{\n", 2, "Unexpected end of the file.")
        fails("[1] x", 1, "Unexpected character 'x'.")
        fails("\u00a0", 1, "Unexpected character '\u00a0'.")
    }

    @Test
    fun isOldFormatSeesXml() {
        assertTrue(Format.isOldFormat(old("Order")))
        assertTrue(Format.isOldFormat("\uFEFF \n<x/>"))
        assertFalse(Format.isOldFormat(example("Order.fsm")))
        assertFalse(Format.isOldFormat(""))
    }
}
