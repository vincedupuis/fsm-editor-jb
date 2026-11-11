package com.vincegosoftware.fsmeditor.core

import kotlin.math.max

/** The selection cannot be extracted; the message says why. */
class ExtractException(message: String) : Exception(message)

class ExtractResult(
    /** The edited machine, with the submachine state in place of the extracted elements. */
    val parent: FsmModel,
    /** The new state machine. */
    val machine: FsmModel,
    /** Id of the submachine state in [parent]. */
    val stateId: String,
)

/**
 * Extract to Submachine (src/extract.ts of the VS Code edition): moves selected elements of a state machine into a new
 * state machine, and puts a submachine state referencing it in their place.
 *
 * - Several elements of one region: they move with their contents, the transitions between them, and the comments
 *   and groups among them. A submachine state covering their bounding box replaces them.
 * - One composite state with one region: its contents move, and the state becomes the submachine state, keeping its
 *   name, behaviors, transitions and place. Its own entry/exit points become the new machine's, and connection point
 *   references with the same ids take their place on its border. A composite state with several regions moves whole,
 *   as above, since the new machine has one region.
 *
 * A transition crossing the boundary is split at a connection point, keeping its trigger, guard and effect on its
 * source's side: an incoming one goes to an entry point reference on the submachine state, then from the new
 * machine's entry point to its target; an outgoing one goes to an exit point of the new machine, then from the exit
 * point reference to its target. There is one entry point per inner target and one exit point per outer target. An
 * incoming transition to the target of the new machine's initial transition (without guard or effect) targets the
 * submachine state itself (default entry) instead.
 */
object Extraction {
    private const val POINT = 16.0
    private const val MACHINE_ID = "sm"

    /** The name proposed for the new machine: the extracted state's name when there is one, else `Submachine`. */
    fun proposedName(model: FsmModel, selected: Collection<String>): String {
        val states = extractedRoots(model, selected).filter { it.type == VertexType.State }
        val name = if (states.size == 1) states[0].name.trim() else ""
        return name.ifEmpty { "Submachine" }
    }

    /** Why [selected] can't be extracted, or null when it can. */
    fun problem(model: FsmModel, selected: Collection<String>): String? =
        try {
            extract(model, selected, "Submachine", "Submachine.fsm#sm")
            null
        } catch (e: ExtractException) {
            e.message
        }

    /** The selected vertices without a selected ancestor, with the other members of their groups, in file order. */
    private fun extractedRoots(model: FsmModel, selected: Collection<String>): List<Vertex> {
        val ix = ModelIndex(model)
        val ids = selected.filter { it in ix.vertices }.toSet()
        val roots = ids.filter { id -> ix.ancestors(ix.vertices.getValue(id)).none { it.id in ids } }.toSet()
        val groups = model.vertices.filter { it.id in roots && it.group.isNotEmpty() }.map { it.group }.toSet()
        return model.vertices.filter { it.id in roots || (it.group.isNotEmpty() && it.group in groups) }
    }

