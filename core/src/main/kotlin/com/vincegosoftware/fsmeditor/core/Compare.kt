package com.vincegosoftware.fsmeditor.core

enum class ChangeKind { Added, Removed, Changed }

/** A property that differs, with its two values as text ("" when absent). */
class PropertyChange(val name: String, val old: String, val new: String, /** A property of the drawing, not of the model. */ val layout: Boolean)

class Change(
    /** What identifies the element in the newer version (see [Compare.itemsOf]). */
    val key: String,
    /** Its key in the older version, when it differs (a transition whose target or trigger changed). */
    val oldKey: String?,
    val kind: ChangeKind,
    /** `machine`, `state`, `choice`…, `region`, `transition`. */
    val what: String,
    /** How a person names it: `state 'Off'`, `transition Off -> On (powerOn)`. */
    val label: String,
    /** The element's id in each version. */
    val oldId: String?,
    val newId: String?,
    /** For [ChangeKind.Changed]: what differs. */
    val properties: List<PropertyChange> = emptyList(),
)

/** Properties as text, in the order they were set. */
class Props {
    private val values = LinkedHashMap<String, String>()

    operator fun get(key: String): String? = values[key]

    operator fun set(key: String, value: String) {
        values[key] = value
    }

    val keys: List<String> get() = values.keys.toList()
}

/** What can be compared in a model: an element by key, with its properties. */
class CompareItem(val key: String, val id: String, val what: String, val label: String, val model: Props, val layout: Props)

/** The items of a model in order, by key. */
class CompareItems {
    val list = ArrayList<CompareItem>()
    val byKey = HashMap<String, CompareItem>()

    operator fun get(key: String?): CompareItem? = if (key == null) null else byKey[key]

    fun has(key: String?) = key != null && key in byKey

    internal fun set(item: CompareItem) {
        if (item.key !in byKey) list.add(item) else list[list.indexOfFirst { it.key == item.key }] = item
        byKey[item.key] = item
    }
}

/**
 * Comparing two versions of a machine, for the diagram diff (two panes) and merge (three panes). A port of the Visual
 * Studio edition's `Compare.cs` (the VS Code edition's `src/compare.ts`).
 *
 * Elements are matched by what identifies them in the file: an element by its name, a region of `regions` by its state
 * and name, a point by its state and name, an initial or history pseudostate by its region, a transition by its source,
 * trigger and target (then by order among identical ones; a transition left over is then paired with one of the same
 * source and trigger, else of the same source, in order, its target or trigger having changed). A renamed element is
 * one removed and one added.
 *
 * Each match lists the properties that differ, model and layout apart: positions are compared relative to the owner
 * (moving a state doesn't change what it contains), bend points relative to the innermost state containing both ends.
 */
object Compare {
    private fun num1(n: Double?): String = if (n != null && !n.isNaN() && !n.isInfinite()) Num.format(Num.r1(n)) else ""

    private fun whatOf(t: VertexType): String = when (t) {
        VertexType.ShallowHistory -> "history"
        VertexType.DeepHistory -> "deep history"
        VertexType.EntryPoint -> "entry point"
        VertexType.ExitPoint -> "exit point"
        VertexType.ConnectionPointRef -> "point"
        VertexType.Final -> "final state"
        VertexType.Terminate -> "terminate"
        else -> t.key
    }

    private fun shown(name: String) = Format.shown(name)

