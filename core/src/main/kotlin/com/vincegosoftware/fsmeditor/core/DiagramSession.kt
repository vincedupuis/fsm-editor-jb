package com.vincegosoftware.fsmeditor.core

import kotlin.math.max
import kotlin.random.Random

/** Vertices and transitions copied to the clipboard. */
class ClipboardFragment(
    val vertices: MutableList<Vertex> = mutableListOf(),
    val transitions: MutableList<Transition> = mutableListOf(),
)

/**
 * The model being edited in a diagram, its selection and what is known about
 * the other machines of the project, with every editing operation of the
 * diagram editor. Operations that change the model call [commit], which asks
 * the host to write the document; problems they refuse are reported through
 * the message listeners.
 */
class DiagramSession(model: FsmModel) {
    private val random = Random.Default
    private var pasteCount = 0
    private val committedListeners = ArrayList<() -> Unit>()
    private val messageListeners = ArrayList<(String) -> Unit>()

    var model: FsmModel = model
        private set
    lateinit var index: ModelIndex
        private set
    var selection: MutableSet<String> = LinkedHashSet()
    /** Machines referenced by submachine states, keyed by href. */
    var submachines: Map<String, SubmachineInfo> = emptyMap()
    /** State machines in the project that can be used as submachines. */
    var machines: List<MachineInfo> = emptyList()

    init {
        reindex()
    }

    /** Called after a change that must be written to the document. */
    fun onCommitted(listener: () -> Unit) {
        committedListeners.add(listener)
    }

    /** Called with a short explanation when an operation is refused or needs attention. */
    fun onMessage(listener: (String) -> Unit) {
        messageListeners.add(listener)
    }

    fun replaceModel(model: FsmModel) {
        this.model = model
        reindex()
        selection = selection.filterTo(LinkedHashSet()) { index.vertices.containsKey(it) || index.transitions.containsKey(it) }
    }

    fun reindex() {
        index = ModelIndex(model)
    }

    fun commit() {
        reindex()
        committedListeners.toList().forEach { it() }
    }

    fun say(message: String) {
        messageListeners.toList().forEach { it(message) }
    }

    val rootRegion: String get() = model.regions[0].id

    // ---------------------------------------------------------------- names and ids

