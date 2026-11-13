package com.vincegosoftware.fsmeditor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Elements stay inside their composite state's region, which grows for them; regions have their own sizes. */
class LayoutTest {
    private fun state(id: String, parent: String, x: Double, y: Double, w: Double = 100.0, h: Double = 50.0, vararg regions: String): Vertex {
        val v = Vertex(id, VertexType.State, id, parent, x, y, w, h)
        for (r in regions) v.regions.add(Region(r))
        return v
    }

    /** Q holds P, whose stacked regions r1 (A) and r2 (C, D, a transition with a bend point) share it equally; headers are 24 high. */
    private fun nested(): DiagramSession {
        val m = FsmModel()
        m.name = "T"
        m.regions.add(Region("r0"))
        m.vertices.add(state("Q", "r0", 50.0, 50.0, 600.0, 500.0, "rq"))
        m.vertices.add(state("P", "rq", 100.0, 100.0, 300.0, 260.0, "r1", "r2"))
        m.vertices.add(state("A", "r1", 130.0, 150.0))
        m.vertices.add(state("C", "r2", 130.0, 270.0))
        m.vertices.add(state("D", "r2", 260.0, 270.0))
        m.vertices.add(state("B", "r0", 700.0, 150.0))
        val t = Transition("t1", "C", "D")
        t.points = mutableListOf(PointD(245.0, 330.0))
        m.transitions.add(t)
        return DiagramSession(m)
    }

    private fun DiagramSession.v(id: String) = index.vertex(id)!!

    private fun move(s: DiagramSession, snap: DiagramSession.LayoutSnapshot, v: Vertex, dx: Double, dy: Double) {
        s.restore(snap)
        val items = (listOf(v) + s.index.descendants(v)).map { DiagramSession.MovedItem(it, it.x, it.y) }
        val boxes = mapOf(v.id to RectD(v.x, v.y, v.w, v.h))
        s.moveFrom(snap, items, emptyList(), listOf(v), boxes, dx, dy, v.center, false)
    }

    @Test
    fun movingPastARegionGrowsItAndMovingBackShrinksIt() {
        val s = nested()
        val snap = s.snapshot()
        move(s, snap, s.v("A"), 0.0, 60.0)
        assertEquals(148.0, s.v("P").regions[0].size)
        assertEquals(290.0, s.v("P").h)
        assertEquals(300.0, s.v("C").y)
        assertEquals(300.0, s.v("D").y)
        assertEquals(360.0, s.index.transition("t1")!!.points[0].y)
        move(s, snap, s.v("A"), 0.0, 20.0)
        assertEquals(260.0, s.v("P").h)
        assertEquals(270.0, s.v("C").y)
    }

    @Test
    fun growingCascadesToTheEnclosingStates() {
        val s = nested()
        move(s, s.snapshot(), s.v("A"), 0.0, 300.0)
        assertEquals(530.0, s.v("P").h)
        assertEquals(590.0, s.v("Q").h)
    }

    @Test
    fun movingUpAndLeftGrowsThoseSides() {
        val s = nested()
        move(s, s.snapshot(), s.v("A"), -60.0, -60.0)
        val p = s.v("P")
        val a = s.v("A")
        assertTrue(p.x <= a.x - 10 && p.y + 24 <= a.y - 10)
        assertEquals(400.0 to 360.0, (p.x + p.w) to (p.y + p.h))
    }

    @Test
    fun droppedElementsNestOnlyWhenWhollyInsideARegion() {
        var s = nested()
        var b = s.v("B")
        b.x = 280.0
        b.y = 160.0
        s.dropMoved(listOf(b), setOf("B"))
        assertEquals("r1", b.parent)

        s = nested()
        b = s.v("B")
        b.x = 600.0
        b.y = 160.0
        s.dropMoved(listOf(b), setOf("B"))
        assertEquals("r0", b.parent)
        assertTrue(s.overlapIssues().any { it.id == "B" && it.message == "Overlaps 'Q'." })
        assertTrue(s.overlapIssues().any { it.id == "Q" && it.message == "Overlaps 'B'." })
        assertTrue(s.overlapIssues().none { it.id == "A" })
    }

    @Test
    fun leavingTheParentKeepsThePlace() {
        val s = nested()
        s.selection = linkedSetOf("A")
        assertTrue(s.canLeaveParent)
        s.leaveParent()
        val a = s.v("A")
        assertEquals("rq", a.parent)
        assertEquals(130.0 to 150.0, a.x to a.y)
        assertTrue(s.overlapIssues().any { it.id == "A" && it.message == "Overlaps 'P'." })
        // Dropped back entirely inside its old state, it nests again.
        s.dropMoved(listOf(a), setOf("A"))
        assertEquals("r1", a.parent)
        s.selection = linkedSetOf("B")
        assertFalse(s.canLeaveParent)
    }

    @Test
    fun theLineBetweenRegionsResizesTheRegionBeforeIt() {
        val s = nested()
        val p = s.v("P")
        val snap = s.beginLayoutDrag(p)
        s.resizeRegionFrom(snap, p, 0, 50.0, false)
        assertEquals(listOf(168.0, 310.0, 320.0), listOf(p.regions[0].size!!, p.h, s.v("C").y))
        s.resizeRegionFrom(snap, p, 0, -92.0, false)
        assertEquals(listOf(86.0, 228.0, 238.0), listOf(p.regions[0].size!!, p.h, s.v("C").y))
    }

