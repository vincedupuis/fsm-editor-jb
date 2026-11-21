package com.vincegosoftware.fsmeditor.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Editing parity with the VS Code edition, the reference: `src/test/editing` is a copy of its
 * `test/unit/fixtures/editing`, each case a machine of `src/test/geometry`, steps (editing operations in the diagram's
 * coordinates, run there with the mouse and the keyboard) and the two files its last edit wrote. Here the steps run on
 * [DiagramSession] as `DiagramCanvas` and `PropertiesPanel` call it (the drags as the canvas does them), and must write
 * the same files. After a change there, copy the folder here.
 */
class EditingCasesTest {
    init {
        // Sets the sidecar's path.
        TestFiles.CLI
    }

    private val dir = File(System.getProperty("fsm.editing") ?: "src/test/editing")
    private val geometry = File(System.getProperty("fsm.geometry") ?: "src/test/geometry")

    @Test
    fun editsAsTheVsCodeEdition() {
        val cases = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()
        assertTrue(cases.size >= 15, "${cases.size} cases in $dir")
        val failed = cases.filter { name ->
            val c = PlainJson.parse(File(dir, "$name.json").readText()) as Map<*, *>
            val machine = File(geometry, c["machine"] as String).path
            val s = DiagramSession(TestFiles.read(File("$machine.fsm").readText(), File("$machine.fsm.layout").readText()).model!!)
            val selected = mutableListOf<String>()
            for (step in c["steps"] as List<*>) {
                step as Map<*, *>
                run(s, step, selected)
                if (step["op"] == "select") continue
                // As the VS Code edition shows each edit: written, and read back (ids derived from names).
                val w = TestFiles.write(s.model)
                s.replaceModel(TestFiles.read(w.model, w.layout).model!!)
            }
            val written = TestFiles.write(s.model)
            written.model != c["fsm"] || plainGroups(written.layout) != c["layout"]
        }
        assertEquals(emptyList(), failed, "edited differently from the VS Code edition")
    }

    /** A layout file with its group ids, which a new group takes at random, renamed group1, group2… in order of appearance. */
    private fun plainGroups(layout: String): String {
        val names = LinkedHashMap<String, String>()
        return Regex("\"group\": \"([^\"]*)\"").replace(layout) { m ->
            "\"group\": \"" + names.getOrPut(m.groupValues[1]) { "group${names.size + 1}" } + "\""
        }
    }

    /** Where the VS Code edition grabs a vertex: its center, or the middle of a composite state's title bar. */
    private fun grab(v: Vertex) = PointD(v.x + v.w / 2, if (v.regions.isNotEmpty()) v.y + 10 else v.y + v.h / 2)

    private fun run(s: DiagramSession, step: Map<*, *>, selected: MutableList<String>) {
        fun str(k: String) = step[k] as String
        fun num(k: String) = step[k] as Double
        fun v(id: String) = s.index.vertex(id) ?: error("no vertex $id")
        fun t(id: String) = s.index.transition(id) ?: error("no transition $id")
        when (str("op")) {
            "select" -> {
                selected.clear()
                selected.addAll((step["ids"] as List<*>).map { it as String })
                s.selection = LinkedHashSet(selected)
            }
            "create" -> s.createVertex(str("tool"), PointD(num("x"), num("y")))
            "move" -> move(s, grab(v(selected[0])), num("dx"), num("dy"))
            "resize" -> {
                val x = v(selected[0])
                val snap = s.beginLayoutDrag(x)
                s.resizeFrom(snap, x, str("corner"), x.x, x.y, x.w, x.h, num("dx"), num("dy"), false)
                s.commit()
            }
            "divider" -> {
                val x = v(str("state"))
                val snap = s.beginLayoutDrag(x)
                s.resizeRegionFrom(snap, x, num("index").toInt(), num("delta"), false)
                s.commit()
            }
            "connect" -> s.connect(v(str("source")), str("target"))
            "delete" -> s.deleteSelection()
            "waypoint" -> s.addWaypoint(t(str("transition")), PointD(num("x"), num("y")))
            "unwaypoint" -> s.removeWaypoint(t(str("transition")), num("index").toInt())
            "label" -> assertNull(s.applyLabel(t(str("transition")), str("text")))
            "kind" -> s.setKind(t(str("transition")), when (str("kind")) {
                "local" -> TransitionKind.Local
                "internal" -> TransitionKind.Internal
                else -> TransitionKind.External
            })
            "straighten" -> s.straighten(t(str("transition")))
            "reverse" -> s.reverse(t(str("transition")))
            "addRegion" -> s.addRegion(v(str("state")))
            "internal" -> s.addInternalTransition(v(str("state")))
            "removeRegion" -> v(str("state")).let { s.removeRegion(it, it.regions[num("index").toInt()].id) }
            "layout" -> s.setRegionLayout(v(str("state")), if (str("layout") == "horizontal") RegionLayout.Horizontal else RegionLayout.Vertical)
            "type" -> s.changeType(v(str("id")), VertexType.parseKey(str("type")) ?: error("no type ${str("type")}"))
            "group" -> s.toggleGroup()
            "duplicate" -> {
                s.resetPasteOffset()
                s.paste(s.collectSelection())
            }
            "leave" -> s.leaveParent()
            "align" -> s.align(str("anchor"), step["horizontally"] as Boolean)
            "color" -> {
                val x = s.index.vertex(str("id"))
                if (x != null) s.setColor(x, null, str("color")) else s.setColor(t(str("id")), null, str("color"))
            }
            else -> error("unknown step ${str("op")}")
        }
    }

    /** A drag of the selection from [start], as `DiagramCanvas` does it (startMove, a move, the drop). */
    private fun move(s: DiagramSession, start: PointD, dx0: Double, dy0: Double) {
        val ix = s.index
        val moving = LinkedHashMap<String, Vertex>()
        val roots = ArrayList<Vertex>()
        val picked = s.withGroupMates(s.selection.mapNotNull { ix.vertex(it) })
        val pickedIds = picked.map { it.id }.toSet()
        for (v in picked) {
            if (ix.ancestors(v).any { it.id in pickedIds }) continue
            roots.add(v)
            moving[v.id] = v
            for (d in ix.descendants(v)) moving[d.id] = d
        }
        val items = moving.values.map { DiagramSession.MovedItem(it, it.x, it.y) }
        val transitions = s.model.transitions
            .filter { it.points.isNotEmpty() && it.source in moving && it.target in moving }
            .map { DiagramSession.MovedPoints(it, it.points.toList()) }
        val snap = s.snapshot()
        val boxes = roots.associate { it.id to RectD(it.x, it.y, it.w, it.h) }
        var dx = dx0
        var dy = dy0
        val lead = roots.firstOrNull()
        if (lead != null && !ix.isBorderVertex(lead)) {
            val it = items.first { it.v === lead }
            dx = Geometry.snap(it.x0 + dx) - it.x0
            dy = Geometry.snap(it.y0 + dy) - it.y0
        }
        s.moveFrom(snap, items, transitions, roots, boxes, dx, dy, PointD(start.x + dx, start.y + dy), false)
        s.dropMoved(roots, moving.keys.toSet())
    }
}