    fun newId(prefix: String): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var id: String
        do {
            id = prefix + "_" + String(CharArray(6) { chars[random.nextInt(chars.length)] })
        } while (index.vertices.containsKey(id) || index.transitions.containsKey(id) || index.regionOwner.containsKey(id))
        return id
    }

    fun uniqueName(baseName: String): String {
        val names = model.vertices.map { it.name }.toSet()
        if (baseName !in names) return baseName
        var i = 2
        while (true) {
            if (baseName + i !in names) return baseName + i
            i++
        }
    }

    /** Display name of the machine a submachine href points to. */
    fun machineLabel(href: String?): String {
        val info = submachines[href ?: ""]
        if (info != null && info.name.isNotEmpty()) return info.name
        val known = machines.firstOrNull { it.href == href }
        if (known != null && known.name.isNotEmpty()) return known.name
        var file = Hrefs.decodeUri((href ?: "").split('#')[0])
        file = file.substring(file.lastIndexOf('/') + 1)
        return if (file.endsWith(".fsm")) file.substring(0, file.length - 4) else file
    }

    /** Entry/exit points of a submachine state's referenced machine, as far as they could be resolved. */
    fun submachinePoints(state: Vertex?): List<ConnectionPointInfo> {
        if (state == null || state.submachine.isEmpty()) return emptyList()
        val info = submachines[state.submachine]
        return if (info != null && info.found) info.points else emptyList()
    }

    /** Name of the point [id] in the machine referenced by [state]. */
    fun pointName(state: Vertex?, id: String): String = submachinePoints(state).firstOrNull { it.id == id }?.name ?: ""

    fun refsOf(state: Vertex): List<Vertex> =
        model.vertices.filter { it.type == VertexType.ConnectionPointRef && it.parent == state.id }

    /** Points of the submachine not yet referenced on [state]. */
    fun freePoints(state: Vertex): List<ConnectionPointInfo> {
        val used = refsOf(state).map { it.ref }.toSet()
        return submachinePoints(state).filter { it.id !in used }
    }

    // ---------------------------------------------------------------- creating

    /** Places a new element of tool [toolId] at [p]. Returns it, or null when refused. */
    fun createVertex(toolId: String, p: PointD): Vertex? {
        val create = Tools.byId(toolId)?.create ?: return null
        reindex()
        val v = create(this)
        v.id = newId("v")
        if (v.type.isPointType) {
            val s = Geometry.stateAt(index, p, emptySet())
            if (s != null && s.submachine.isNotEmpty() && v.type != VertexType.ConnectionPointRef) {
                // On a submachine state, UML uses a reference to the submachine's own entry/exit point.
                val kind = if (v.type == VertexType.ExitPoint) PointKind.Exit else PointKind.Entry
                v.type = VertexType.ConnectionPointRef
                v.pointKind = kind
                v.ref = ""
                say("Added a connection point reference: pick the ${if (kind == PointKind.Exit) "exit" else "entry"} point of '${machineLabel(s.submachine)}' it refers to.")
            }
            if (v.type == VertexType.ConnectionPointRef) {
                if (s == null || s.submachine.isEmpty()) {
                    say("Connection point references go on the border of a submachine state.")
                    return null
                }
                val free = freePoints(s)
                val pick = free.firstOrNull { v.ref.isEmpty() && it.kind == v.pointKind } ?: free.firstOrNull()
                if (pick != null) {
                    v.ref = pick.id
                    v.pointKind = pick.kind
                }
            }
            if (s != null) {
                v.parent = s.id
                Geometry.snapToBorder(v, s, p)
            } else {
                // Not on a state: a connection point of the state machine itself.
                v.parent = rootRegion
                v.name = uniqueName(if (v.type == VertexType.EntryPoint) "in" else "out")
                v.x = Geometry.snap(p.x) - v.w / 2
                v.y = Geometry.snap(p.y) - v.h / 2
            }
        } else {
            v.x = Geometry.snap(p.x - v.w / 2)
            v.y = Geometry.snap(p.y - v.h / 2)
            val r = Geometry.regionAt(index, p, emptySet())
            v.parent = r?.id ?: rootRegion
        }
        model.vertices.add(v)
        selection = linkedSetOf(v.id)
        commit()
        return v
    }

    /** Adds a reference for every unreferenced point (or only [only]): entries on the left border, exits on the right. */
    fun addAllReferences(state: Vertex, only: ConnectionPointInfo? = null) {
        val free = if (only != null) listOf(only) else freePoints(state)
        if (free.isEmpty()) return
        for (kind in listOf(PointKind.Entry, PointKind.Exit)) {
            val list = free.filter { it.kind == kind }
            val existing = refsOf(state).count { it.pointKind == kind }
            val total = list.size + existing
            for ((i, point) in list.withIndex()) {
                val v = Vertex(newId("v"), VertexType.ConnectionPointRef, "", state.id, 0.0, 0.0, 16.0, 16.0)
                v.ref = point.id
                v.pointKind = kind
                val y = state.y + state.h * (existing + i + 1) / (total + 1)
                Geometry.snapToBorder(v, state, PointD(if (kind == PointKind.Entry) state.x else state.x + state.w, y))
                model.vertices.add(v)
                reindex()
            }
        }
        // Leave room for the captions under the points.
        state.h = max(state.h, 40.0 + 24 * max(free.count { it.kind == PointKind.Entry }, free.count { it.kind == PointKind.Exit }))
        commit()
    }

    fun addRegion(state: Vertex) {
        if (state.regions.isEmpty()) {
            // Make room for the region below the name compartment.
            state.w = max(state.w, 220.0)
            state.h = max(state.h, Geometry.headerHeight(model, state) + 120)
        } else if (state.regionLayout == RegionLayout.Horizontal) {
            state.w += 160
        } else {
            state.h += 100
        }
        state.regions.add(Region(newId("r")))
        selection = linkedSetOf(state.id)
        commit()
    }

    fun removeRegion(state: Vertex, regionId: String) {
        val doomed = HashSet<String>()
        for (v in model.vertices) {
            if (v.parent != regionId) continue
            doomed.add(v.id)
            for (d in index.descendants(v)) doomed.add(d.id)
        }
        state.regions = state.regions.filterTo(mutableListOf()) { it.id != regionId }
        removeVertices(doomed, emptySet())
        commit()
    }

    fun addInternalTransition(state: Vertex) {
        val t = Transition(newId("t"), state.id, state.id, TransitionKind.Internal)
        t.triggers = mutableListOf("event")
        t.effect = "action()"
        model.transitions.add(t)
        commit()
    }

    // ---------------------------------------------------------------- deleting

    fun removeVertices(ids: Set<String>, transitionIds: Set<String>) {
        model.vertices = model.vertices.filterTo(mutableListOf()) { it.id !in ids }
        model.transitions = model.transitions
            .filterTo(mutableListOf()) { it.id !in transitionIds && it.source !in ids && it.target !in ids }
        val alive = (model.vertices.map { it.id } + model.transitions.map { it.id }).toSet()
        for (v in model.vertices) v.anchors = v.anchors.filterTo(mutableListOf()) { it in alive }
        selection = selection.filterTo(LinkedHashSet()) { it in alive }
        reindex()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val vs = HashSet<String>()
        val ts = HashSet<String>()
        for (id in selection) {
            val v = index.vertex(id)
            if (v != null) {
                vs.add(id)
                for (d in index.descendants(v)) vs.add(d.id)
            } else if (index.transition(id) != null) {
                ts.add(id)
            }
        }
        removeVertices(vs, ts)
        selection.clear()
        commit()
    }

    fun removeTransition(t: Transition) {
        model.transitions.remove(t)
        selection.remove(t.id)
        commit()
    }

    // ---------------------------------------------------------------- connecting

    /**
     * Draws a transition from [source] to [targetId], or attaches a comment.
     * Returns the new transition, or null when none was created.
     */
    fun connect(source: Vertex, targetId: String): Transition? {
        if (source.type == VertexType.Comment) {
            if (targetId == source.id) return null
            if (targetId !in source.anchors) source.anchors.add(targetId)
            selection = linkedSetOf(source.id)
            commit()
            return null
        }
        val target = index.vertex(targetId) ?: return null
        if (target.type == VertexType.Comment) {
            if (source.id !in target.anchors) target.anchors.add(source.id)
            commit()
            return null
        }
        if (source.type == VertexType.ConnectionPointRef && source.pointKind == PointKind.Entry) {
            say("An entry reference only receives transitions; execution continues inside the submachine.")
            return null
        }
        if (target.type == VertexType.ConnectionPointRef && target.pointKind == PointKind.Exit) {
            say("An exit reference only has outgoing transitions; it is reached when the submachine exits.")
            return null
        }
        if (source.type == VertexType.Final || source.type == VertexType.Terminate) {
            say("A ${source.type.title.lowercase()} cannot have outgoing transitions.")
            return null
        }
        if (target.type == VertexType.Initial) {
            say("An initial pseudostate cannot be the target of a transition.")
            return null
        }
        val t = Transition(newId("t"), source.id, target.id)
        model.transitions.add(t)
        selection = linkedSetOf(t.id)
        commit()
        return t
    }

    // ---------------------------------------------------------------- transitions

    /** Adds a bend point at [p], on the segment nearest to it. */
    fun addWaypoint(t: Transition, p: PointD) {
        if (Geometry.isLoop(index, t)) return // a self-transition loop has no bend points but its corners
        val pts = Geometry.route(index, t) ?: return
        if (t.points.isEmpty()) t.points = pts.subList(1, pts.size - 1).mapTo(mutableListOf()) { PointD(Num.round(it.x), Num.round(it.y)) }
        val full = Geometry.route(index, t)!!
        var best = 0
        var bestD = Double.POSITIVE_INFINITY
        for (i in 0 until full.size - 1) {
            val d = Geometry.distToSegment(p, full[i], full[i + 1])
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        t.points.add(best, PointD(Geometry.snap(p.x), Geometry.snap(p.y)))
        selection = linkedSetOf(t.id)
        commit()
    }

    /**
     * Drags corner [corner] (in drawing order) of a state's self-transition loop to [p]. The far
     * corner resizes the loop and moves it to the state's corner in its quadrant; each other corner
     * moves its leg and slides the leg's end along the state's edge. The loop then keeps its three
     * corners as bend points.
     */
    fun moveLoopCorner(t: Transition, corner: Int, p: PointD) {
        val s = index.vertex(t.source) ?: return
        if (!Geometry.isLoop(index, t)) return
        val f = Geometry.loop(t, s)
        val moved = when {
            corner == 1 -> {
                val right = p.x >= s.x + s.w / 2
                val below = p.y > s.y + s.h / 2
                // Moved to another corner of the state, the loop is mirrored.
                f.copy(
                    right = right,
                    below = below,
                    cx = p.x,
                    cy = p.y,
                    hx = if (right != f.right) 2 * s.x + s.w - f.hx else f.hx,
                    vy = if (below != f.below) 2 * s.y + s.h - f.vy else f.vy,
                )
            }
            (corner == 0) == f.startsOnTopOrBottom -> f.copy(hx = p.x, cy = p.y)
            else -> f.copy(cx = p.x, vy = p.y)
        }
        t.points = Geometry.clampLoop(moved, s).corners()
    }

    fun removeWaypoint(t: Transition, index: Int) {
        if (index < 0 || index >= t.points.size) return
        if (Geometry.isLoop(this.index, t)) t.points.clear() else t.points.removeAt(index) // a loop goes back to the default
        commit()
    }

    fun straighten(t: Transition) {
        t.points.clear()
        t.labelOffset = null
        commit()
    }

    fun reverse(t: Transition) {
        val s = index.vertex(t.source)
        val g = index.vertex(t.target)
        if (g == null || g.type == VertexType.Initial || (s != null && (s.type == VertexType.Final || s.type == VertexType.Terminate))) {
            say("This transition cannot be reversed.")
            return
        }
        val source = t.source
        t.source = t.target
        t.target = source
        t.points.reverse()
        commit()
    }

    fun setKind(t: Transition, kind: TransitionKind) {
        t.kind = kind
        if (kind == TransitionKind.Internal) {
            t.target = t.source
            t.points.clear()
        }
        commit()
    }

    /** Applies a label typed on the diagram. Returns an error message, or null when applied. */
    fun applyLabel(t: Transition, text: String): String? {
        val err = Labels.applyLabel(model, t, text, index.vertex(t.source))
        if (err == null) commit()
        return err
    }

    // ---------------------------------------------------------------- vertices

    /** Swaps a pseudostate for another kind of its group, keeping its center. */
    fun changeType(v: Vertex, type: VertexType) {
        val c = v.center
        v.type = type
        val (w, h) = type.defaultSize
        v.w = w
        v.h = h
        v.x = c.x - v.w / 2
        v.y = c.y - v.h / 2
        commit()
    }

    /** Sets the color of a vertex ([key] null) or of one of its items; null or empty clears it. */
    fun setColor(v: Vertex, key: String?, color: String?) = setColor({ v.color = it }, v.itemColors, key, color)

    /** Sets the color of a transition ([key] null) or of one of its label's items; null or empty clears it. */
    fun setColor(t: Transition, key: String?, color: String?) = setColor({ t.color = it }, t.itemColors, key, color)

    private fun setColor(setOwn: (String) -> Unit, items: MutableMap<String, String>, key: String?, color: String?) {
        val c = if (ElementColors.isColor(color)) color!! else ""
        when {
            key == null -> setOwn(c)
            c.isNotEmpty() -> items[key] = c
            else -> items.remove(key)
        }
        commit()
    }

    fun setSubmachine(v: Vertex, href: String): Boolean {
        if (href.isNotEmpty() && v.regions.isNotEmpty()) {
            say("Remove the regions first: a submachine state cannot own regions.")
            return false
        }
        v.submachine = href
        commit()
        return true
    }

    /** Moves the selected elements (and everything inside them) by a few pixels. */
    fun nudge(dx: Double, dy: Double): Boolean {
        val moved = HashSet<String>()
        for (id in selection) {
            val v = index.vertex(id) ?: continue
            if (index.ancestors(v).any { it.id in selection }) continue
            for (x in listOf(v) + index.descendants(v)) {
                if (!moved.add(x.id)) continue
                x.x += dx
                x.y += dy
            }
        }
        if (moved.isEmpty()) return false
        commit()
        return true
    }

    /**
     * Aligns the selected states (without a selected ancestor) with [anchorId]: on its horizontal center
     * line when [horizontally], else on its vertical one. Each state moves with its contents and stays in
     * its region; the enclosing composite states grow so that their regions still hold their states.
     * Returns false, changing nothing, when fewer than two states are selected or when states of different
     * regions of one state would overlap along its region layout.
     */
    fun align(anchorId: String, horizontally: Boolean): Boolean {
        val states = selectionRoots().filter { it.type == VertexType.State }
        val clicked = index.vertex(anchorId) ?: return false
        val anchor = index.ancestors(clicked).lastOrNull { it.id in selection } ?: clicked
        if (states.size < 2 || states.none { it === anchor }) return false

        val saved = model.vertices.map { Saved(it, it.x, it.y, it.w, it.h) }
        val savedPoints = model.transitions.map { it to it.points }
        val moved = ArrayList<Vertex>()
        for (v in states) {
            val dx = if (horizontally) 0.0 else anchor.centerX - v.centerX
            val dy = if (horizontally) anchor.centerY - v.centerY else 0.0
            if (dx == 0.0 && dy == 0.0) continue
            moved.add(v)
            val group = listOf(v) + index.descendants(v)
            for (x in group) {
                x.x += dx
                x.y += dy
            }
            val ids = group.mapTo(HashSet()) { it.id }
            for (t in model.transitions) {
                if (t.points.isNotEmpty() && t.source in ids && t.target in ids) {
                    t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + dx, it.y + dy) }
                }
            }
        }
        if (moved.isEmpty()) return false

        // Innermost first: a state that grows may no longer fit in its own region.
        for (s in moved.flatMap { index.ancestors(it) }.distinct().sortedByDescending { index.depth(it) }) {
            if (fitRegions(s)) continue
            for (r in saved) {
                r.v.x = r.x
                r.v.y = r.y
                r.v.w = r.w
                r.v.h = r.h
            }
            for ((t, points) in savedPoints) t.points = points
            say("The states cannot be aligned: they would leave their regions of '${s.name}'.")
            return false
        }
        commit()
        return true
    }

    companion object {
        /** Space kept between a region's border and its contents when [align] grows a state. */
        const val FIT_MARGIN = 10.0
    }

    private class Saved(val v: Vertex, val x: Double, val y: Double, val w: Double, val h: Double)

    /** Extent of the vertices of region [i] along one axis. */
    private class Extent(val i: Int, val lo: Double, val hi: Double)

    /** Grows [s] so that each of its regions holds its vertices; false when no size can. */
    private fun fitRegions(s: Vertex): Boolean {
        val n = s.regions.size
        val boxes = ArrayList<Pair<Int, RectD>>()
        for (i in 0 until n) {
            val id = s.regions[i].id
            val kids = model.vertices.filter { it.parent == id }
            if (kids.isEmpty()) continue
            val x0 = kids.minOf { it.x }
            val y0 = kids.minOf { it.y }
            boxes.add(i to RectD(x0, y0, kids.maxOf { it.x + it.w } - x0, kids.maxOf { it.y + it.h } - y0))
        }
        if (boxes.isEmpty()) return true
        if (s.regionLayout == RegionLayout.Horizontal) {
            val along = fitAlong(s.x, s.x + s.w, n, boxes.map { (i, r) -> Extent(i, r.x, r.right) }) ?: return false
            val (x, right) = stretch(s.x, s.x + s.w, along.first, along.first + n * along.second)
            s.x = x
            s.w = right - x
            val header = Geometry.headerHeight(model, s)
            val (top, bottom) = stretch(s.y + header, s.y + s.h, boxes.minOf { it.second.y } - FIT_MARGIN, boxes.maxOf { it.second.bottom } + FIT_MARGIN)
            s.y = top - header
            s.h = bottom - s.y
        } else {
            val (left, right) = stretch(s.x, s.x + s.w, boxes.minOf { it.second.x } - FIT_MARGIN, boxes.maxOf { it.second.right } + FIT_MARGIN)
            s.x = left
            s.w = right - left
            // After the width: the wrapped activities depend on it.
            val header = Geometry.headerHeight(model, s)
            val along = fitAlong(s.y + header, s.y + s.h, n, boxes.map { (i, r) -> Extent(i, r.y, r.bottom) }) ?: return false
            val (top, bottom) = stretch(s.y + header, s.y + s.h, along.first, along.first + n * along.second)
            s.y = top - header
            s.h = bottom - s.y
        }
        keepPointsOnBorder(s)
        return true
    }

    /** The extent [start0]..[end0] grown to cover [lo]..[hi], unchanged where it already does. */
    private fun stretch(start0: Double, end0: Double, lo: Double, hi: Double): Pair<Double, Double> =
        (if (lo < start0 - 1e-6) lo else start0) to (if (hi > end0 + 1e-6) hi else end0)

    /**
     * Start and size of [n] equal regions laid out from [start0] to [end0], grown as little as possible so
     * that region `i` spans `[lo, hi]` with the margin; null when the extents overlap so that no size fits them.
     */
    private fun fitAlong(start0: Double, end0: Double, n: Int, items: List<Extent>): Pair<Double, Double>? {
        // Region i spans [start + i * size, start + (i + 1) * size]. Upper bounds: start + j * size <= a;
        // lower bounds: start + (k + 1) * size >= b. The first and last ones keep the old extent.
        val uppers = listOf(0 to start0) + items.map { it.i to it.lo - FIT_MARGIN }
        val lowers = listOf(n - 1 to end0) + items.map { it.i to it.hi + FIT_MARGIN }
        // Each pair bounds the size from below when the lower bound's region doesn't come before the upper one's.
        var size = max(0.0, (end0 - start0) / n)
        for ((j, a) in uppers) {
            for ((k, b) in lowers) {
                if (k + 1 > j) size = max(size, (b - a) / (k + 1 - j))
            }
        }
        val start = uppers.minOf { (j, a) -> a - j * size }
        return if (lowers.all { (k, b) -> start + (k + 1) * size >= b - 1e-6 }) start to size else null
    }

    /** Selected elements without a selected ancestor, which lead a move. */
    fun selectionRoots(): List<Vertex> =
        selection.mapNotNull { index.vertex(it) }.filter { v -> index.ancestors(v).none { it.id in selection } }

    /** After a move: re-parents the moved elements into the region (or state border) they were dropped on. */
    fun dropMoved(roots: List<Vertex>, moving: Set<String>) {
        reindex()
        for (v in roots) {
            if (index.isBorderVertex(v)) continue
            if (v.type == VertexType.EntryPoint || v.type == VertexType.ExitPoint) {
                // A machine-level point dropped on a state becomes that state's point; elsewhere it stays top-level.
                val s = Geometry.stateAt(index, v.center, moving)
                if (s != null && s.submachine.isEmpty()) {
                    v.parent = s.id
                    Geometry.snapToBorder(v, s, v.center)
                } else {
                    v.parent = rootRegion
                }
                continue
            }
            val r = Geometry.regionAt(index, v.center, moving)
            v.parent = r?.id ?: rootRegion
        }
        commit()
    }

    /** Smallest size (width, height) a vertex can be resized to. */
    fun minimumSize(v: Vertex): Pair<Double, Double> {
        val bar = v.type == VertexType.Fork || v.type == VertexType.Join
        val minW = if (bar) 6.0 else 40.0
        val minH = when {
            bar -> 6.0
            v.type == VertexType.State -> Geometry.headerHeight(model, v) + if (v.regions.isNotEmpty()) 30 else 6
            else -> 30.0
        }
        return minW to minH
    }

    /** Keeps the connection points of [state] on its border after a resize. */
    fun keepPointsOnBorder(state: Vertex) {
        for (cp in model.vertices) {
            if (cp.parent == state.id && index.isBorderVertex(cp)) Geometry.snapToBorder(cp, state, cp.center)
        }
    }

    // ---------------------------------------------------------------- clipboard

    /** The selected elements with everything they contain and the transitions between them. */
    fun collectSelection(): ClipboardFragment? {
        val ids = HashSet<String>()
        for (id in selection) {
            val v = index.vertex(id) ?: continue
            ids.add(id)
            for (d in index.descendants(v)) ids.add(d.id)
        }
        if (ids.isEmpty()) return null
        return ClipboardFragment(
            model.vertices.filter { it.id in ids }.mapTo(mutableListOf()) { it.copy() },
            model.transitions.filter { it.source in ids && it.target in ids }.mapTo(mutableListOf()) { it.copy() },
        )
    }

    /** Copying starts a new series of paste offsets. */
    fun resetPasteOffset() {
        pasteCount = 0
    }

    fun paste(data: ClipboardFragment?) {
        if (data == null || data.vertices.isEmpty()) return
        reindex()
        pasteCount++
        val off = 20.0 * pasteCount
        val map = HashMap<String, String>()
        for (v in data.vertices) {
            map[v.id] = newId("v")
            for (r in v.regions) map[r.id] = newId("r")
        }
        val added = ArrayList<Vertex>()
        for (src in data.vertices) {
            val v = src.copy()
            v.id = map[src.id]!!
            v.x += off
            v.y += off
            v.regions = v.regions.mapTo(mutableListOf()) { Region(map[it.id]!!, it.name) }
            val parent = map[v.parent]
            if (parent != null) {
                v.parent = parent
            } else {
                // Pasted without its owner: keep it where it was when that still exists.
                val border = index.isBorderVertex(v)
                if (if (border) index.vertex(v.parent) == null else !index.isRegion(v.parent)) {
                    if (border) continue
                    v.parent = rootRegion
                }
            }
            v.anchors = v.anchors.filter { it in map }.mapTo(mutableListOf()) { map[it]!! }
            model.vertices.add(v)
            added.add(v)
        }
        for (src in data.transitions) {
            if (src.source !in map || src.target !in map) continue
            val t = src.copy()
            t.id = newId("t")
            t.source = map[src.source]!!
            t.target = map[src.target]!!
            t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + off, it.y + off) }
            model.transitions.add(t)
        }
        reindex()
        val addedIds = added.map { it.id }.toSet()
        selection = added.filter { v -> index.ancestors(v).none { it.id in addedIds } }.mapTo(LinkedHashSet()) { it.id }
        commit()
    }
}
