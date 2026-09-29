package com.vincegosoftware.fsmeditor.core

import java.io.File
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditingTest {
    private fun newSession() = DiagramSession(FsmModel.createDefault("Test"))

    private fun example(file: String) = DiagramSession(Xmi.fromXmi(File(ParityTest.EXAMPLES, file).readText()))

    @Test
    fun createsStatesInTheRegionUnderThePoint() {
        val s = newSession()
        var commits = 0
        s.onCommitted { commits++ }
        val comp = s.createVertex("composite", PointD(500.0, 300.0))!!
        assertEquals("Composite", comp.name)
        assertEquals(1, comp.regions.size)
        val inner = s.createVertex("state", PointD(comp.x + 60, comp.y + comp.h - 40))!!
        assertEquals(comp.regions[0].id, inner.parent)
        assertEquals("State", inner.name)
        assertEquals(2, commits)
        assertEquals(setOf(inner.id), s.selection)
    }

    @Test
    fun machineLevelEntryPointsAreNamed() {
        val s = newSession()
        val p = s.createVertex("entryPoint", PointD(900.0, 900.0))!!
        assertEquals(s.rootRegion, p.parent)
        assertEquals("in", p.name)
        assertEquals(1, s.model.machineConnectionPoints().size)
    }

    @Test
    fun connectionPointRefsNeedASubmachineState() {
        val s = newSession()
        var message: String? = null
        s.onMessage { message = it }
        assertNull(s.createVertex("connectionPointRef", PointD(900.0, 900.0)))
        assertEquals("Connection point references go on the border of a submachine state.", message)
    }

    @Test
    fun connectRefusesInvalidTransitions() {
        val s = newSession()
        val messages = ArrayList<String>()
        s.onMessage { messages.add(it) }
        val fin = s.createVertex("final", PointD(600.0, 100.0))!!
        val idle = s.index.vertex("v_idle")!!
        assertNull(s.connect(fin, idle.id))
        assertNull(s.connect(idle, "v_init"))
        assertNotNull(s.connect(idle, fin.id))
        assertEquals(listOf("A final state cannot have outgoing transitions.", "An initial pseudostate cannot be the target of a transition."), messages)
    }

    @Test
    fun deletingAStateRemovesItsContentsTransitionsAndAnchors() {
        val s = example("MediaPlayer.fsm")
        val composite = s.model.vertices.first { it.type == VertexType.State && it.regions.isNotEmpty() }
        val inside = s.index.descendants(composite).map { it.id }
        assertTrue(inside.isNotEmpty())
        s.selection = linkedSetOf(composite.id)
        s.deleteSelection()
        assertTrue(s.model.vertices.none { it.id == composite.id || it.id in inside })
        assertTrue(s.model.transitions.none { it.source == composite.id || it.target == composite.id || it.source in inside || it.target in inside })
        assertTrue(s.model.vertices.all { v -> v.anchors.all { s.index.vertex(it) != null || s.index.transition(it) != null } })
    }

    @Test
    fun pasteCopiesWithNewIdsAndOffset() {
        val s = example("MediaPlayer.fsm")
        val composite = s.model.vertices.first { it.type == VertexType.State && it.regions.isNotEmpty() }
        s.selection = linkedSetOf(composite.id)
        val data = s.collectSelection()!!
        val count = s.model.vertices.size
        val transitions = s.model.transitions.size

        // Through the clipboard text, as between two editors.
        s.paste(ClipboardJson.tryRead(ClipboardJson.write(data)))

        assertEquals(count + data.vertices.size, s.model.vertices.size)
        assertEquals(transitions + data.transitions.size, s.model.transitions.size)
        val copy = s.index.vertex(s.selection.single())!!
        assertNotEquals(composite.id, copy.id)
        assertEquals(composite.x + 20, copy.x)
        assertEquals(composite.name, copy.name)
        assertEquals(s.index.descendants(composite).size, s.index.descendants(copy).size)
        assertTrue(Validation.validate(s.model).none { it.message.startsWith("Duplicate id") })
    }

    @Test
    fun clipboardJsonRoundTrips() {
        val s = example("Order.fsm")
        s.selection = s.model.vertices.mapTo(LinkedHashSet()) { it.id }
        val data = s.collectSelection()!!
        val back = ClipboardJson.tryRead(ClipboardJson.write(data))!!
        assertEquals(ClipboardJson.write(data), ClipboardJson.write(back))
        assertNull(ClipboardJson.tryRead("hello"))
        assertNull(ClipboardJson.tryRead("{\"vertices\":[]}"))
    }

    @Test
    fun readsClipboardTextOfTheOtherEditors() {
        val text = "{\"fsmClipboard\":1,\"vertices\":[{\"id\":\"v_a\",\"type\":\"state\",\"name\":\"A\",\"parent\":\"r_root\",\"x\":10,\"y\":20,\"w\":140,\"h\":60,\"regions\":[],\"entry\":\"go()\"}," +
            "{\"id\":\"v_b\",\"type\":\"shallowHistory\",\"name\":\"\",\"parent\":\"r_root\",\"x\":200,\"y\":20,\"w\":26,\"h\":26,\"regions\":[]}]," +
            "\"transitions\":[{\"id\":\"t1\",\"source\":\"v_b\",\"target\":\"v_a\",\"kind\":\"external\",\"triggers\":[],\"guard\":\"\",\"effect\":\"\",\"points\":[{\"x\":1.5,\"y\":2}],\"labelOffset\":{\"x\":0,\"y\":-4}}]}"
        val data = ClipboardJson.tryRead(text)!!
        assertEquals(2, data.vertices.size)
        assertEquals(VertexType.ShallowHistory, data.vertices[1].type)
        assertEquals("go()", data.vertices[0].entry)
        assertEquals(PointD(1.5, 2.0), data.transitions[0].points[0])
        assertEquals(PointD(0.0, -4.0), data.transitions[0].labelOffset)
    }

    @Test
    fun labelsParseAndRefuseBadText() {
        val s = newSession()
        val t = s.index.transition("t_init")!!
        val idle = s.index.vertex("v_idle")!!
        val t2 = s.connect(idle, idle.id)!!
        assertNull(s.applyLabel(t2, "play, after(2s) [isReady() && !isBusy()] / doIt(); log()"))
        assertEquals(listOf("play", "after(2s)"), t2.triggers)
        assertEquals("isReady() && !isBusy()", t2.guard)
        assertEquals("doIt(); log()", t2.effect)
        assertEquals("play, after(2s) [isReady() && !isBusy()] / doIt(); log()", Labels.transitionLabel(s.model, t2))
        assertTrue(s.applyLabel(t2, "go [volume > 3]")!!.startsWith("Guard:"))
        assertEquals("isReady() && !isBusy()", t2.guard)
        assertTrue(s.applyLabel(t, "[else]")!!.startsWith("Guard:"))
    }

    @Test
    fun addAllReferencesUsesTheSubmachinePoints() {
        val s = newSession()
        val sub = s.createVertex("submachine", PointD(400.0, 300.0))!!
        s.setSubmachine(sub, "Payment.fsm#sm")
        s.submachines = mapOf(
            "Payment.fsm#sm" to SubmachineInfo(
                true,
                "Payment",
                points = listOf(
                    ConnectionPointInfo("p1", "retry", PointKind.Entry),
                    ConnectionPointInfo("p2", "failed", PointKind.Exit),
                    ConnectionPointInfo("p3", "cancelled", PointKind.Exit),
                ),
            ),
        )
        s.addAllReferences(sub)
        val refs = s.refsOf(sub)
        assertEquals(3, refs.size)
        assertTrue(refs.filter { it.pointKind == PointKind.Entry }.all { it.centerX == sub.x })
        assertTrue(refs.filter { it.pointKind == PointKind.Exit }.all { it.centerX == sub.x + sub.w })
        assertTrue(s.freePoints(sub).isEmpty())
        assertEquals("Payment", s.machineLabel("Payment.fsm#sm"))
    }

    @Test
    fun regionsAreAddedAndRemovedWithTheirContents() {
        val s = newSession()
        val idle = s.index.vertex("v_idle")!!
        s.addRegion(idle)
        val region = idle.regions.single()
        val inner = s.createVertex("state", PointD(idle.x + 50, idle.y + idle.h - 30))!!
        assertEquals(region.id, inner.parent)
        s.removeRegion(idle, region.id)
        assertTrue(idle.regions.isEmpty())
        assertNull(s.index.vertex(inner.id))
    }

    @Test
    fun svgExportDrawsEveryElement() {
        val s = example("MediaPlayer.fsm")
        val svg = SvgExport.export(s)
        assertTrue(svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertEquals(s.model.vertices.size, countOf(svg, "<g class=\"vertex "))
        assertEquals(s.model.transitions.count { Geometry.isDrawn(s.index, it) }, countOf(svg, "<g class=\"transition\">"))
        // Well-formed XML.
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
    }

    private fun countOf(text: String, part: String): Int {
        var n = 0
        var i = text.indexOf(part)
        while (i >= 0) {
            n++
            i = text.indexOf(part, i + part.length)
        }
        return n
    }

    @Test
    fun hrefsAreRelativeAndEncoded() {
        val root = System.getProperty("java.io.tmpdir")
        val doc = Paths.get(root, "models", "Order.fsm").toString()
        val other = Paths.get(root, "models", "sub dir", "Payment.fsm").toString()
        val href = Hrefs.forFile(doc, other, "sm")
        assertEquals("sub%20dir/Payment.fsm#sm", href)
        val (file, id) = Hrefs.resolve(doc, href)
        assertEquals(Paths.get(other).toAbsolutePath().normalize().toString(), file)
        assertEquals("sm", id)
        assertEquals("../Order.fsm#sm", Hrefs.forFile(other, doc, "sm"))
        assertEquals("sub dir/é#x", Hrefs.decodeUri(Hrefs.encodeUri("sub dir/é#x")))
        assertEquals("100%", Hrefs.decodeUri("100%"))
    }

    @Test
    fun numbersPrintLikeTheFiles() {
        assertEquals("0", Num.format(-0.0))
        assertEquals("12.5", Num.format(12.5))
        assertEquals("-3", Num.format(-3.0))
        assertEquals("0.1", Num.format(0.1))
        assertEquals("0.0001", Num.format(0.0001))
        assertEquals("123456789.5", Num.format(123456789.5))
        assertEquals(0.3, Num.r1(0.25))
        assertEquals(-2.0, Num.round(-2.5))
    }
}