    /** The items of a model, keyed so that the same element has the same key in another version. */
    fun itemsOf(model: FsmModel): CompareItems {
        val vs = HashMap<String, Vertex>()
        for (v in model.vertices) if (v.id !in vs) vs[v.id] = v
        val regionOwner = HashMap<String, Vertex?>()
        val regions = HashMap<String, Region>()
        for (r in model.regions) {
            regionOwner[r.id] = null
            regions[r.id] = r
        }
        for (v in model.vertices) for (r in v.regions) {
            regionOwner[r.id] = v
            regions[r.id] = r
        }
        val top = model.regions.mapTo(HashSet()) { it.id }
        fun isBorder(v: Vertex) = v.type == VertexType.ConnectionPointRef || ((v.type == VertexType.EntryPoint || v.type == VertexType.ExitPoint) && v.parent in vs)
        fun isMachinePoint(v: Vertex) = (v.type == VertexType.EntryPoint || v.type == VertexType.ExitPoint) && v.parent in top
        fun owner(v: Vertex): Vertex? = if (isBorder(v)) vs[v.parent] else regionOwner[v.parent]
        // A region's key: its owner and name, or index for a state's only region.
        fun regionKey(rid: String): String {
            val o = regionOwner[rid]
            val list = o?.regions ?: model.regions
            val r = regions[rid]
            val at = if (list.size > 1) ":${r?.name ?: ""}" else ""
            return if (o != null) "${o.name}$at" else at
        }
        fun vertexKey(v: Vertex): String {
            if (v.type == VertexType.Initial || v.type == VertexType.ShallowHistory || v.type == VertexType.DeepHistory) return "${v.type.key}@${regionKey(v.parent)}"
            if (isBorder(v)) return "point@${vs[v.parent]?.name ?: ""}.${if (v.type == VertexType.ConnectionPointRef) v.ref else v.name}"
            if (isMachinePoint(v)) return "point@.${v.name}"
            return "element@${v.name}"
        }
        val keys = HashMap<String, String>()
        for (v in model.vertices) keys[v.id] = vertexKey(v)
        fun refOf(id: String): String {
            if (id !in vs) return if (id.startsWith(Format.UNRESOLVED)) id.substring(1) else id
            val k = keys.getValue(id)
            return when {
                k.startsWith("element@") -> k.substring(8)
                k.startsWith("point@.") -> "${model.name}." + k.substring(7)
                k.startsWith("point@") -> k.substring(6)
                else -> k
            }
        }
        fun nameOf(v: Vertex) = shown(if (v.type == VertexType.ConnectionPointRef) v.ref else v.name)
        // Where a vertex is: its state, and the region of a state (or machine) with several.
        fun placeOf(v: Vertex): String {
            val o = owner(v)
            if (isBorder(v)) return if (o != null) "'${shown(o.name)}'" else ""
            val list = o?.regions ?: model.regions
            val i = list.indexOfFirst { it.id == v.parent }
            val regionName = if (i >= 0) shown(list[i].name) else ""
            val region = if (list.size > 1) "region '${regionName.ifEmpty { (i + 1).toString() }}'" else ""
            return if (o != null) "'${shown(o.name)}'${if (region.isNotEmpty()) ", $region" else ""}" else region
        }
        val ts = model.transitions.associateBy { it.id }
        // A comment's anchor: a reference, or a transition as `source -> target (trigger)`.
        fun anchorOf(a: String): String {
            if (a in vs) return refOf(a)
            val t = ts[a] ?: return if (a.startsWith(Format.UNRESOLVED) || a.startsWith(Format.UNRESOLVED_ANCHOR)) a.substring(1) else a
            val trigger = t.triggers.joinToString(", ")
            return "${refOf(t.source)} -> ${if (t.kind == TransitionKind.Internal) "(internal)" else refOf(t.target)}${if (trigger.isNotEmpty()) " ($trigger)" else ""}"
        }
        fun labelOf(v: Vertex): String {
            val what = whatOf(v.type)
            val n = nameOf(v)
            val o = owner(v)
            return if (n.isNotEmpty()) "$what '$n'" else if (o != null && shown(o.name).isNotEmpty()) "$what in '${shown(o.name)}'" else what
        }
        fun frame(t: Transition): Vertex? {
            fun chain(id: String): List<Vertex> {
                val output = ArrayList<Vertex>()
                var v = vs[id]
                if (v != null && v.type != VertexType.State) v = owner(v)
                val seen = HashSet<Vertex>()
                while (v != null && seen.add(v)) {
                    output.add(v)
                    v = owner(v)
                }
                return output
            }
            val g = chain(t.target).toHashSet()
            return chain(t.source).firstOrNull { it in g }
        }
        fun colors(color: String, items: Map<String, String>, into: Props) {
            into["color"] = color
            for (k in ElementColors.ITEM_KEYS) {
                val c = items[k]
                if (!c.isNullOrEmpty()) into["color of $k"] = c
            }
        }

        val result = CompareItems()
        val machine = Props()
        machine["name"] = model.name
        machine["type"] = if (model.kind == MachineKind.Protocol) "protocol" else "behavioral"
        machine["documentation"] = model.documentation
        result.set(CompareItem("machine", "", "machine", "the state machine", machine, Props()))
        for (v in model.vertices) {
            val key = keys.getValue(v.id)
            if (result.has(key)) continue // a name used twice: the first
            val o = owner(v)
            val m = Props()
            m["type"] = v.type.key
            m["in"] = placeOf(v)
            if (v.type == VertexType.State) {
                m["entry"] = v.entry
                m["exit"] = v.exit
                m["do"] = v.doActivity
                m["defer"] = v.deferrable.joinToString(", ")
                m["invariant"] = v.invariant
                m["stereotype"] = v.stereotype
                m["submachine"] = v.submachine
                m["regions"] = if (v.regions.size > 1) v.regions.joinToString(", ") { shown(it.name).ifEmpty { "(unnamed)" } } else v.regions.size.toString()
            }
            if (v.type == VertexType.Comment) {
                m["text"] = v.text
                m["anchors"] = v.anchors.joinToString(", ") { anchorOf(it) }
            }
            if (v.type == VertexType.ConnectionPointRef) m["direction"] = if (v.pointKind == PointKind.Exit) "exit" else "entry"
            // Where it is, relative to its owner.
            val ox = o?.x ?: 0.0
            val oy = o?.y ?: 0.0
            val l = Props()
            l["x"] = num1(v.x - ox)
            l["y"] = num1(v.y - oy)
            l["width"] = num1(v.w)
            l["height"] = num1(v.h)
            l["group"] = if (v.group.isNotEmpty()) "grouped" else ""
            if (v.regions.size > 1) {
                l["region layout"] = if (v.regionLayout == RegionLayout.Horizontal) "horizontal" else "vertical"
                for ((i, r) in v.regions.withIndex()) l["size of region ${shown(r.name).ifEmpty { (i + 1).toString() }}"] = num1(r.size)
            }
            colors(v.color, v.itemColors, l)
            result.set(CompareItem(key, v.id, whatOf(v.type), labelOf(v), m, l))
        }
        // Transitions: by source, trigger and target, then order.
        val seen = HashMap<String, Int>()
        for (t in model.transitions) {
            val s = vs[t.source]
            val source = if (s != null) keys.getValue(s.id) else "?${t.source}"
            val trigger = t.triggers.joinToString(", ")
            val target = if (t.kind == TransitionKind.Internal) "" else refOf(t.target)
            val base = "transition@$source|$trigger|$target"
            val n = seen[base] ?: 0
            seen[base] = n + 1
            val key = "$base|$n"
            val sv = if (s != null) shown(s.name).ifEmpty { labelOf(s) } else t.source
            val label = "transition $sv -> ${if (t.kind == TransitionKind.Internal) "(internal)" else target.removeSuffix("*")}${if (trigger.isNotEmpty()) " ($trigger)" else ""}"
            val f = frame(t)
            val fx = f?.x ?: 0.0
            val fy = f?.y ?: 0.0
            val l = Props()
            l["bend points"] = t.points.joinToString(" ") { "${num1(it.x - fx)},${num1(it.y - fy)}" }
            val lo = t.labelOffset
            l["label"] = if (lo != null) "${num1(lo.x)},${num1(lo.y)}" else ""
            colors(t.color, t.itemColors, l)
            val m = Props()
            m["kind"] = t.kind.key
            m["trigger"] = trigger
            m["guard"] = t.guard
            m["action"] = t.action
            m["precondition"] = t.precondition
            m["postcondition"] = t.postcondition
            m["target"] = target
            m["source"] = source
            result.set(CompareItem(key, t.id, "transition", label, m, l))
        }
        return result
    }