    @Test
    fun resizingStopsAtTheContentsAndTheTopEdgeResizesTheFirstRegion() {
        val s = nested()
        val p = s.v("P")
        val snap = s.beginLayoutDrag(p)
        s.resizeFrom(snap, p, "se", 100.0, 100.0, 300.0, 260.0, -200.0, -200.0, false)
        assertEquals(270.0 to 230.0, p.w to p.h)
        s.resizeFrom(snap, p, "nw", 100.0, 100.0, 300.0, 260.0, 0.0, -20.0, false)
        assertEquals(listOf(80.0, 138.0, 270.0), listOf(p.y, p.regions[0].size!!, s.v("C").y))
    }

    @Test
    fun resizingANestedStatePastItsRegionGrowsIt() {
        val s = nested()
        val a = s.v("A")
        val snap = s.beginLayoutDrag(a)
        s.resizeFrom(snap, a, "se", 130.0, 150.0, 100.0, 50.0, 200.0, 0.0, false)
        assertEquals(440.0, s.v("P").x + s.v("P").w)
    }

    @Test
    fun aTallerHeaderMovesTheRegionsDown() {
        val s = nested()
        s.v("P").entry = "doIt()"
        s.commit()
        assertEquals(listOf(280.0, 170.0, 290.0), listOf(s.v("P").h, s.v("A").y, s.v("C").y))
    }

    @Test
    fun switchingTheLayoutKeepsTheContentsInTheirRegions() {
        val s = nested()
        val p = s.v("P")
        s.setRegionLayout(p, RegionLayout.Horizontal)
        assertEquals(150.0 to 270.0, p.regions[0].size to p.regions[1].size)
        assertEquals(420.0 to 260.0, p.w to p.h)
        assertEquals(130.0 to 150.0, s.v("A").x to s.v("A").y)
        assertEquals(280.0 to 152.0, s.v("C").x to s.v("C").y)
    }

    @Test
    fun pastingAtAPointGoesIntoTheRegionThere() {
        val s = nested()
        s.selection = linkedSetOf("A")
        val data = ClipboardJson.tryRead(ClipboardJson.write(s.collectSelection()!!))
        s.paste(data, PointD(350.0, 300.0))
        val copy = s.model.vertices.single { it.name == "A" && it.id != "A" }
        assertEquals(Triple("r2", 350.0, 300.0), Triple(copy.parent, copy.x, copy.y))
        assertEquals(360.0 to 260.0, s.v("P").w to s.v("P").h)
    }

    @Test
    fun addedRegionsComeAfterAndRemovedOnesShrinkTheState() {
        val s = nested()
        val p = s.v("P")
        s.addRegion(p)
        assertEquals(3, p.regions.size)
        assertEquals(360.0 to 100.0, p.h to p.regions[2].size)
        s.removeRegion(p, "r1")
        assertEquals(242.0 to 152.0, p.h to s.v("C").y)
        assertNull(s.index.vertex("A"))
    }

    @Test
    fun regionSizesGoThroughTheClipboard() {
        val s = nested()
        s.v("P").regions[0].size = 100.0
        s.selection = linkedSetOf("P")
        val json = ClipboardJson.write(s.collectSelection()!!)
        assertTrue("{\"id\":\"r1\",\"name\":\"\",\"size\":100}" in json)
        assertEquals(100.0, ClipboardJson.tryRead(json)!!.vertices.single { it.id == "P" }.regions[0].size)
    }

    @Test
    fun deeplyNestedClipboardTextIsNotElements() {
        val deep = "[".repeat(100000) + "]".repeat(100000)
        assertNull(ClipboardJson.tryRead("{\"fsmClipboard\":1,\"vertices\":$deep}"))
        // Crafted: the only vertex's parent is the id of a copied transition, so no copied vertex is a root.
        val s = nested()
        val count = s.model.vertices.size
        val data = ClipboardJson.tryRead(
            "{\"fsmClipboard\":1,\"vertices\":[{\"id\":\"a\",\"type\":\"state\",\"parent\":\"t\"}]," +
                "\"transitions\":[{\"id\":\"t\",\"source\":\"a\",\"target\":\"a\"}]}",
        )
        s.paste(data, PointD(10.0, 10.0))
        assertEquals(count, s.model.vertices.size)
    }

    @Test
    fun aStateInItsOwnRegionNeitherGrowsForeverNorOverflowsTheWriter() {
        // A file edited by hand: state S reuses the id of the top-level region for its own region.
        val m = FsmModel()
        m.id = "sm"
        m.name = "T"
        m.regions.add(Region("r0"))
        m.vertices.add(state("S", "r0", 50.0, 50.0, 200.0, 100.0, "r0"))
        m.vertices.add(state("A", "r0", 60.0, 90.0))
        val s = DiagramSession(m)
        assertNotNull(s.createVertex("state", PointD(500.0, 500.0)))
        assertFailsWith<XmiException> { Xmi.toXmi(s.model) }
    }

    @Test
    fun wrappingDropsOneCarriageReturnPerLineAsTheOtherEditions() {
        // replace(/\r$/, '') in the other editions: a second \r stays, and counts in the width.
        assertEquals(listOf("a b", "c"), Geometry.commentLines("a b\r\nc", 100.0))
        assertEquals(listOf("x()\r", "y()"), Geometry.commentLines("x()\r\r\ny()", 100.0))
        assertEquals(listOf("entry /", "x()\r", "y()"), Geometry.commentLines("entry / x()\r\r\ny()", 74.0))
    }
}
