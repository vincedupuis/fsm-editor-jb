package com.vincegosoftware.fsmeditor.core

/** A text with conflict markers, split into its two sides. */
class ConflictSplit(
    /** The text with the current side of every conflict ("ours"). */
    val current: String,
    /** The text with the incoming side of every conflict ("theirs"). */
    val incoming: String,
    /** How many conflicts. */
    val conflicts: Int,
)

class ConflictMarkerException(message: String) : Exception(message)

/**
 * The diagram merge (three panes), without editor APIs: splitting a file with conflict markers into its two sides, and
 * taking one side's version of an element into the result. A port of the Visual Studio edition's `Merge.cs` (the VS
 * Code edition's `src/merge.ts`).
 */
object Merge {
    private val START = Regex("^<{7}(?: |\\r?$)", RegexOption.MULTILINE)
    private val END = Regex("^>{7}(?: |\\r?$)", RegexOption.MULTILINE)
    private val MARKER = Regex("^(<{7}|\\|{7}|={7}|>{7})(?: |\\r?\\n|\\r?$)")

    /** Whether a text holds Git's conflict markers. */
    fun hasConflictMarkers(text: String?): Boolean = text != null && START.containsMatchIn(text) && END.containsMatchIn(text)

    /**
     * Splits a text with conflict markers into its two sides: `<<<<<<<` current `=======` incoming `>>>>>>>`, and the
     * three-part style, whose base section (`|||||||`) is left out. Throws [ConflictMarkerException] when the markers
     * don't nest.
     */
    fun splitConflicts(text: String): ConflictSplit {
        val current = StringBuilder()
        val incoming = StringBuilder()
        var state = "both"
        var conflicts = 0
        var at = 0
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            val line = if (nl < 0) text.substring(start) else text.substring(start, nl + 1)
            start = if (nl < 0) text.length else nl + 1
            at++
            val marker = MARKER.find(line)?.groupValues?.get(1)
            if (marker == "<<<<<<<") {
                if (state != "both") throw ConflictMarkerException("Line $at: a conflict starts inside another one.")
                state = "current"
                conflicts++
            } else if (marker == "|||||||" && state == "current") {
                state = "base"
            } else if (marker == "=======" && (state == "current" || state == "base")) {
                state = "incoming"
            } else if (marker == ">>>>>>>") {
                if (state != "incoming") throw ConflictMarkerException("Line $at: a conflict ends where none is open.")
                state = "both"
            } else if (state == "both") {
                current.append(line)
                incoming.append(line)
            } else if (state == "current") current.append(line)
            else if (state == "incoming") incoming.append(line)
        }
        if (state != "both") throw ConflictMarkerException("The last conflict has no end marker (>>>>>>>).")
        return ConflictSplit(current.toString(), incoming.toString(), conflicts)
    }

    /** The owners in a model, as [adopt] needs them. */
    private class Owners(m: FsmModel) {
        val v = HashMap<String, Vertex>()
        val regionOwner = HashMap<String, Vertex?>()

        init {
            for (x in m.vertices) v[x.id] = x
            for (x in m.regions) regionOwner[x.id] = null
            for (x in m.vertices) for (r in x.regions) regionOwner[r.id] = x
        }

        fun get(id: String?): Vertex? = if (id == null) null else v[id]

        fun border(x: Vertex) =
            x.type == VertexType.ConnectionPointRef || ((x.type == VertexType.EntryPoint || x.type == VertexType.ExitPoint) && x.parent in v)

        fun owner(x: Vertex): Vertex? = if (border(x)) get(x.parent) else regionOwner[x.parent]

        fun inside(x: Vertex, s: Vertex): Boolean {
            val seen = HashSet<Vertex>()
            var o = owner(x)
            while (o != null && seen.add(o)) {
                if (o === s) return true
                o = owner(o)
            }
            return false
        }
    }

    /** A key that is no key (an element whose name is used twice has none): one value, as JavaScript's undefined. */
    private const val NO_KEY = "\u0000undefined"

    /**
     * The result with the element of [keys] (see [Compare]: its key in either version, for a transition whose target or
     * trigger changed) as [source] has it: updated, added (with what it contains), or removed (with what it contains and
     * the transitions to and from it). Positions are taken relative to the owner. The result's own ids stay; added
     * elements get new ones.
     */
    fun adopt(result: FsmModel, source: FsmModel, vararg keys: String): FsmModel {
        val key = keys[0]
        val r = result.copy()
        val ri = Compare.itemsOf(r)
        val si = Compare.itemsOf(source)
        val sv = HashMap<String, Vertex>()
        for (v in source.vertices) sv[v.id] = v
        val st = HashMap<String, Transition>()
        for (t in source.transitions) st[t.id] = t
        val taken = HashSet<String>()
        r.vertices.mapTo(taken) { it.id }
        r.transitions.mapTo(taken) { it.id }
        r.regions.mapTo(taken) { it.id }
        for (v in r.vertices) v.regions.mapTo(taken) { it.id }
        fun fresh(base: String): String {
            var id = "$base~m"
            var n = 2
            while (id in taken) id = "$base~m${n++}"
            taken.add(id)
            return id
        }
        // Keys of the source's elements, by id, and the result's ids, by key.
        val keyOfS = HashMap<String, String>()
        for (it in si.list) if (it.id.isNotEmpty()) keyOfS[it.id] = it.key
        val idOfR = HashMap<String, String>()
        for (it in ri.list) if (it.id.isNotEmpty()) idOfR[it.key] = it.id
        fun keyOf(id: String?): String? = if (id == null) null else keyOfS[id]
        fun idOf(k: String?): String? = idOfR[k ?: NO_KEY]

        val so = Owners(source)

        // The result's parent for a source vertex: the same region of the same owner, else the top-level region.
        fun parentIn(v: Vertex): String {
            val ro = Owners(r)
            if (so.border(v)) return idOf(keyOf(v.parent)) ?: ""
            val owner = so.owner(v)
            if (owner == null) {
                val index = source.regions.indexOfFirst { it.id == v.parent }
                return (if (index >= 0 && index < r.regions.size) r.regions[index] else r.regions[0]).id
            }
            val o = ro.get(idOf(keyOf(owner.id)) ?: "")
            if (o == null || o.regions.isEmpty()) return r.regions[0].id
            val i = owner.regions.indexOfFirst { it.id == v.parent }
            val wanted = if (i >= 0 && i < owner.regions.size) owner.regions[i].name else null
            val named = o.regions.firstOrNull { it.name.isNotEmpty() && it.name == wanted }
            return (named ?: if (i >= 0 && i < o.regions.size) o.regions[i] else o.regions[0]).id
        }

        // Copies a source vertex's place onto a result vertex, relative to its owner.
        fun place(target: Vertex, v: Vertex) {
            val ro = Owners(r)
            val sOwner = so.owner(v)
            val rOwner = ro.get(target.parent) ?: ro.regionOwner[target.parent]
            val a = if (sOwner != null) PointD(sOwner.x, sOwner.y) else PointD(0.0, 0.0)
            val b = if (rOwner != null && rOwner !== target) PointD(rOwner.x, rOwner.y) else PointD(0.0, 0.0)
            target.x = Num.r1(b.x + v.x - a.x)
            target.y = Num.r1(b.y + v.y - a.y)
        }
        fun copyVertex(target: Vertex, v: Vertex) {
            target.type = v.type
            target.name = v.name
            target.w = v.w
            target.h = v.h
            target.regionLayout = v.regionLayout
            target.entry = v.entry
            target.exit = v.exit
            target.doActivity = v.doActivity
            target.deferrable = v.deferrable.toMutableList()
            target.invariant = v.invariant
            target.submachine = v.submachine
            target.stereotype = v.stereotype
            target.ref = v.ref
            target.pointKind = v.pointKind
            target.text = v.text
            target.color = v.color
            target.itemColors = LinkedHashMap(v.itemColors)
            target.group = v.group
            // Regions: the same count and names, keeping the result's ids where they line up.
            target.regions = v.regions.mapIndexedTo(mutableListOf()) { i, x -> Region(if (i < target.regions.size) target.regions[i].id else fresh(x.id), x.name, x.size) }
            // An anchor to what the result doesn't have (yet) is kept as the reference the file writes, which names it once
            // it is taken too.
            target.anchors = v.anchors.mapTo(mutableListOf()) { a ->
                idOf(keyOf(a) ?: "") ?: (if (keyOf(a) != null) Format.unresolvedAnchorOf(source, a) else null) ?: a
            }
        }
        fun removeVertex(id: String) {
            val ro = Owners(r)
            val v = ro.get(id) ?: return
            val gone = (listOf(v) + r.vertices.filter { ro.inside(it, v) }).mapTo(HashSet()) { it.id }
            r.vertices = r.vertices.filterTo(mutableListOf()) { it.id !in gone }
            r.transitions = r.transitions.filterTo(mutableListOf()) { it.source !in gone && it.target !in gone }
            for (x in r.vertices) x.anchors = x.anchors.filterTo(mutableListOf()) { it !in gone }
        }
        fun addVertex(v: Vertex) {
            val n = Vertex(fresh(v.id), v.type, v.name, parentIn(v), 0.0, 0.0, v.w, v.h)
            copyVertex(n, v)
            r.vertices.add(n)
            idOfR[keyOf(v.id) ?: NO_KEY] = n.id
            place(n, v)
            // What it contains comes along.
            for (x in source.vertices) {
                if (x === v || !so.inside(x, v)) continue
                if ((keyOf(x.id) ?: NO_KEY) !in idOfR) addVertex(x)
            }
        }
        // The target in the result; one the result doesn't have (yet) is the reference the file writes, naming nothing.
        fun targetIn(t: Transition): String {
            val k = keyOf(t.target)
            val reference = if (t.target in sv) Format.referenceOf(source, t.target) else null
            return (if (k != null) idOf(k) else null) ?: if (reference != null) Format.UNRESOLVED + reference else t.target
        }
        fun copyTransition(target: Transition, t: Transition) {
            target.kind = t.kind
            target.triggers = t.triggers.toMutableList()
            target.guard = t.guard
            target.action = t.action
            target.precondition = t.precondition
            target.postcondition = t.postcondition
            target.color = t.color
            target.itemColors = LinkedHashMap(t.itemColors)
            target.labelOffset = t.labelOffset
            target.target = if (t.kind == TransitionKind.Internal) target.source else targetIn(t)
            // Bend points, moved with the difference between the two sources' places.
            val s1 = sv[t.source]
            val s2 = r.vertices.firstOrNull { it.id == target.source }
            target.points = if (s1 != null && s2 != null) t.points.mapTo(mutableListOf()) { PointD(Num.r1(it.x - s1.x + s2.x), Num.r1(it.y - s1.y + s2.y)) } else mutableListOf()
        }

        if (key == "machine") {
            r.name = source.name
            r.kind = source.kind
            r.documentation = source.documentation
            return r.normalize()
        }
        val s = keys.firstNotNullOfOrNull { si[it] }
        val mine = keys.firstNotNullOfOrNull { ri[it] }
        if (s?.what == "transition" || mine?.what == "transition") {
            if (s == null) r.transitions = r.transitions.filterTo(mutableListOf()) { it.id != mine!!.id }
            else {
                val t = st.getValue(s.id)
                val existing = if (mine != null) r.transitions.firstOrNull { it.id == mine.id } else null
                if (existing != null) copyTransition(existing, t)
                else {
                    val source2 = idOf(keyOf(t.source) ?: "")
                    if (source2 != null) {
                        val n = Transition(fresh(t.id), source2, "", t.kind)
                        copyTransition(n, t)
                        r.transitions.add(n)
                    }
                }
            }
            return r.normalize()
        }
        if (s == null) {
            if (mine != null) removeVertex(mine.id)
            return r.normalize()
        }
        val vs = sv.getValue(s.id)
        val ex = if (mine != null) r.vertices.firstOrNull { it.id == mine.id } else null
        if (ex != null) {
            val parent = parentIn(vs)
            if (parent.isNotEmpty()) ex.parent = parent
            val x0 = ex.x
            val y0 = ex.y
            copyVertex(ex, vs)
            place(ex, vs)
            // What it contains moves with it, bend points between them included.
            val dx = ex.x - x0
            val dy = ex.y - y0
            if (dx != 0.0 || dy != 0.0) {
                val ro = Owners(r)
                val inner = r.vertices.filter { ro.inside(it, ex) }.mapTo(HashSet()) { it.id }
                for (x in r.vertices) {
                    if (x.id !in inner) continue
                    x.x += dx
                    x.y += dy
                }
                inner.add(ex.id)
                for (t in r.transitions) {
                    if (t.points.isNotEmpty() && t.source in inner && t.target in inner) t.points = t.points.mapTo(mutableListOf()) { PointD(it.x + dx, it.y + dy) }
                }
            }
        } else addVertex(vs)
        return r.normalize()
    }
}