    /** The properties of two items that differ. */
    private fun differences(a: CompareItem, b: CompareItem): List<PropertyChange> {
        val output = ArrayList<PropertyChange>()
        for ((layout, x, y) in listOf(Triple(false, a.model, b.model), Triple(true, a.layout, b.layout))) {
            for (k in (x.keys + y.keys).distinct()) {
                if (k == "source") continue
                val xv = x[k] ?: ""
                val yv = y[k] ?: ""
                if (xv != yv) output.add(PropertyChange(k, xv, yv, layout))
            }
        }
        return output
    }

    private fun parts(key: String) = key.split('|')

    /** What changed from [older] to [newer], in the order of the newer version (removed ones come last). */
    fun compareModels(older: FsmModel, newer: FsmModel): List<Change> {
        val a = itemsOf(older)
        val b = itemsOf(newer)
        val pairs = HashMap<String, String>() // new key -> old key
        for (i in b.list) if (a.has(i.key)) pairs[i.key] = i.key
        // Transitions left over: the same source and trigger, else the same source, in order.
        val leftA = a.list.filter { it.what == "transition" && it.key !in pairs }
        val leftB = b.list.filter { it.what == "transition" && it.key !in pairs }
        val used = HashSet<String>()
        for (level in listOf(2, 1)) {
            for (t in leftB) {
                if (t.key in pairs) continue
                val p = parts(t.key)
                val o = leftA.firstOrNull { x -> x.key !in used && parts(x.key)[0] == p[0] && (level == 1 || parts(x.key)[1] == p[1]) }
                if (o != null) {
                    pairs[t.key] = o.key
                    used.add(o.key)
                }
            }
        }
        val matchedOld = pairs.values.toHashSet()
        val changes = ArrayList<Change>()
        for (item in b.list) {
            val old = pairs[item.key]
            if (old == null) {
                changes.add(Change(item.key, null, ChangeKind.Added, item.what, item.label, null, item.id))
                continue
            }
            val props = differences(a[old]!!, item)
            if (props.isNotEmpty()) changes.add(Change(item.key, if (old != item.key) old else null, ChangeKind.Changed, item.what, item.label, a[old]!!.id, item.id, props))
        }
        for (item in a.list) {
            if (item.key !in matchedOld) changes.add(Change(item.key, null, ChangeKind.Removed, item.what, item.label, item.id, null))
        }
        return changes
    }

    /** Whether a change shows when layout changes are hidden. */
    fun isModelChange(c: Change): Boolean = c.kind != ChangeKind.Changed || c.properties.any { !it.layout }

    /** A change's properties as lines: `guard: a() → b()`. */
    fun describeChange(c: Change, layout: Boolean): List<String> {
        fun value(s: String) = if (s.isEmpty()) "(none)" else if (s.length > 60) s.substring(0, 57) + "…" else s
        return c.properties.filter { layout || !it.layout }.map { "${it.name}: ${value(it.old)} → ${value(it.new)}" }
    }
}