    /**
     * Extracts the [selected] vertices of [model] into a new state machine named [name], referenced as [href]. [model]
     * is left unchanged. Throws an [ExtractException] when the selection can't be extracted.
     */
    fun extract(model: FsmModel, selected: Collection<String>, name: String, href: String): ExtractResult {
        val m = model.copy().normalize()
        val ix = ModelIndex(m)
        val roots = extractedRoots(m, selected)
        fun label(v: Vertex?) = if (v != null && v.name.isNotEmpty()) "'${v.name}'" else "the ${v?.type?.key ?: "element"}"

        if (roots.none { it.type != VertexType.Comment }) throw ExtractException("Select the states and pseudostates to extract.")
        val border = roots.firstOrNull { ix.isBorderVertex(it) }
        if (border != null) throw ExtractException("Points on a state's border can't be extracted without their state (${label(border)}).")
        if (roots.any { it.parent != roots[0].parent }) throw ExtractException("Select elements of one region to extract them.")

        // A composite state with several regions moves whole instead: the new machine has one region.
        val single = roots.singleOrNull()?.takeIf { it.type == VertexType.State && it.regions.size == 1 && it.submachine.isEmpty() }

        // Vertices moving to the new machine.
        val moved = HashSet<String>()
        // Entry/exit points of the extracted composite state, which become the new machine's.
        val ownPoints = mutableListOf<String>()
        if (single != null) {
            for (v in m.vertices) {
                if (v.parent == single.id && ix.isBorderVertex(v)) ownPoints.add(v.id)
                else if (ix.isInside(v, single.id)) moved.add(v.id)
            }
            moved.addAll(ownPoints)
        } else {
            for (r in roots) moved.add(r.id)
            for (v in m.vertices) if (roots.any { ix.isInside(v, it.id) }) moved.add(v.id)
        }

        fun isIn(id: String) = id in moved
        val crossing = m.transitions.filter { isIn(it.source) != isIn(it.target) && it.source !in ownPoints && it.target !in ownPoints }
        fun history(v: Vertex?) = v != null && (v.type == VertexType.ShallowHistory || v.type == VertexType.DeepHistory)
        for (t in crossing) {
            val s = ix.vertex(t.source)
            val g = ix.vertex(t.target)
            val what = "the transition from ${label(s)} to ${label(g)}"
            if (t.kind != TransitionKind.External) {
                throw ExtractException("Make $what external first: a ${t.kind.key} transition can't cross the submachine's boundary.")
            }
            if (history(s) || history(g)) {
                throw ExtractException("${capitalize(what)} goes to or from a history pseudostate across the boundary, which a submachine can't express.")
            }
        }

        // Ids, new ones unique in the model they go to.
        val parentIds = HashSet<String>()
        m.vertices.forEach { parentIds.add(it.id) }
        m.transitions.forEach { parentIds.add(it.id) }
        m.regions.forEach { parentIds.add(it.id) }
        m.vertices.forEach { v -> v.regions.forEach { parentIds.add(it.id) } }
        val machineIds = HashSet<String>()
        for (v in m.vertices) {
            if (!isIn(v.id)) continue
            machineIds.add(v.id)
            v.regions.forEach { machineIds.add(it.id) }
        }
        for (t in m.transitions) if (isIn(t.source) && isIn(t.target)) machineIds.add(t.id)
        fun fresh(taken: HashSet<String>, prefix: String): String {
            var n = 1
            while ("${prefix}_x$n" in taken) n++
            val id = "${prefix}_x$n"
            taken.add(id)
            return id
        }

        // The region of the new machine.
        val region = if (single != null) Region(single.regions[0].id, single.regions[0].name) else Region(fresh(machineIds, "r"))
        val outerRegion = roots[0].parent

        // The submachine state.
        val state: Vertex
        if (single != null) {
            state = single
            state.regions = mutableListOf()
            state.regionLayout = RegionLayout.Vertical
            state.submachine = href
        } else {
            val box = unionBox(roots)
            val names = m.vertices.filter { !isIn(it.id) }.map { it.name }.toSet()
            var stateName = name
            var n = 2
            while (stateName in names) stateName = name + n++
            state = Vertex(fresh(parentIds, "v"), VertexType.State, stateName, outerRegion, box.x, box.y, max(box.w, 140.0), max(box.h, 60.0))
            state.submachine = href
        }

        // Layout of the new machine: its contents from (120, 60), entry points on the left, exit points on the right.
        val content = m.vertices.filter { isIn(it.id) && it.id !in ownPoints }
        val contentBox = if (content.isNotEmpty()) unionBox(content) else RectD(0.0, 0.0, 0.0, 0.0)
        val dx = 120 - contentBox.x
        val dy = 60 - contentBox.y

        val machineVertices = mutableListOf<Vertex>()
        val machineTransitions = mutableListOf<Transition>()
        val parentVertices = mutableListOf<Vertex>()
        val parentTransitions = mutableListOf<Transition>()
        for (v in m.vertices) {
            if (!isIn(v.id)) {
                parentVertices.add(v)
                continue
            }
            if (v.id in ownPoints) continue // placed below
            v.x += dx
            v.y += dy
            if (single == null && v.parent == outerRegion) v.parent = region.id
            machineVertices.add(v)
        }
        if (single == null) parentVertices.add(firstIndex(m.vertices, roots, ::isIn), state)
        // Transitions inside move; those outside stay, and so do those of the composite state's own points.
        for (t in m.transitions) {
            if (isIn(t.source) && isIn(t.target)) {
                t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + dx, it.y + dy) }
                machineTransitions.add(t)
            } else if (t !in crossing) {
                parentTransitions.add(t)
            }
        }

        val entries = mutableListOf<Vertex>()
        val exits = mutableListOf<Vertex>()
        var entryRefs = mutableListOf<Vertex>()
        var exitRefs = mutableListOf<Vertex>()
        val pointNames = HashSet<String>()
        fun pointName(base: String): String {
            var n = base
            var i = 2
            while (n in pointNames) n = base + i++
            pointNames.add(n)
            return n
        }

        // The composite state's own points: same ids in both machines (a reference in the parent).
        for (id in ownPoints) {
            val p = ix.vertices.getValue(id)
            val kind = if (p.type == VertexType.EntryPoint) PointKind.Entry else PointKind.Exit
            val r = reference(p.id, state.id, p.id, kind)
            r.x = p.x
            r.y = p.y
            r.w = p.w
            r.h = p.h
            r.color = p.color
            parentVertices.add(r)
            val point = p.copy()
            point.parent = region.id
            point.name = pointName(p.name.ifEmpty { if (kind == PointKind.Entry) "in" else "out" })
            point.group = ""
            (if (kind == PointKind.Entry) entries else exits).add(point)
        }

        // Default entry: the target of the new machine's initial transition, when it has no guard or effect.
        val initial = machineVertices.firstOrNull { it.type == VertexType.Initial && it.parent == region.id }
        val initialOut = if (initial != null) machineTransitions.filter { it.source == initial.id } else emptyList()
        val defaultTarget = if (initialOut.size == 1 && initialOut[0].guard.isBlank() && initialOut[0].effect.isBlank()) initialOut[0].target else null

        // By inner target: the reference on the submachine state that incoming transitions go to.
        val entryRefFor = HashMap<String, String>()
        // By outer target: the new machine's exit point that outgoing transitions go to.
        val exitFor = HashMap<String, String>()
        fun targetName(v: Vertex?): String {
            if (v == null) return ""
            if (v.type == VertexType.ConnectionPointRef) return targetName(ix.vertex(v.parent)).ifEmpty { "Point" }
            return identifier(v.name).ifEmpty { capitalize(typeName(v.type)) }
        }
        for (t in crossing) {
            t.points = mutableListOf()
            t.labelOffset = null
            if (isIn(t.target)) {
                if (t.target == defaultTarget) {
                    t.target = state.id
                    parentTransitions.add(t)
                    continue
                }
                val refId = entryRefFor.getOrPut(t.target) {
                    val entry = newPoint(fresh(machineIds, "v"), VertexType.EntryPoint, pointName("to" + targetName(ix.vertex(t.target))), region.id)
                    entries.add(entry)
                    machineTransitions.add(plain(fresh(machineIds, "t"), entry.id, t.target))
                    val r = reference(fresh(parentIds, "v"), state.id, entry.id, PointKind.Entry)
                    entryRefs.add(r)
                    r.id
                }
                t.target = refId
                parentTransitions.add(t)
            } else {
                val exitId = exitFor.getOrPut(t.target) {
                    val exit = newPoint(fresh(machineIds, "v"), VertexType.ExitPoint, pointName("to" + targetName(ix.vertex(t.target))), region.id)
                    exits.add(exit)
                    val r = reference(fresh(parentIds, "v"), state.id, exit.id, PointKind.Exit)
                    exitRefs.add(r)
                    parentTransitions.add(plain(fresh(parentIds, "t"), r.id, t.target))
                    exit.id
                }
                t.target = exitId
                machineTransitions.add(t)
            }
        }

        // Connection points of the new machine, beside the vertex they lead to or come from.
        val contentRight = if (content.isNotEmpty()) 120 + contentBox.w else 120.0
        fun besideOf(p: Vertex): Double {
            val linked = if (p.type == VertexType.EntryPoint) machineTransitions.firstOrNull { it.source == p.id }?.target
            else machineTransitions.firstOrNull { it.target == p.id }?.source
            val v = machineVertices.firstOrNull { it.id == linked }
            return if (v != null) v.y + v.h / 2 else 60.0
        }
        placeColumn(entries, 40.0, ::besideOf)
        placeColumn(exits, contentRight + 60, ::besideOf)
        machineVertices.addAll(entries)
        machineVertices.addAll(exits)

        // References on the submachine state's border, in the order of their points: entries on the left, exits on the right.
        val pointY = (entries + exits).associate { it.id to it.y }
        entryRefs = entryRefs.sortedBy { pointY.getValue(it.ref) }.toMutableList()
        exitRefs = exitRefs.sortedBy { pointY.getValue(it.ref) }.toMutableList()
        spreadOnBorder(entryRefs, state, state.x)
        spreadOnBorder(exitRefs, state, state.x + state.w)
        parentVertices.addAll(entryRefs)
        parentVertices.addAll(exitRefs)

        // The initial pseudostate moved out of the region: the region gets a new one, to the submachine state.
        if (single == null && initial != null && initial.parent == region.id && roots.any { it.id == initial.id }) {
            val init = Vertex(fresh(parentIds, "v"), VertexType.Initial, "", outerRegion, state.x - 60, state.y + state.h / 2 - 10, 20.0, 20.0)
            parentVertices.add(init)
            parentTransitions.add(plain(fresh(parentIds, "t"), init.id, state.id))
        }

        // Comment anchors: an outside comment anchored to an extracted element anchors the submachine state instead.
        val machineElements = (machineVertices.map { it.id } + machineTransitions.map { it.id }).toSet()
        val parentElements = (parentVertices.map { it.id } + parentTransitions.map { it.id }).toSet()
        for (v in machineVertices) v.anchors = v.anchors.filterTo(mutableListOf()) { it in machineElements }
        for (v in parentVertices) {
            v.anchors = v.anchors
                .mapNotNull { if (it in parentElements) it else if (it in machineElements) state.id else null }
                .distinct().toMutableList()
        }

        m.vertices = parentVertices
        m.transitions = parentTransitions
        val machine = FsmModel()
        machine.id = MACHINE_ID
        machine.name = name
        machine.kind = m.kind
        machine.regions = mutableListOf(region)
        machine.vertices = machineVertices
        machine.transitions = machineTransitions
        return ExtractResult(m.normalize(), machine.normalize(), state.id)
    }

    private fun typeName(t: VertexType): String = when (t) {
        VertexType.EntryPoint -> "entry"
        VertexType.ExitPoint -> "exit"
        else -> t.key
    }

    private fun capitalize(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    /** [name] as one word: letters, digits and underscores, each word capitalized (`Wait for payment` → `WaitForPayment`). */
    private fun identifier(name: String): String =
        name.split(Regex("[^A-Za-z0-9_]+")).filter { it.isNotEmpty() }.joinToString("") { capitalize(it) }

    private fun newPoint(id: String, type: VertexType, name: String, parent: String) = Vertex(id, type, name, parent, 0.0, 0.0, POINT, POINT)

    private fun reference(id: String, parent: String, pointId: String, kind: PointKind): Vertex {
        val v = Vertex(id, VertexType.ConnectionPointRef, "", parent, 0.0, 0.0, POINT, POINT)
        v.ref = pointId
        v.pointKind = kind
        return v
    }

    private fun plain(id: String, source: String, target: String) = Transition(id, source, target)

    private fun unionBox(vs: List<Vertex>): RectD {
        val x = vs.minOf { it.x }
        val y = vs.minOf { it.y }
        return RectD(x, y, vs.maxOf { it.x + it.w } - x, vs.maxOf { it.y + it.h } - y)
    }

    /** Where the submachine state goes in the vertex list: where the first extracted vertex was. */
    private fun firstIndex(all: List<Vertex>, roots: List<Vertex>, isIn: (String) -> Boolean): Int {
        var kept = 0
        for (v in all) {
            if (v.id == roots[0].id) return kept
            if (!isIn(v.id)) kept++
        }
        return kept
    }

    /** Stacks points in a column at [x], each near the height [yOf] gives, without overlapping. */
    private fun placeColumn(points: List<Vertex>, x: Double, yOf: (Vertex) -> Double) {
        var next = Double.NEGATIVE_INFINITY
        for ((p, y) in points.map { it to yOf(it) - POINT / 2 }.sortedBy { it.second }) {
            p.x = x
            p.y = max(y, next)
            next = p.y + POINT + 24
        }
    }

    /** Spreads references evenly along the vertical border of [state] at [x]. */
    private fun spreadOnBorder(refs: List<Vertex>, state: Vertex, x: Double) {
        refs.forEachIndexed { i, r ->
            r.x = x - r.w / 2
            r.y = state.y + state.h * (i + 1) / (refs.size + 1) - r.h / 2
        }
    }
}
