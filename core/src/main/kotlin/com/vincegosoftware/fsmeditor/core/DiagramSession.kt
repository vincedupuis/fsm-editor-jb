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
        resetHeaders()
        selection = selection.filterTo(LinkedHashSet()) { index.vertices.containsKey(it) || index.transitions.containsKey(it) }
    }

    fun reindex() {
        index = ModelIndex(model)
    }

    fun commit() {
        reindex()
        model.normalizeGroups()
        keepHeaders()
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
        reindex()
        fitInRegion(v, null, false)
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
        val old = boxOf(state)
        state.h = max(state.h, 40.0 + 24 * max(free.count { it.kind == PointKind.Entry }, free.count { it.kind == PointKind.Exit }))
        fitInRegion(state, old, false)
        commit()
    }

    fun addRegion(state: Vertex) {
        val old = boxOf(state)
        if (state.regions.isEmpty()) {
            // Make room for the region below the name compartment.
            state.w = max(state.w, 220.0)
            state.h = max(state.h, Geometry.headerHeight(model, state) + 120)
            state.regions.add(Region(newId("r")))
            layoutHeader[state.id] = headerOf(state)
        } else {
            // The new region comes after the others, which keep their sizes.
            keepRegionSizes(state)
            val size = if (state.regionLayout == RegionLayout.Horizontal) 160.0 else 100.0
            if (state.regionLayout == RegionLayout.Horizontal) state.w += size else state.h += size
            state.regions.add(Region(newId("r"), "", size))
        }
        reindex()
        followBorder(state, old)
        fitInRegion(state, old, false)
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
        val i = state.regions.indexOfFirst { it.id == regionId }
        if (i >= 0 && state.regions.size > 1) {
            // The state shrinks by the region's size, and the later regions move back with their contents.
            val old = boxOf(state)
            keepRegionSizes(state)
            val size = state.regions[i].size ?: 0.0
            val horizontal = state.regionLayout == RegionLayout.Horizontal
            shiftRegions(state, i + 1, state.regions.size - 1, if (horizontal) -size else 0.0, if (horizontal) 0.0 else -size)
            if (horizontal) state.w -= size else state.h -= size
            followBorder(state, old)
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
        connectProblem(source, target)?.let {
            say(it)
            return null
        }
        val t = Transition(newId("t"), source.id, target.id)
        model.transitions.add(t)
        selection = linkedSetOf(t.id)
        commit()
        return t
    }

    /** Why a transition can't go from [source] to [target], or null when it can. */
    private fun connectProblem(source: Vertex, target: Vertex): String? = when {
        source.type == VertexType.ConnectionPointRef && source.pointKind == PointKind.Entry ->
            "An entry reference only receives transitions; execution continues inside the submachine."
        target.type == VertexType.ConnectionPointRef && target.pointKind == PointKind.Exit ->
            "An exit reference only has outgoing transitions; it is reached when the submachine exits."
        source.type == VertexType.Final || source.type == VertexType.Terminate ->
            "A ${source.type.title.lowercase()} cannot have outgoing transitions."
        target.type == VertexType.Initial -> "An initial pseudostate cannot be the target of a transition."
        else -> null
    }

    /**
     * Moves the source ([atSource]) or the target of [t] to [v]. Its bend points stay, unless it
     * becomes or stops being a state's self-transition loop, whose bend points are its corners.
     */
    fun reconnect(t: Transition, atSource: Boolean, v: Vertex) {
        if ((if (atSource) t.source else t.target) == v.id) return
        val source = if (atSource) v else (index.vertex(t.source) ?: return)
        val target = if (atSource) (index.vertex(t.target) ?: return) else v
        connectProblem(source, target)?.let {
            say(it)
            return
        }
        val wasLoop = Geometry.isLoop(index, t)
        if (atSource) t.source = v.id else t.target = v.id
        if (Geometry.isLoop(index, t) != wasLoop) t.points.clear()
        commit()
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
        // Reversed, the target becomes the source: refused as drawing it that way would be.
        if (g == null || s == null || connectProblem(g, s) != null) {
            say("This transition cannot be reversed.")
            return
        }
        val source = t.source
        t.source = t.target
        t.target = source
        // A self-transition loop's bend points are its corners, in drawing order: reversed, they would reshape it.
        if (!Geometry.isLoop(index, t)) t.points.reverse()
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
        val old = boxOf(v)
        v.type = type
        val (w, h) = type.defaultSize
        v.w = w
        v.h = h
        v.x = c.x - v.w / 2
        v.y = c.y - v.h / 2
        fitInRegion(v, old, false)
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

    // ---------------------------------------------------------------- layout
    // Elements inside a composite state's region stay inside it: what would leave it grows the region,
    // its state and the states around it in turn, and moving back during the same drag undoes that.

    companion object {
        /** Space kept between a region's border and the elements in it. */
        const val FIT_MARGIN = 10.0
        /** Smallest extent of a region along its state's region layout. */
        const val MIN_REGION = 40.0

        private fun boxOf(v: Vertex) = RectD(v.x, v.y, v.w, v.h)

        /** Box around [vs], or null when there are none. */
        fun unionBox(vs: List<Vertex>): RectD? {
            if (vs.isEmpty()) return null
            val x = vs.minOf { it.x }
            val y = vs.minOf { it.y }
            return RectD(x, y, vs.maxOf { it.x + it.w } - x, vs.maxOf { it.y + it.h } - y)
        }
    }

    /**
     * Header height each composite state's regions were laid out for (by state id): when its internal
     * activities change it, the state grows or shrinks by the difference and its regions' contents move
     * with them. Reset whenever the model comes from the document.
     */
    private var layoutHeader = HashMap<String, Double>()

    init {
        resetHeaders()
    }

    private fun headerOf(s: Vertex) = Geometry.headerHeight(model, s)

    private fun rectsOf(s: Vertex) = Geometry.regionRects(model, s)

    /** The composite state owning region [id], or null. */
    private fun regionOwnerOf(id: String?): Vertex? = if (id == null) null else index.regionOwner[id]

    /** Records the current header height of every composite state. */
    private fun resetHeaders() {
        layoutHeader = HashMap()
        for (v in model.vertices) if (v.regions.isNotEmpty()) layoutHeader[v.id] = headerOf(v)
    }

    /** Geometry of every element (with region sizes and bend points), for [restore]. */
    class LayoutSnapshot internal constructor(
        internal val vertices: List<Triple<Vertex, RectD, List<Double?>>>,
        internal val points: List<Pair<Transition, List<PointD>>>,
        internal val headers: Map<String, Double>,
    )

    fun snapshot() = LayoutSnapshot(
        model.vertices.map { Triple(it, boxOf(it), it.regions.map { r -> r.size }) },
        model.transitions.map { it to it.points.toList() },
        HashMap(layoutHeader),
    )

    fun restore(snap: LayoutSnapshot) {
        for ((v, box, sizes) in snap.vertices) {
            v.x = box.x
            v.y = box.y
            v.w = box.w
            v.h = box.h
            for (i in 0 until minOf(v.regions.size, sizes.size)) v.regions[i].size = sizes[i]
        }
        for ((t, points) in snap.points) t.points = points.toMutableList()
        layoutHeader = HashMap(snap.headers)
    }

    /** The vertices in region [id], at any depth. */
    private fun regionContents(id: String): Set<String> {
        val ids = HashSet<String>()
        for (v in model.vertices) {
            if (v.parent != id) continue
            ids.add(v.id)
            for (d in index.descendants(v)) ids.add(d.id)
        }
        return ids
    }

    /** Bounding box of the vertices directly in region [id], or null when it is empty. */
    private fun contentsBox(id: String): RectD? {
        val kids = model.vertices.filter { it.parent == id }
        if (kids.isEmpty()) return null
        val x = kids.minOf { it.x }
        val y = kids.minOf { it.y }
        return RectD(x, y, kids.maxOf { it.x + it.w } - x, kids.maxOf { it.y + it.h } - y)
    }

    /** Moves the vertices [ids] by [dx], [dy], with the bend points of the transitions between them. */
    private fun shiftVertices(ids: Set<String>, dx: Double, dy: Double) {
        if ((dx == 0.0 && dy == 0.0) || ids.isEmpty()) return
        for (v in model.vertices) {
            if (v.id !in ids) continue
            v.x += dx
            v.y += dy
        }
        for (t in model.transitions) {
            if (t.points.isNotEmpty() && t.source in ids && t.target in ids) t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + dx, it.y + dy) }
        }
    }

    /** Moves the contents of [s]'s regions [from]..[to] (inclusive) by [dx], [dy]. */
    private fun shiftRegions(s: Vertex, from: Int, to: Int, dx: Double, dy: Double) {
        val ids = HashSet<String>()
        for (i in maxOf(0, from)..minOf(to, s.regions.size - 1)) ids.addAll(regionContents(s.regions[i].id))
        shiftVertices(ids, dx, dy)
    }

    /** Keeps the points on the border of [s] on the edge they were on when its box was [old]. */
    private fun followBorder(s: Vertex, old: RectD) {
        for (cp in model.vertices) {
            if (cp.parent != s.id || !index.isBorderVertex(cp)) continue
            val c = cp.center
            val d = listOf(kotlin.math.abs(c.x - old.x), kotlin.math.abs(old.right - c.x), kotlin.math.abs(c.y - old.y), kotlin.math.abs(old.bottom - c.y))
            val m = d.min()
            val p = when (m) {
                d[0] -> PointD(s.x, c.y)
                d[1] -> PointD(s.x + s.w, c.y)
                d[2] -> PointD(c.x, s.y)
                else -> PointD(c.x, s.y + s.h)
            }
            Geometry.snapToBorder(cp, s, p)
        }
    }

    /** Stores the drawn size of each of [s]'s regions, so that they can change one by one. */
    fun keepRegionSizes(s: Vertex) {
        val horizontal = s.regionLayout == RegionLayout.Horizontal
        rectsOf(s).forEachIndexed { i, r -> s.regions[i].size = if (horizontal) r.rect.w else r.rect.h }
    }

    /**
     * Changes the size of region [i] of [s] by [d] at its far end (bottom, or right side by side): the
     * state changes with it and the later regions move with their contents.
     */
    private fun resizeRegionEnd(s: Vertex, i: Int, d: Double) {
        if (d == 0.0) return
        keepRegionSizes(s)
        s.regions[i].size = (s.regions[i].size ?: 0.0) + d
        if (s.regionLayout == RegionLayout.Horizontal) {
            s.w += d
            shiftRegions(s, i + 1, s.regions.size - 1, d, 0.0)
        } else {
            s.h += d
            shiftRegions(s, i + 1, s.regions.size - 1, 0.0, d)
        }
    }

    /**
     * Grows region [i] of [s] on its sides (each amount >= 0): the state grows on the same sides, and what
     * lies beyond the region in its state moves with that side.
     */
    private fun growRegion(s: Vertex, i: Int, left: Double, right: Double, top: Double, bottom: Double) {
        val old = boxOf(s)
        keepRegionSizes(s)
        if (s.regionLayout == RegionLayout.Horizontal) {
            s.y -= top
            s.h += top + bottom
            if (left > 0) {
                s.x -= left
                s.w += left
                s.regions[i].size = (s.regions[i].size ?: 0.0) + left
                shiftRegions(s, 0, i - 1, -left, 0.0)
            }
            resizeRegionEnd(s, i, right)
        } else {
            s.x -= left
            s.w += left + right
            if (top > 0) {
                s.y -= top
                s.h += top
                s.regions[i].size = (s.regions[i].size ?: 0.0) + top
                shiftRegions(s, 0, i - 1, 0.0, -top)
            }
            resizeRegionEnd(s, i, bottom)
        }
        followBorder(s, old)
        keepHeader(s)
    }

    /**
     * After a change of [s]'s header height (its internal activities, or its width rewrapping them), moves
     * its bottom edge by the difference: its regions keep their sizes and their contents move with them.
     * Returns whether it changed.
     */
    private fun keepHeader(s: Vertex): Boolean {
        if (s.regions.isEmpty()) return false
        val h = headerOf(s)
        val before = layoutHeader[s.id]
        layoutHeader[s.id] = h
        if (before == null || before == h) return false
        val old = boxOf(s)
        s.h += h - before
        shiftRegions(s, 0, s.regions.size - 1, 0.0, h - before)
        followBorder(s, old)
        return true
    }

    /** [keepHeader] for every composite state, growing the states around those that grew. */
    private fun keepHeaders() {
        for (s in model.vertices.filter { it.regions.isNotEmpty() }.sortedByDescending { index.depth(it) }) {
            val old = boxOf(s)
            if (keepHeader(s)) fitInRegion(s, old, false)
        }
    }

    /**
     * Grows the region holding [v] (with its state, and the states around it in turn) so that [v] lies inside
     * it with the margin. [old] is its box before the change, or null for a new element: where it already
     * came closer to the region's border, that smaller gap is kept. The growth is a multiple of the grid
     * unless [exact].
     */
    fun fitInRegion(v: Vertex, old: RectD?, exact: Boolean) {
        if (index.isBorderVertex(v)) return
        val s = regionOwnerOf(v.parent) ?: return
        // A state in its own regions (a region id used twice in a file edited by hand) would grow forever.
        if (s === v || index.isInside(s, v.id)) return
        val i = s.regions.indexOfFirst { it.id == v.parent }
        val r = rectsOf(s)[i].rect
        fun gap(g: Double) = if (old != null) minOf(FIT_MARGIN, g) else FIT_MARGIN
        val gl = gap(if (old != null) old.x - r.x else 0.0)
        val gr = gap(if (old != null) r.right - old.right else 0.0)
        val gt = gap(if (old != null) old.y - r.y else 0.0)
        val gb = gap(if (old != null) r.bottom - old.bottom else 0.0)
        fun up(n: Double) = if (n <= 1e-6) 0.0 else if (exact) n else kotlin.math.ceil(n / Geometry.GRID - 1e-6) * Geometry.GRID
        val left = up(r.x + gl - v.x)
        val right = up(v.x + v.w + gr - r.right)
        val top = up(r.y + gt - v.y)
        val bottom = up(v.y + v.h + gb - r.bottom)
        if (left == 0.0 && right == 0.0 && top == 0.0 && bottom == 0.0) return
        val before = boxOf(s)
        growRegion(s, i, left, right, top, bottom)
        fitInRegion(s, before, exact)
    }

    /** How far each edge of a composite state can move inward: the largest left and top, the smallest right and bottom. */
    private class Limits(val left: Double, val right: Double, val top: Double, val bottom: Double)

    /**
     * How far each edge of composite state [s] can move inward without leaving any of its contents outside
     * their region. A gap already smaller than the margin is kept, and each region keeps at least [MIN_REGION].
     */
    private fun resizeLimits(s: Vertex): Limits {
        val rects = rectsOf(s).map { it.rect }
        val n = rects.size
        val boxes = s.regions.map { contentsBox(it.id) }
        val all = boxes.filterNotNull()
        val union = if (all.isEmpty()) null else {
            val ux = all.minOf { it.x }
            val uy = all.minOf { it.y }
            RectD(ux, uy, all.maxOf { it.right } - ux, all.maxOf { it.bottom } - uy)
        }
        // The edge at `edge` may come up to `content` minus the margin, or stay where it is when already closer.
        fun lo(content: Double, edge: Double) = maxOf(content - FIT_MARGIN, minOf(edge, content))
        fun hi(content: Double, edge: Double) = minOf(content + FIT_MARGIN, maxOf(edge, content))
        val first = rects[0]
        val last = rects[n - 1]
        val header = first.y - s.y
        var left = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var top: Double
        var bottom: Double
        if (s.regionLayout == RegionLayout.Horizontal) {
            left = first.right - MIN_REGION
            boxes[0]?.let { left = minOf(left, lo(it.x, first.x)) }
            right = last.x + MIN_REGION
            boxes[n - 1]?.let { right = maxOf(right, hi(it.right, last.right)) }
            top = s.y + s.h - MIN_REGION - header
            bottom = first.y + MIN_REGION
            if (union != null) {
                top = minOf(top, lo(union.y, first.y) - header)
                bottom = maxOf(bottom, hi(union.bottom, s.y + s.h))
            }
        } else {
            if (union != null) {
                left = lo(union.x, s.x)
                right = hi(union.right, s.x + s.w)
            }
            top = first.bottom - MIN_REGION - header
            boxes[0]?.let { top = minOf(top, lo(it.y, first.y) - header) }
            bottom = last.y + MIN_REGION
            boxes[n - 1]?.let { bottom = maxOf(bottom, hi(it.bottom, last.bottom)) }
        }
        return Limits(left, right, top, bottom)
    }

    /** Smallest size (width, height) a vertex can be resized to (a composite state's regions add their own limits). */
    fun minimumSize(v: Vertex): Pair<Double, Double> {
        val bar = v.type == VertexType.Fork || v.type == VertexType.Join
        val minW = if (bar) 6.0 else 40.0
        val minH = when {
            bar -> 6.0
            v.type == VertexType.State -> Geometry.headerHeight(model, v) + if (v.regions.isNotEmpty()) 20 else 6
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

    /** Starts dragging a resize handle of [v] or the line after one of its regions: the layout to restart from. */
    fun beginLayoutDrag(v: Vertex): LayoutSnapshot {
        if (v.regions.isNotEmpty()) keepRegionSizes(v)
        return snapshot()
    }

    /**
     * Resizes [v] from the layout [snap] (taken with its box [x0], [y0], [w0], [h0]) by dragging [corner] by
     * [dx], [dy]; snapped to the grid unless [exact]. A composite state keeps its contents in their regions;
     * side by side the left edge resizes the first region and stacked the top edge does; the region holding
     * [v] grows when it no longer holds it.
     */
    fun resizeFrom(snap: LayoutSnapshot, v: Vertex, corner: String, x0: Double, y0: Double, w0: Double, h0: Double, dx: Double, dy: Double, exact: Boolean) {
        restore(snap)
        val start = boxOf(v)
        val west = 'w' in corner
        val north = 'n' in corner
        val n = v.regions.size
        val horizontal = v.regionLayout == RegionLayout.Horizontal
        val minW = minimumSize(v).first
        var lim = if (n > 0) resizeLimits(v) else null
        if (west) {
            // The right edge stays put.
            val right = x0 + w0
            v.x = minOf(right - minW, if (exact) x0 + dx else Geometry.snap(x0 + dx))
            if (lim != null) v.x = minOf(v.x, lim.left)
            v.w = right - v.x
            if (n > 1 && horizontal) v.regions[0].size = (v.regions[0].size ?: 0.0) - (v.x - x0)
        } else {
            v.w = maxOf(minW, if (exact) w0 + dx else Geometry.snap(v.x + w0 + dx) - v.x)
            if (lim != null) v.w = maxOf(v.w, lim.right - v.x)
        }
        // After the width: the wrapped text depends on it.
        if (n > 0) {
            keepHeader(v)
            lim = resizeLimits(v)
        }
        val y1 = v.y
        val h1 = v.h
        val minH = minimumSize(v).second
        if (north) {
            // The bottom edge stays put.
            val bottom = y1 + h1
            v.y = minOf(bottom - minH, if (exact) y0 + dy else Geometry.snap(y0 + dy))
            if (lim != null) v.y = minOf(v.y, lim.top)
            v.h = bottom - v.y
            if (n > 1 && !horizontal) v.regions[0].size = (v.regions[0].size ?: 0.0) - (v.y - y1)
        } else {
            v.h = maxOf(minH, if (exact) h1 + dy else Geometry.snap(v.y + h1 + dy) - v.y)
            if (lim != null) v.h = maxOf(v.h, lim.bottom - v.y)
        }
        followBorder(v, start)
        fitInRegion(v, start, exact)
    }

    /**
     * Drags the line after region [i] of [s] by [delta] from the layout [snap]: the region resizes (not below
     * its contents or [MIN_REGION]), its state with it, and the later regions move with their contents.
     */
    fun resizeRegionFrom(snap: LayoutSnapshot, s: Vertex, i: Int, delta: Double, exact: Boolean) {
        restore(snap)
        val start = boxOf(s)
        val horizontal = s.regionLayout == RegionLayout.Horizontal
        val r = rectsOf(s)[i].rect
        val from = if (horizontal) r.x else r.y
        val end0 = if (horizontal) r.right else r.bottom
        var end = end0 + if (exact) delta else Geometry.snap(delta)
        var min = from + MIN_REGION
        contentsBox(s.regions[i].id)?.let { c ->
            val far = if (horizontal) c.right else c.bottom
            min = maxOf(min, minOf(far + FIT_MARGIN, maxOf(end0, far)))
        }
        end = maxOf(end, min)
        resizeRegionEnd(s, i, end - end0)
        followBorder(s, start)
        keepHeader(s)
        fitInRegion(s, start, exact)
    }

    /** A dragged element and its position at the start of the drag. */
    class MovedItem(val v: Vertex, val x0: Double, val y0: Double)

    /** A transition whose bend points move with a drag, and its bend points at the start. */
    class MovedPoints(val t: Transition, val points0: List<PointD>)

    /**
     * Moves the dragged elements from the layout [snap]: [items] (the roots and their contents) by [dx], [dy]
     * with the bend points of [transitions]. A connection point dragged alone stays on its state's border,
     * under [p]; other roots keep inside their region, which grows ([boxes] are their boxes at the start).
     */
    fun moveFrom(snap: LayoutSnapshot, items: List<MovedItem>, transitions: List<MovedPoints>, roots: List<Vertex>,
        boxes: Map<String, RectD>, dx: Double, dy: Double, p: PointD, exact: Boolean) {
        restore(snap)
        for (it in items) {
            it.v.x = it.x0 + dx
            it.v.y = it.y0 + dy
        }
        for (it in transitions) it.t.points = it.points0.mapTo(mutableListOf()) { q -> PointD(q.x + dx, q.y + dy) }
        val lead = roots.firstOrNull() ?: return
        if (roots.size == 1 && index.isBorderVertex(lead)) {
            index.vertex(lead.parent)?.let { Geometry.snapToBorder(lead, it, p) }
            return
        }
        for (r in roots) fitInRegion(r, boxes[r.id], exact)
    }

    /**
     * Region a moved element is dropped into: the deepest one holding its whole box, when that is its own
     * region or lies inside it (an element never leaves its region by a drag); null to keep its region.
     */
    fun dropRegionOf(v: Vertex, moving: Set<String>): RegionRect? = dropRegionOf(boxOf(v), v.parent, moving)

    private fun dropRegionOf(box: RectD, parent: String, moving: Set<String>): RegionRect? {
        val r = Geometry.regionHolding(index, box, moving) ?: return null
        return if (r.id != parent && Geometry.regionWithin(index, r.id, parent)) r else null
    }

    /** Region to highlight while [lead] is dragged: where it would be dropped, else its own region. */
    fun dropHighlight(lead: Vertex, moving: Set<String>): RegionRect? =
        dropRegionOf(dropBox(lead), lead.parent, moving) ?: regionOwnerOf(lead.parent)?.let { s -> rectsOf(s).firstOrNull { it.id == lead.parent } }

    /**
     * Switches [s] between stacked and side-by-side regions: they share it equally again, any region too small
     * for its contents grows (with the state), and each region's contents move with it.
     */
    fun setRegionLayout(s: Vertex, layout: RegionLayout) {
        if (s.regionLayout == layout) return
        val old = boxOf(s)
        val before = rectsOf(s).map { it.rect }
        // Each region's contents, relative to the region's top-left corner.
        val rel = s.regions.mapIndexed { i, r -> contentsBox(r.id)?.let { b -> (b.right - before[i].x) to (b.bottom - before[i].y) } ?: (0.0 to 0.0) }
        val n = before.size
        val body = s.h - (before[0].y - s.y)
        s.regionLayout = layout
        for (r in s.regions) r.size = null
        if (layout == RegionLayout.Horizontal) {
            val sizes = rel.map { maxOf(s.w / n, it.first + FIT_MARGIN, MIN_REGION) }
            s.w = sizes.sum()
            sizes.forEachIndexed { i, size -> s.regions[i].size = size }
            s.h = headerOf(s) + maxOf(body, rel.maxOf { it.second + FIT_MARGIN })
        } else {
            s.w = maxOf(s.w, rel.maxOf { it.first + FIT_MARGIN })
            val sizes = rel.map { maxOf(body / n, it.second + FIT_MARGIN, MIN_REGION) }
            sizes.forEachIndexed { i, size -> s.regions[i].size = size }
            s.h = headerOf(s) + sizes.sum()
        }
        layoutHeader[s.id] = headerOf(s)
        val after = rectsOf(s).map { it.rect }
        for (i in 0 until n) shiftVertices(regionContents(s.regions[i].id), after[i].x - before[i].x, after[i].y - before[i].y)
        followBorder(s, old)
        fitInRegion(s, old, false)
        commit()
    }

    /** Warnings for elements whose boxes overlap, other than a state and what it contains; comments don't count. */
    fun overlapIssues(): List<Issue> {
        val output = ArrayList<Issue>()
        val vs = model.vertices.filter { it.type != VertexType.Comment }
        fun describe(v: Vertex) = if (v.name.isNotEmpty()) "'${v.name}'" else "an unnamed ${v.type.title}"
        for (i in vs.indices) {
            val a = vs[i]
            for (j in i + 1 until vs.size) {
                val b = vs[j]
                if (!(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h)) continue
                if (index.isInside(a, b.id) || index.isInside(b, a.id)) continue
                output.add(Issue(a.id, Severity.Warning, "Overlaps ${describe(b)}."))
                output.add(Issue(b.id, Severity.Warning, "Overlaps ${describe(a)}."))
            }
        }
        return output
    }

    /** The selected elements (without a selected ancestor) and their groups, when they all lie in a composite state's region. */
    private fun leavingElements(): List<Vertex> {
        val roots = withGroupMates(selectionRoots())
        return if (roots.isNotEmpty() && roots.all { !index.isBorderVertex(it) && regionOwnerOf(it.parent) != null }) roots else emptyList()
    }

    val canLeaveParent: Boolean get() = leavingElements().isNotEmpty()

    /** Moves the selected elements up one level, out of their composite state, without moving them on the diagram. */
    fun leaveParent() {
        val roots = leavingElements()
        if (roots.isEmpty()) return
        for (v in roots) v.parent = regionOwnerOf(v.parent)!!.parent
        commit()
    }

    /**
     * Aligns the selected elements (without a selected ancestor) with [anchorId]: on its horizontal center
     * line when [horizontally], else on its vertical one. Elements on a state's border don't align. Each
     * element moves with its contents and stays in its region; the enclosing composite states grow so that
     * their regions still hold their elements. An element that is the only selected member of its group moves
     * the whole group, unless the anchor is in that group: selected members of one group are aligned within it.
     * Returns false, changing nothing, when fewer than two elements are selected or when elements of different
     * regions of one state would overlap along its region layout.
     */
    fun align(anchorId: String, horizontally: Boolean): Boolean {
        val items = alignable()
        val clicked = index.vertex(anchorId) ?: return false
        val anchor = index.ancestors(clicked).lastOrNull { it.id in selection } ?: clicked
        if (items.size < 2 || items.none { it === anchor }) return false

        val snap = snapshot()
        val moved = ArrayList<Pair<Vertex, RectD>>()
        val roots = selectionRoots()
        for (v in items) {
            val dx = if (horizontally) 0.0 else anchor.centerX - v.centerX
            val dy = if (horizontally) anchor.centerY - v.centerY else 0.0
            if (dx == 0.0 && dy == 0.0) continue
            // A group follows its only selected member, unless the anchor is in it: aligning several members of a
            // group, or members with the anchor's group, moves just them.
            val mates = if (v.group.isNotEmpty() && v.group != anchor.group && roots.count { it.group == v.group } == 1)
                groupMembers(v.group).filter { it !== v } else emptyList()
            val ids = HashSet<String>()
            for (x in listOf(v) + mates) {
                moved.add(x to boxOf(x))
                ids.add(x.id)
                for (d in index.descendants(x)) ids.add(d.id)
            }
            shiftVertices(ids, dx, dy)
        }
        if (moved.isEmpty()) return false

        // Innermost first: a state that grows may no longer fit in its own region.
        for ((v, old) in moved.sortedByDescending { index.depth(it.first) }) fitInRegion(v, old, true)
        fun along(v: Vertex) = if (horizontally) v.centerY else v.centerX
        val off = items.firstOrNull { kotlin.math.abs(along(it) - along(anchor)) > 0.5 }
        if (off != null) {
            // Growing one region moved the others: elements in different regions of one state can't line up.
            restore(snap)
            val shared = index.ancestors(off).firstOrNull { index.isInside(anchor, it.id) }
            say("The elements cannot be aligned: they would leave their regions of '${shared?.name ?: ""}'.")
            return false
        }
        commit()
        return true
    }

    /** The selected elements (without a selected ancestor) that Align moves: all but those on a state's border. */
    fun alignable(): List<Vertex> = selectionRoots().filter { !index.isBorderVertex(it) }

    /** Selected elements without a selected ancestor, which lead a move. */
    fun selectionRoots(): List<Vertex> =
        selection.mapNotNull { index.vertex(it) }.filter { v -> index.ancestors(v).none { it.id in selection } }

    /** After a move: re-parents the moved elements into the region (or state border) they were dropped on. */
    fun dropMoved(roots: List<Vertex>, moving: Set<String>) {
        reindex()
        for (v in roots) {
            if (index.isBorderVertex(v)) continue
            if (v.group.isNotEmpty()) {
                // A group drops as a whole: into the region that holds all its members, or nowhere.
                if (v !== roots.first { it.group == v.group }) continue
                val gr = dropRegionOf(dropBox(v), v.parent, moving)
                if (gr != null) for (m in groupMembers(v.group)) m.parent = gr.id
                continue
            }
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
            dropRegionOf(v, moving)?.let { v.parent = it.id }
        }
        commit()
    }

    // ---------------------------------------------------------------- groups

    /** Members of group [id], in file order. */
    fun groupMembers(id: String): List<Vertex> = if (id.isEmpty()) emptyList() else model.vertices.filter { it.group == id }

    /** [vs] with the other members of their groups, each once. */
    fun withGroupMates(vs: List<Vertex>): List<Vertex> {
        val out = LinkedHashMap<String, Vertex>()
        for (v in vs) out.putIfAbsent(v.id, v)
        for (v in vs) for (m in groupMembers(v.group)) out.putIfAbsent(m.id, m)
        return out.values.toList()
    }

    /** The box a moved element drops by: with a group, the box around all its members. */
    fun dropBox(v: Vertex): RectD = (if (v.group.isNotEmpty()) unionBox(groupMembers(v.group)) else null) ?: boxOf(v)

    /**
     * The elements a Group makes one group of: the selected ones and their groups' other members, when there
     * are at least two, in one region, none on a state's border, and they aren't already one group.
     */
    private fun groupable(): List<Vertex> {
        val vs = withGroupMates(selectionRoots())
        if (vs.size < 2 || vs.any { index.isBorderVertex(it) || it.parent != vs[0].parent }) return emptyList()
        return if (vs[0].group.isNotEmpty() && vs.all { it.group == vs[0].group }) emptyList() else vs
    }

    val canGroup: Boolean get() = groupable().isNotEmpty()

    val canUngroup: Boolean get() = selection.any { index.vertex(it)?.group?.isNotEmpty() == true }

    fun group() {
        val vs = groupable()
        if (vs.isEmpty()) return
        val id = newId("g")
        for (v in vs) v.group = id
        commit()
    }

    /** Dissolves the groups of the selected elements. */
    fun ungroup() {
        val ids = selection.mapNotNull { index.vertex(it)?.group?.takeIf { g -> g.isNotEmpty() } }.toSet()
        if (ids.isEmpty()) return
        for (v in model.vertices) if (v.group in ids) v.group = ""
        commit()
    }

    /** G: ungroups the selection when an element of it is in a group, groups it otherwise. */
    fun toggleGroup() {
        when {
            canUngroup -> ungroup()
            canGroup -> group()
            else -> say("Select two or more elements in the same region to group them.")
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

    /**
     * Adds a copy of [data]. Without [at] (keyboard paste, duplicate), each element goes into its original's
     * region when there is one, offset further at each repeat; with [at] (context menu), the copy's top-left
     * corner goes there, into the region under it. Regions grow to hold the copy.
     */
    fun paste(data: ClipboardFragment?, at: PointD? = null) {
        if (data == null || data.vertices.isEmpty()) return
        reindex()
        // A connection point reference whose state is neither copied nor in this diagram is left out, with its transitions.
        val copied = data.vertices.map { it.id }.toSet()
        val vertices = data.vertices.filter { it.type != VertexType.ConnectionPointRef || it.parent in copied || index.vertex(it.parent) != null }
        if (vertices.isEmpty()) return
        val map = HashMap<String, String>()
        for (v in vertices) {
            map[v.id] = newId("v")
            for (r in v.regions) map[r.id] = newId("r")
        }
        // Parents copied in a cycle (crafted clipboard text): nothing would be a root.
        if (vertices.none { it.parent !in map }) return
        // Before the comments, whose anchors may be copied transitions.
        for (t in data.transitions) if (t.source in map && t.target in map) map[t.id] = newId("t")
        val offX: Double
        val offY: Double
        var target: RegionRect? = null
        if (at != null) {
            val roots = vertices.filter { it.parent !in map }
            // Parents that are the ids of copied transitions (crafted clipboard text): no root to place.
            if (roots.isEmpty()) return
            offX = Geometry.snap(at.x) - roots.minOf { it.x }
            offY = Geometry.snap(at.y) - roots.minOf { it.y }
            target = Geometry.regionAt(index, at, emptySet())
        } else {
            pasteCount++
            offX = 20.0 * pasteCount
            offY = offX
        }
        val added = ArrayList<Vertex>()
        val groups = HashMap<String, String>()
        for (src in vertices) {
            val v = src.copy()
            v.id = map[src.id]!!
            // A copied group is a new group; a lone copied member belongs to none (see FsmModel.normalizeGroups).
            if (v.group.isNotEmpty()) v.group = groups.getOrPut(v.group) { newId("g") }
            v.x += offX
            v.y += offY
            v.regions = v.regions.mapTo(mutableListOf()) { Region(map[it.id]!!, it.name, it.size) }
            val parent = map[v.parent]
            if (parent != null) {
                v.parent = parent
            } else if (index.isBorderVertex(v)) {
                // Pasted without its state: on that state's border when it still exists.
                val state = index.vertex(v.parent) ?: continue
                Geometry.snapToBorder(v, state, v.center)
            } else if (at != null && v.type != VertexType.EntryPoint && v.type != VertexType.ExitPoint) {
                v.parent = target?.id ?: rootRegion
            } else if (at != null || !index.isRegion(v.parent)) {
                v.parent = rootRegion
            }
            v.anchors = v.anchors.filter { it in map }.mapTo(mutableListOf()) { map[it]!! }
            model.vertices.add(v)
            added.add(v)
        }
        for (src in data.transitions) {
            if (src.source !in map || src.target !in map) continue
            val t = src.copy()
            t.id = map[src.id]!!
            t.source = map[src.source]!!
            t.target = map[src.target]!!
            t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + offX, it.y + offY) }
            model.transitions.add(t)
        }
        reindex()
        val addedIds = added.map { it.id }.toSet()
        val pastedRoots = added.filter { v -> index.ancestors(v).none { it.id in addedIds } }
        for (v in pastedRoots) fitInRegion(v, null, false)
        selection = pastedRoots.mapTo(LinkedHashSet()) { it.id }
        commit()
    }
}
