package com.vincegosoftware.fsmeditor.core

import java.io.File
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val text = "{\"fsmClipboard\":1,\"vertices\":[{\"id\":\"v_a\",\"type\":\"state\",\"name\":\"A\",\"parent\":\"r_root\",\"x\":10,\"y\":20,\"w\":140,\"h\":60,\"regions\":[],\"entry\":\"go()\",\"color\":\"#e5484d\",\"itemColors\":{\"entry\":\"#30a46c\",\"exit\":\"bad\"}}," +
            "{\"id\":\"v_b\",\"type\":\"shallowHistory\",\"name\":\"\",\"parent\":\"r_root\",\"x\":200,\"y\":20,\"w\":26,\"h\":26,\"regions\":[]}]," +
            "\"transitions\":[{\"id\":\"t1\",\"source\":\"v_b\",\"target\":\"v_a\",\"kind\":\"external\",\"triggers\":[],\"guard\":\"\",\"effect\":\"\",\"points\":[{\"x\":1.5,\"y\":2}],\"labelOffset\":{\"x\":0,\"y\":-4},\"itemColors\":{\"guard\":\"#0090ff\"}}]}"
        val data = ClipboardJson.tryRead(text)!!
        assertEquals(2, data.vertices.size)
        assertEquals(VertexType.ShallowHistory, data.vertices[1].type)
        assertEquals("go()", data.vertices[0].entry)
        assertEquals(PointD(1.5, 2.0), data.transitions[0].points[0])
        assertEquals(PointD(0.0, -4.0), data.transitions[0].labelOffset)
        assertEquals("#e5484d", data.vertices[0].color)
        assertEquals(mapOf("entry" to "#30a46c"), data.vertices[0].itemColors)
        assertEquals("", data.transitions[0].color)
        assertEquals("#0090ff", data.transitions[0].itemColors["guard"])
        assertTrue("\"color\":\"#e5484d\",\"itemColors\":{\"entry\":\"#30a46c\"}" in ClipboardJson.write(data))
    }

    @Test
    fun splitsTheFillOfACompositeState() {
        val s = example("MediaPlayer.fsm")
        val v = s.model.vertices.first { it.type == VertexType.State && it.regions.isNotEmpty() }
        assertFalse("linearGradient" in SvgExport.export(s))
        // The title color tints the header only.
        s.setColor(v, null, "#0090ff")
        val svg = SvgExport.export(s)
        assertTrue("style=\"stop-color:#d5ecfd\"/><stop offset=" in svg)
        assertTrue("style=\"stop-color:#fdfdfd\"/></linearGradient>" in svg)
        assertTrue("fill:url(#fsm-fill-1);stroke:#0090ff" in svg)
        // The background color tints the regions.
        s.setColor(v, "background", "#30a46c")
        assertTrue("style=\"stop-color:#dcefe6\"/></linearGradient>" in SvgExport.export(s))
        val back = Xmi.fromXmi(Xmi.toXmi(s.model)).vertices.first { it.id == v.id }
        assertEquals("#0090ff", back.color)
        assertEquals("#30a46c", back.itemColors["background"])
        // One color for both is a plain fill.
        s.setColor(v, "background", "#0090ff")
        assertFalse("linearGradient" in SvgExport.export(s))
    }

    @Test
    fun setsAndClearsColors() {
        val s = example("MediaPlayer.fsm")
        val v = s.model.vertices.first { it.type == VertexType.State }
        val t = s.model.transitions[0]
        s.setColor(v, null, "#e5484d")
        s.setColor(v, "entry", "#30a46c")
        s.setColor(t, "guard", "#0090ff")
        s.setColor(t, null, "not a color")
        val back = Xmi.fromXmi(Xmi.toXmi(s.model))
        val bv = back.vertices.first { it.id == v.id }
        assertEquals("#e5484d", bv.color)
        assertEquals("#30a46c", bv.itemColors["entry"])
        assertEquals("", back.transitions.first { it.id == t.id }.color)
        assertEquals("#0090ff", back.transitions.first { it.id == t.id }.itemColors["guard"])
        assertTrue("fill:#30a46c" in SvgExport.export(s))
        s.setColor(v, "entry", null)
        s.setColor(v, null, null)
        assertEquals("", v.color)
        assertTrue(v.itemColors.isEmpty())
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
    fun stateActivitiesWrapToTheStateWidth() {
        val s = newSession()
        val v = s.index.vertex("v_idle")!!
        v.entry = "startTheVeryLongRunningActivity(); andThenSomethingElse(); andFinallyCleanUp()"
        v.w = 300.0
        val wide = Geometry.activityRows(s.model, v)
        val wideHeight = Geometry.headerHeight(s.model, v)
        v.w = 120.0
        val narrow = Geometry.activityRows(s.model, v)
        assertTrue(narrow.size > wide.size)
        assertTrue(Geometry.headerHeight(s.model, v) > wideHeight)
        assertTrue(narrow.all { Geometry.estimateLabelWidth(it) <= 120.0 - 16 || !it.contains(' ') })
        assertEquals(narrow.size * Geometry.LINE_HEIGHT + 6 + Geometry.NAME_HEIGHT, Geometry.headerHeight(s.model, v))
        assertTrue(s.minimumSize(v).second >= Geometry.headerHeight(s.model, v))
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
    private fun addState(
        s: DiagramSession, id: String, parent: String, x: Double, y: Double, w: Double = 140.0, h: Double = 60.0,
        regions: Int = 0, layout: RegionLayout = RegionLayout.Vertical,
    ): Vertex {
        val v = Vertex(id, VertexType.State, id, parent, x, y, w, h)
        v.regionLayout = layout
        repeat(regions) { v.regions.add(Region(id + "_r" + it)) }
        s.model.vertices.add(v)
        s.reindex()
        return v
    }

    @Test
    fun alignsTheSelectedStatesOnTheClickedOne() {
        val s = newSession()
        val a = addState(s, "a", s.rootRegion, 1000.0, 1000.0)
        val b = addState(s, "b", s.rootRegion, 1300.0, 1120.0, 100.0, 40.0)
        val c = addState(s, "c", s.rootRegion, 1600.0, 900.0)
        val init = s.index.vertex("v_init")!!
        val initAt = init.x to init.y
        s.selection = linkedSetOf(a.id, b.id, c.id, init.id)
        var commits = 0
        s.onCommitted { commits++ }
        assertTrue(s.align(b.id, horizontally = true))
        assertEquals(1, commits)
        assertEquals(1000.0 to 1110.0, a.x to a.y)
        assertEquals(1300.0 to 1120.0, b.x to b.y)
        assertEquals(1600.0 to 1110.0, c.x to c.y)
        assertTrue(s.align(a.id, horizontally = false))
        assertEquals(1000.0 to 1110.0, a.x to a.y)
        assertEquals(1020.0 to 1120.0, b.x to b.y)
        assertEquals(1000.0 to 1110.0, c.x to c.y)
        // Pseudostates are left alone, and a single state has nothing to align with.
        assertEquals(initAt, init.x to init.y)
        s.selection = linkedSetOf(a.id, init.id)
        assertFalse(s.align(a.id, horizontally = true))
    }

    @Test
    fun aligningMovesAStateWithItsContents() {
        val s = newSession()
        val comp = addState(s, "comp", s.rootRegion, 1000.0, 1000.0, 280.0, 190.0, regions = 1)
        val inner = addState(s, "inner", "comp_r0", 1040.0, 1080.0)
        val other = addState(s, "other", "comp_r0", 1100.0, 1100.0)
        val t = Transition("t1", inner.id, other.id).also { it.points = mutableListOf(PointD(1200.0, 1060.0)) }
        s.model.transitions.add(t)
        val b = addState(s, "b", s.rootRegion, 1500.0, 1300.0)
        s.selection = linkedSetOf(comp.id, inner.id, b.id)
        assertTrue(s.align(b.id, horizontally = true))
        assertEquals(1235.0, comp.y)
        assertEquals(1315.0, inner.y)
        assertEquals(1335.0, other.y)
        assertEquals(PointD(1200.0, 1295.0), t.points[0])
        assertEquals(280.0 to 190.0, comp.w to comp.h)
    }

    @Test
    fun aligningGrowsTheRegionsThatNoLongerHoldTheirStates() {
        val s = newSession()
        val outer = addState(s, "outer", s.rootRegion, 1000.0, 1000.0, 400.0, 300.0, regions = 1)
        val comp = addState(s, "comp", "outer_r0", 1020.0, 1050.0, 280.0, 190.0, regions = 1)
        val inner = addState(s, "inner", "comp_r0", 1040.0, 1100.0)
        val top = addState(s, "top", s.rootRegion, 600.0, 1500.0)
        s.selection = linkedSetOf(inner.id, top.id)
        assertTrue(s.align(top.id, horizontally = true))
        assertEquals(1500.0, inner.y)
        assertEquals("comp_r0", inner.parent)
        assertEquals(listOf(1020.0, 1050.0, 280.0), listOf(comp.x, comp.y, comp.w))
        assertEquals(1570.0, comp.y + comp.h)
        assertEquals(listOf(1000.0, 1000.0, 400.0), listOf(outer.x, outer.y, outer.w))
        assertEquals(1580.0, outer.y + outer.h)

        assertTrue(s.align(top.id, horizontally = false))
        assertEquals(600.0, inner.x)
        assertEquals(590.0 to 1300.0, comp.x to comp.x + comp.w)
        assertEquals(580.0 to 1400.0, outer.x to outer.x + outer.w)
    }

    @Test
    fun aligningUpwardGrowsTheStateAboveItsHeader() {
        val s = newSession()
        val comp = addState(s, "comp", s.rootRegion, 1000.0, 1000.0, 280.0, 190.0, regions = 1)
        addState(s, "inner", "comp_r0", 1040.0, 1100.0)
        val top = addState(s, "top", s.rootRegion, 600.0, 900.0)
        s.selection = linkedSetOf("inner", top.id)
        assertTrue(s.align(top.id, horizontally = true))
        val header = Geometry.headerHeight(s.model, comp)
        assertEquals(900 - DiagramSession.FIT_MARGIN - header, comp.y, 1e-6)
        assertEquals(1190.0, comp.y + comp.h, 1e-6)
        assertEquals(900.0, Geometry.regionRects(s.model, comp)[0].rect.y + DiagramSession.FIT_MARGIN, 1e-6)
    }

    @Test
    fun aligningGrowsEveryRegionOfAnOrthogonalState() {
        val s = newSession()
        // Stacked regions of 100 below a header: the second one holds b.
        val orth = addState(s, "orth", s.rootRegion, 1000.0, 1000.0, 300.0, 0.0, regions = 2)
        val header = Geometry.headerHeight(s.model, orth)
        orth.h = header + 200
        val a = addState(s, "a", "orth_r0", 1020.0, 1000 + header + 20)
        val b = addState(s, "b", "orth_r1", 1020.0, 1000 + header + 120)
        val c = addState(s, "c", s.rootRegion, 1600.0, 1000 + header + 300)
        s.selection = linkedSetOf(b.id, c.id)
        assertTrue(s.align(c.id, horizontally = true))
        val rects = Geometry.regionRects(s.model, orth)
        assertTrue(rects[0].rect.bottom >= a.y + a.h + DiagramSession.FIT_MARGIN - 1e-6)
        assertTrue(rects[1].rect.y <= b.y - DiagramSession.FIT_MARGIN + 1e-6)
        assertTrue(rects[1].rect.bottom >= b.y + b.h + DiagramSession.FIT_MARGIN - 1e-6)
        assertEquals(1000.0, orth.y)
        assertEquals(rects[0].rect.h, rects[1].rect.h, 1e-6)
    }

    @Test
    fun statesOfStackedRegionsCannotShareARow() {
        val s = newSession()
        val orth = addState(s, "orth", s.rootRegion, 1000.0, 1000.0, 340.0, 250.0, regions = 2)
        val a = addState(s, "a", "orth_r0", 1020.0, 1050.0)
        val b = addState(s, "b", "orth_r1", 1180.0, 1160.0)
        var message: String? = null
        s.onMessage { message = it }
        s.selection = linkedSetOf(a.id, b.id)
        assertFalse(s.align(a.id, horizontally = true))
        assertEquals("The states cannot be aligned: they would leave their regions of 'orth'.", message)
        assertEquals(1180.0 to 1160.0, b.x to b.y)
        assertEquals(listOf(1000.0, 1000.0, 340.0, 250.0), listOf(orth.x, orth.y, orth.w, orth.h))
        // Side by side, the same states can share a row but not a column.
        orth.regionLayout = RegionLayout.Horizontal
        b.x = 1200.0
        assertTrue(s.align(a.id, horizontally = true))
        assertEquals(a.y, b.y)
        assertFalse(s.align(a.id, horizontally = false))
    }

    @Test
    fun selfTransitionLoopsAreReshapedByTheirCorners() {
        val s = newSession()
        val a = addState(s, "a", s.rootRegion, 100.0, 100.0)
        val t = Transition("t1", a.id, a.id)
        s.model.transitions.add(t)
        s.reindex()
        fun route() = Geometry.route(s.index, t)!!.joinToString(" ") { "${Num.format(Math.round(it.x * 10) / 10.0)},${Num.format(Math.round(it.y * 10) / 10.0)}" }
        assertEquals("200.8,100 200.8,70 270,70 270,118 240,118", route())
        // The far corner moves the loop to the state's corner in its quadrant, mirrored.
        s.moveLoopCorner(t, 1, PointD(300.0, 250.0))
        assertEquals("240,142 300,142 300,250 200.8,250 200.8,160", route())
        assertEquals(3, t.points.size)
        s.moveLoopCorner(t, 1, PointD(20.0, 250.0))
        assertEquals("139.2,160 139.2,250 20,250 20,142 100,142", route())
        s.moveLoopCorner(t, 1, PointD(20.0, 20.0))
        assertEquals("100,118 20,118 20,20 139.2,20 139.2,100", route())
        // The other corners move their leg and slide its end, which stays away from the state's corner.
        s.moveLoopCorner(t, 0, PointD(60.0, 140.0))
        assertEquals("100,140 60,140 60,20 139.2,20 139.2,100", route())
        s.moveLoopCorner(t, 2, PointD(300.0, 30.0))
        assertEquals("100,140 60,140 60,30 240,30 240,100", route())
        // Inside the state, the far corner is pushed out.
        s.moveLoopCorner(t, 1, PointD(180.0, 120.0))
        assertEquals("100,100 100,90 250,90 250,140 240,140", route())
        s.addWaypoint(t, PointD(250.0, 100.0))
        assertEquals(3, t.points.size)
        s.removeWaypoint(t, 0)
        assertTrue(t.points.isEmpty())
        // A single bend point is the far corner of a loop with the default ends.
        t.points.add(PointD(20.0, 20.0))
        assertEquals("100,118 20,118 20,20 139.2,20 139.2,100", route())
    }
}
