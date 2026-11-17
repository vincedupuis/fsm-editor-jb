package com.vincegosoftware.fsmeditor.core

/** A problem of a file's text or shape, with its line. */
class Problem(val line: Int, val message: String) {
    override fun toString() = "line $line: $message"
}

/** A machine read from its model file (and its layout file). */
class ReadMachine(
    /** The model, or null when the model file can't be read (see [problems]). */
    val model: FsmModel? = null,
    /** Line of each element, region, point and transition, by id. */
    val lines: MutableMap<String, Int> = HashMap(),
    /** Why the model file can't be read (not JSON, or a wrong shape), or null. */
    val problems: List<Problem>? = null,
) {
    /** Why the layout file couldn't be used (it is then left out entirely), or null. */
    var layoutProblem: String? = null

    /** How many elements had no layout entry and were placed automatically. */
    var placed: Int = 0

    val failed: Boolean get() = problems != null
}

/** What writing a machine gives. */
class Written(
    /** The model file. */
    val model: String,
    /** The layout file. */
    val layout: String,
    /** Line of each vertex and transition in the model file, by id. */
    val lines: Map<String, Int>,
    /** What the format can't hold, which was left out (an editor's model holds none of it). */
    val lost: List<String>,
)

/** A name changed by [Format.assignNames]. */
class Rename(val what: String, val from: String, val to: String, val why: String? = null)

/**
 * The file format: a machine is two JSON files, `<Name>.fsm` (the model, without ids: elements are identified by their
 * names) and `<Name>.fsm.layout` beside it (the drawing). Described in fsm-cli's README ("The .fsm file format") and by
 * its JSON Schemas. A port of the Visual Studio edition's `Format.cs` (the VS Code edition's `src/format.ts`).
 *
 * Reading the model is fsm-cli's reader (`src/format.rs`), problem for problem: the same messages at the same lines,
 * and the same ids, derived from the names (an element: its name; a point: `<owner>.<point>`; `<owner>.initial`,
 * `.history`, `.deepHistory`; the only region of a state or of the machine: `<owner>.`; a transition: `<source>#<n>`;
 * an id already taken gets `~2`, `~3`...). The editor works on the flat [FsmModel] with absolute coordinates; the
 * layout file holds them relative to their owner ([applyLayout], [writeMachine]).
 *
 * A reference that names nothing is kept as it is: a transition's target is then [UNRESOLVED] followed by the
 * reference, a comment's anchor [UNRESOLVED] (a reference) or [UNRESOLVED_ANCHOR] (a transition, as JSON) followed by it.
 */
object Format {
    const val UNRESOLVED = ""
    const val UNRESOLVED_ANCHOR = ""

    /** The reference of a target that names nothing ("" for an `initial` without `target`), or null. */
    fun unresolvedOf(target: String?): String? = if (target != null && target.startsWith(UNRESOLVED)) target.substring(1) else null

    /** Whether a text is a file in the old format (XMI). */
    fun isOldFormat(text: String?): Boolean {
        for (c in text ?: "") {
            if (c == '<') return true
            if (c != '﻿' && c != ' ' && c != '\t' && c != '\r' && c != '\n') return false
        }
        return false
    }

    internal fun n1(n: Double): Double = Num.r1(n)

    /** The size of each kind of vertex when the layout has none (the editor's defaults). */
    fun defaultSize(t: VertexType): Pair<Double, Double> = t.defaultSize

    /** Kinds the editor lets the user resize: the layout holds their width and height. */
    fun isResizable(t: VertexType) = t == VertexType.State || t == VertexType.Comment || t == VertexType.Fork || t == VertexType.Join

    /** Item colors: their keys in the layout file, by the model's. */
    private val ITEM_COLOR_FILE_KEY = linkedMapOf(
        "background" to "background", "entry" to "entry", "exit" to "exit", "doActivity" to "do", "deferrable" to "defer", "invariant" to "invariant",
        "triggers" to "trigger", "guard" to "guard", "action" to "action", "precondition" to "precondition", "postcondition" to "postcondition",
    )
    private val ITEM_COLOR_FROM_FILE = ITEM_COLOR_FILE_KEY.entries.associate { it.value to it.key }

    /** Element types of the file. */
    internal val ELEMENT_TYPES = listOf("state", "choice", "junction", "fork", "join", "final", "terminate", "comment")
    private val REGION_FIELDS = listOf("initial", "history", "deepHistory", "elements")

    internal fun isElementKind(t: VertexType) =
        t == VertexType.State || t == VertexType.Choice || t == VertexType.Junction || t == VertexType.Fork || t == VertexType.Join ||
            t == VertexType.Final || t == VertexType.Terminate || t == VertexType.Comment

    private val PSEUDO = listOf("initial" to VertexType.Initial, "history" to VertexType.ShallowHistory, "deepHistory" to VertexType.DeepHistory)

    // ---------------------------------------------------------------- shape of the model file (format.rs's Shape)

    private fun oneOf(values: List<String>): String {
        val q = values.map { "\"$it\"" }
        return if (q.size == 1) q[0] else "${q.dropLast(1).joinToString(", ")} or ${q.last()}"
    }

    private class Shape {
        val problems = ArrayList<Problem>()

        private fun add(line: Int, message: String) {
            problems.add(Problem(line, message))
        }

        private fun kind(f: JsonField, kind: String): Boolean {
            val ok = when (kind) {
                "text" -> f.value.kind == JsonKind.String
                "object" -> f.value.kind == JsonKind.Object
                else -> f.value.kind == JsonKind.Array
            }
            if (!ok) add(f.value.line, "Field '${f.key}' must be ${if (kind == "text") "a text" else if (kind == "object") "an object" else "a list"}.")
            return ok
        }

        private fun obj(node: JsonNode, what: String, allowed: Collection<String>, required: Collection<String>, check: (JsonField) -> Unit) {
            val fields = node.fields ?: emptyList()
            for (f in fields) {
                if (f.key in allowed) check(f) else add(f.line, "Unknown field '${f.key}' in $what.")
            }
            for (r in required) if (fields.none { it.key == r }) add(node.line, "Missing field '$r' in $what.")
        }

        private fun list(f: JsonField, item: (JsonNode) -> Unit) {
            if (!kind(f, "list")) return
            for (x in f.value.items!!) {
                if (x.kind != JsonKind.Object) add(x.line, "Items of '${f.key}' must be objects.") else item(x)
            }
        }

        private fun enumeration(f: JsonField, values: List<String>): Boolean {
            if (!kind(f, "text")) return false
            if (f.value.str in values) return true
            add(f.value.line, "Field '${f.key}' must be ${oneOf(values)}.")
            return false
        }

        fun machine(node: JsonNode) {
            val fields = node.fields
            if (fields == null) {
                add(node.line, "The file must hold a JSON object.")
                return
            }
            val allowed = listOf("\$schema", "format", "name", "type", "documentation", "points", "regions") + REGION_FIELDS
            obj(node, "the machine", allowed, listOf("format", "name", "type")) { f ->
                when (f.key) {
                    "format" -> {
                        val n = f.value.number
                        if (n != 1.0) {
                            if (n != null && n > 1 && Math.floor(n) == n) add(f.value.line, "This file is in format ${Num.format(n)}: this version reads format 1 only.")
                            else add(f.value.line, "Field 'format' must be 1.")
                        }
                    }
                    "type" -> enumeration(f, listOf("behavioral", "protocol"))
                    "points" -> list(f) { point(it) }
                    "\$schema", "name", "documentation" -> kind(f, "text")
                    else -> regionField(f)
                }
            }
            regionsAlone(fields)
        }

        private fun regionsAlone(fields: List<JsonField>) {
            if (fields.none { it.key == "regions" }) return
            for (f in fields) {
                if (f.key in REGION_FIELDS) add(f.line, "Field '${f.key}' cannot be used with 'regions': each region has its own.")
            }
        }

        private fun regionField(f: JsonField) {
            if (f.key == "initial" || f.key == "history" || f.key == "deepHistory") {
                if (kind(f, "object")) obj(f.value, "'${f.key}'", listOf("target", "action"), emptyList()) { g -> kind(g, "text") }
                return
            }
            if (f.key == "elements") {
                list(f) { element(it) }
                return
            }
            if (!kind(f, "list")) return
            val items = f.value.items!!
            if (items.size < 2) {
                add(f.value.line, "Field 'regions' needs two regions or more: the fields of a single region go directly in its owner.")
            }
            for (x in items) {
                if (x.kind != JsonKind.Object) {
                    add(x.line, "Items of 'regions' must be objects.")
                    continue
                }
                obj(x, "a region", listOf("name") + REGION_FIELDS, listOf("name")) { g ->
                    if (g.key == "name") kind(g, "text") else regionField(g)
                }
            }
        }

        private fun element(node: JsonNode) {
            val ty = node.field("type")?.value?.str
            if (ty == null || ty !in ELEMENT_TYPES) {
                for (f in node.fields ?: emptyList()) {
                    if (f.key == "name") kind(f, "text")
                    else if (f.key == "type") enumeration(f, ELEMENT_TYPES)
                }
                for (r in listOf("name", "type")) if (node.field(r) == null) add(node.line, "Missing field '$r' in an element.")
                return
            }
            val (what, extra) = when (ty) {
                "state" -> "a state" to listOf("stereotype", "submachine", "entry", "do", "exit", "defer", "invariant", "transitions", "points", "initial", "history", "deepHistory", "elements", "regions")
                "choice", "junction", "fork", "join" -> "a $ty" to listOf("transitions")
                "final" -> "a final state" to emptyList()
                "terminate" -> "a terminate pseudostate" to emptyList()
                else -> "a comment" to listOf("text", "anchors")
            }
            obj(node, what, listOf("name", "type") + extra, listOf("name", "type")) { f ->
                when (f.key) {
                    "type" -> {}
                    "transitions" -> list(f) { transition(it) }
                    "points" -> list(f) { point(it) }
                    "anchors" -> if (kind(f, "list")) {
                        for (x in f.value.items!!) {
                            if (x.kind == JsonKind.String) continue
                            if (x.kind == JsonKind.Object) obj(x, "a comment anchor", listOf("source", "trigger", "target"), listOf("source")) { g -> kind(g, "text") }
                            else add(x.line, "Items of 'anchors' must be texts or objects.")
                        }
                    }
                    "initial", "history", "deepHistory", "elements", "regions" -> regionField(f)
                    else -> kind(f, "text")
                }
            }
            if (ty == "state") regionsAlone(node.fields!!)
        }

        private fun point(node: JsonNode) {
            obj(node, "a point", listOf("name", "type", "transitions"), listOf("name", "type")) { f ->
                when (f.key) {
                    "type" -> enumeration(f, listOf("entryPoint", "exitPoint"))
                    "transitions" -> list(f) { transition(it) }
                    else -> kind(f, "text")
                }
            }
        }

        private fun transition(node: JsonNode) {
            obj(node, "a transition", listOf("type", "trigger", "guard", "action", "precondition", "postcondition", "target"), listOf("type")) { f ->
                if (f.key == "type") enumeration(f, listOf("external", "internal", "local")) else kind(f, "text")
            }
            val ty = node.field("type")?.value?.str
            val target = node.field("target")
            if (ty == "internal" && target != null) add(target.line, "An internal transition has no 'target'.")
            else if ((ty == "external" || ty == "local") && target == null) add(node.line, "Missing field 'target' in a transition.")
        }
    }

    /** The problems of a model document's shape (none when it is a machine file). */
    fun shapeProblems(node: JsonNode): List<Problem> = Shape().also { it.machine(node) }.problems

    // ---------------------------------------------------------------- reading the model (format.rs's Builder)

    private fun text(node: JsonNode, key: String): String = node.field(key)?.value?.str ?: ""

    private fun itemsOf(node: JsonNode, key: String): List<JsonNode> = node.field(key)?.value?.items ?: emptyList()

    /** Triggers or deferred events written `a, b, c`. */
    fun splitEvents(s: String?): MutableList<String> = (s ?: "").split(',').map { JsWhitespace.trim(it) }.filterTo(mutableListOf()) { it.isNotEmpty() }

    private fun newVertex(id: String, type: VertexType, name: String, parent: String) =
        Vertex(id, type, name, parent, Double.NaN, Double.NaN, 0.0, 0.0)

    private class Pending(val source: String, val node: JsonNode, val isDefault: Boolean)

    private class Builder(val model: FsmModel) {
        val lines = HashMap<String, Int>()
        private val taken = HashSet<String>()
        private val byName = HashMap<String, String>()
        private val pseudo = HashMap<String, String>()
        private val points = HashMap<String, String>()
        val pending = ArrayList<Pending>()

        /** Comments and their anchors' nodes, resolved once every transition is known. */
        private val anchors = ArrayList<Pair<Vertex, List<JsonNode>>>()

        fun alloc(base: String): String {
            var id = base
            var n = 2
            while (id in taken) id = "$base~${n++}"
            taken.add(id)
            return id
        }

        fun named(name: String): String {
            val id = alloc(name.ifEmpty { "~" })
            if (name !in byName) byName[name] = id
            return id
        }

        fun region(node: JsonNode, id: String, owner: String) {
            for ((key, type) in PSEUDO) {
                val f = node.field(key) ?: continue
                val vid = alloc("$owner.$key")
                if (key != "initial" && "$owner.$key" !in pseudo) pseudo["$owner.$key"] = vid
                lines[vid] = f.line
                model.vertices.add(newVertex(vid, type, "", id))
                val x = f.value
                // The default transition, when there is one: a target or an action (an action without a target is reported).
                if (x.field("target") != null || x.field("action") != null) pending.add(Pending(vid, x, true))
            }
            for (e in itemsOf(node, "elements")) element(e, id)
        }

        private fun element(e: JsonNode, parent: String) {
            val name = text(e, "name")
            val id = named(name)
            lines[id] = e.line
            val parsed = VertexType.parseKey(text(e, "type"))
            val type = if (parsed != null && isElementKind(parsed)) parsed else VertexType.Comment
            val v = newVertex(id, type, name, parent)
            if (v.type == VertexType.Comment) {
                v.text = text(e, "text")
                v.anchors = mutableListOf()
                anchors.add(v to itemsOf(e, "anchors"))
            }
            if (v.type != VertexType.State) {
                model.vertices.add(v)
                for (t in itemsOf(e, "transitions")) pending.add(Pending(id, t, false))
                return
            }
            v.stereotype = text(e, "stereotype")
            v.entry = text(e, "entry")
            v.exit = text(e, "exit")
            v.doActivity = text(e, "do")
            v.invariant = text(e, "invariant")
            v.deferrable = splitEvents(text(e, "defer"))
            v.submachine = e.field("submachine")?.value?.str ?: ""
            model.vertices.add(v)
            for (t in itemsOf(e, "transitions")) pending.add(Pending(id, t, false))
            pointsOf(e, id, name, id, true)
            val sources = ArrayList<Triple<Region, JsonNode, String>>()
            val regs = e.field("regions")
            if (regs != null) {
                for (r in regs.value.items ?: emptyList()) {
                    val rname = text(r, "name")
                    val rid = named(rname)
                    lines[rid] = r.line
                    sources.add(Triple(Region(rid, rname), r, rname))
                }
            } else if (REGION_FIELDS.any { e.field(it) != null }) {
                val rid = alloc("$name.")
                lines[rid] = e.line
                sources.add(Triple(Region(rid, ""), e, name))
            }
            for ((r, node, owner) in sources) {
                v.regions.add(r)
                region(node, r.id, owner)
            }
        }

        fun pointsOf(node: JsonNode, ownerId: String, ownerName: String, parent: String, state: Boolean) {
            val refs = state && node.field("submachine") != null
            for (p in itemsOf(node, "points")) {
                val name = text(p, "name")
                val entry = text(p, "type") == "entryPoint"
                val key = "$ownerName.$name"
                val id = alloc(key)
                if (key !in points) points[key] = id
                lines[id] = p.line
                val v = if (refs) {
                    newVertex(id, VertexType.ConnectionPointRef, "", ownerId).also {
                        it.ref = name
                        it.pointKind = if (entry) PointKind.Entry else PointKind.Exit
                    }
                } else newVertex(id, if (entry) VertexType.EntryPoint else VertexType.ExitPoint, name, parent)
                model.vertices.add(v)
                for (t in itemsOf(p, "transitions")) pending.add(Pending(id, t, false))
            }
        }

        fun resolve(reference: String): String {
            val dot = reference.indexOf('.')
            val found = if (dot < 0) byName[reference]
            else {
                val rest = reference.substring(dot + 1)
                if (rest == "history" || rest == "deepHistory") pseudo[reference] else points[reference]
            }
            return found ?: UNRESOLVED + reference
        }

        /**
         * A comment's anchors, as ids: a reference names an element, an object a transition (under its source, by its
         * trigger and target, then by order among identical ones). One that names nothing is kept.
         */
        fun resolveAnchors(vertexIds: Set<String>) {
            for ((v, nodes) in anchors) {
                val used = HashSet<Transition>()
                v.anchors = nodes.mapTo(mutableListOf()) { a ->
                    if (a.kind == JsonKind.String) {
                        val id = resolve(a.str!!)
                        return@mapTo if (id in vertexIds) id else UNRESOLVED + a.str
                    }
                    val source = resolve(text(a, "source"))
                    val trigger = splitEvents(text(a, "trigger")).joinToString(", ")
                    val reference = a.field("target")?.value?.str
                    val target = reference?.let { resolve(it) }
                    val t = model.transitions.firstOrNull { x ->
                        x !in used && x.source == source && x.triggers.joinToString(", ") == trigger &&
                            (if (target == null) x.kind == TransitionKind.Internal else x.kind != TransitionKind.Internal && x.target == target)
                    }
                    if (t == null) return@mapTo UNRESOLVED_ANCHOR + Json.stringify(Json.plain(a))
                    used.add(t)
                    t.id
                }
            }
        }
    }

    /** Reads a model file. Its [ReadMachine.problems] say why when it can't be read (not JSON, or a wrong shape). */
    fun readModel(text: String): ReadMachine {
        val root = try {
            Json.parse(text)
        } catch (e: JsonSyntaxException) {
            return ReadMachine(problems = listOf(Problem(e.line, e.message!!)))
        }
        val problems = shapeProblems(root)
        if (problems.isNotEmpty()) return ReadMachine(problems = problems)
        val name = text(root, "name")
        val model = FsmModel()
        model.name = name
        model.kind = if (text(root, "type") == "protocol") MachineKind.Protocol else MachineKind.Behavioral
        model.documentation = text(root, "documentation")
        val b = Builder(model)
        val regionSources = ArrayList<Triple<JsonNode, String, String>>()
        val regs = root.field("regions")
        if (regs != null) {
            for (r in regs.value.items!!) {
                val rname = text(r, "name")
                val rid = b.named(rname)
                b.lines[rid] = r.line
                model.regions.add(Region(rid, rname))
                regionSources.add(Triple(r, rid, rname))
            }
        } else {
            val rid = b.alloc("$name.")
            model.regions.add(Region(rid, ""))
            regionSources.add(Triple(root, rid, name))
        }
        for ((node, id, owner) in regionSources) b.region(node, id, owner)
        b.pointsOf(root, "", name, model.regions[0].id, false)

        val count = HashMap<String, Int>()
        for (p in b.pending) {
            val n = (count[p.source] ?: 0) + 1
            count[p.source] = n
            val id = b.alloc("${p.source}#$n")
            b.lines[id] = p.node.line
            val ty = text(p.node, "type")
            val kind = if (ty == "internal") TransitionKind.Internal else if (ty == "local") TransitionKind.Local else TransitionKind.External
            val reference = p.node.field("target")?.value?.str
            val target = reference?.let { b.resolve(it) } ?: if (kind == TransitionKind.Internal && !p.isDefault) p.source else UNRESOLVED
            val t = Transition(id, p.source, target, kind)
            t.triggers = splitEvents(text(p.node, "trigger"))
            t.guard = text(p.node, "guard")
            t.action = text(p.node, "action")
            t.precondition = text(p.node, "precondition")
            t.postcondition = text(p.node, "postcondition")
            model.transitions.add(t)
        }
        b.resolveAnchors(model.vertices.mapTo(HashSet()) { it.id })
        return ReadMachine(model, b.lines)
    }

    // ---------------------------------------------------------------- references

    /** The value [Index.anchor] gives an anchor the file can't hold. */
    private val NOT_HELD = Any()

    /** What the writer needs to know about the model: owners, names and references. */
    private class Index(val model: FsmModel) {
        val v = HashMap<String, Vertex>()
        val t = HashMap<String, Transition>()
        val regionOwner = HashMap<String, Vertex?>()
        private val regions = HashMap<String, Region>()
        private val top: Set<String> = model.regions.mapTo(HashSet()) { it.id }

        init {
            for (r in model.regions) {
                regionOwner[r.id] = null
                regions[r.id] = r
            }
            for (x in model.vertices) {
                if (x.id !in v) v[x.id] = x
                for (r in x.regions) {
                    regionOwner[r.id] = x
                    regions[r.id] = r
                }
            }
            for (x in model.transitions) if (x.id !in t) t[x.id] = x
        }

        fun get(id: String?): Vertex? = if (id == null) null else v[id]

        fun isBorder(x: Vertex) =
            x.type == VertexType.ConnectionPointRef || ((x.type == VertexType.EntryPoint || x.type == VertexType.ExitPoint) && x.parent in v)

        fun isMachinePoint(x: Vertex) = (x.type == VertexType.EntryPoint || x.type == VertexType.ExitPoint) && x.parent in top

        /** The owner of a region's history pseudostates in references: its state, the region when there are several, the machine. */
        private fun regionRefOwner(regionId: String): String {
            val owner = regionOwner[regionId]
            val r = regions[regionId]
            if (owner != null) return if (owner.regions.size > 1) r?.name ?: "" else owner.name
            return if (model.regions.size > 1) r?.name ?: "" else model.name
        }

        /** The point name of a border vertex or machine point. */
        fun pointName(x: Vertex): String = if (x.type == VertexType.ConnectionPointRef) x.ref else x.name

        /** The reference to a vertex, or null when it can't be referenced (an initial pseudostate). */
        fun ref(id: String): String? {
            unresolvedOf(id)?.let { return it }
            val x = get(id) ?: return id
            if (x.type == VertexType.Initial) return null
            if (x.type == VertexType.ShallowHistory || x.type == VertexType.DeepHistory) {
                return "${regionRefOwner(x.parent)}.${if (x.type == VertexType.ShallowHistory) "history" else "deepHistory"}"
            }
            if (isBorder(x)) return "${get(x.parent)?.name ?: ""}.${pointName(x)}"
            if (isMachinePoint(x)) return "${model.name}.${x.name}"
            return x.name
        }

        fun owner(x: Vertex): Vertex? = if (isBorder(x)) get(x.parent) else regionOwner[x.parent]

        /** The innermost state that contains both ends of [tr] (or is one of them), or null: its bend points are relative to it. */
        fun frame(tr: Transition): Vertex? {
            fun chain(id: String): List<Vertex> {
                val output = ArrayList<Vertex>()
                var x = get(id)
                val seen = HashSet<Vertex>()
                if (x != null && x.type != VertexType.State) x = owner(x)
                while (x != null && seen.add(x)) {
                    output.add(x)
                    x = owner(x)
                }
                return output
            }
            val s = chain(tr.source)
            val g = chain(tr.target).toHashSet()
            return s.firstOrNull { it in g }
        }

        /**
         * A comment's anchor as the file writes it: a reference (String), or for a transition `{ source, trigger, target }`
         * ([OutObject]); one that named nothing as it was read. [NOT_HELD] when the file can't hold it (an initial
         * pseudostate, a transition from one), null when it names nothing in the model.
         */
        fun anchor(a: String): Any? {
            if (a.startsWith(UNRESOLVED)) return a.substring(1)
            if (a.startsWith(UNRESOLVED_ANCHOR)) return Json.parseObject(a.substring(1))
            if (a in v) return ref(a) ?: NOT_HELD
            val tr = t[a] ?: return null
            val source = ref(tr.source)
            if (source == null || tr.source !in v) return NOT_HELD
            val ao = OutObject()
            ao["source"] = source
            if (tr.triggers.isNotEmpty()) ao["trigger"] = tr.triggers.joinToString(", ")
            if (tr.kind != TransitionKind.Internal) {
                val target = ref(tr.target)
                if (target != null) ao["target"] = target
            }
            return ao
        }
    }

    /**
     * How [model] refers to its element or transition [id] in a comment's anchor, kept as a reference that names
     * nothing ([UNRESOLVED] or [UNRESOLVED_ANCHOR] followed by it): for a model where it doesn't exist (yet). Null when
     * it can't be referred to.
     */
    fun unresolvedAnchorOf(model: FsmModel, id: String): String? {
        val a = Index(model).anchor(id)
        if (a == null || a === NOT_HELD) return null
        return if (a is String) UNRESOLVED + a else UNRESOLVED_ANCHOR + Json.stringify(a)
    }

    /** How [model] refers to its vertex [id] (a transition's target), or null when it can't be referred to. */
    fun referenceOf(model: FsmModel, id: String): String? = Index(model).ref(id)

    // ---------------------------------------------------------------- writing

    private fun label(t: VertexType): String = if (t == VertexType.Terminate) "terminate pseudostate" else t.label

    private fun finite(n: Double): Boolean = n1(n).let { !it.isNaN() && !it.isInfinite() }

    private fun xy(x: Double, y: Double) = OutObject().also {
        it["x"] = n1(x)
        it["y"] = n1(y)
    }

    private class WrittenTransition(val o: OutObject, val layout: OutObject?, val key: OutObject)

    /** Writes a machine: its model and layout files. Names must have been given ([assignNames]). */
    fun writeMachine(model: FsmModel): Written {
        val ix = Index(model)
        val lost = ArrayList<String>()
        val done = HashSet<Vertex>()
        val doneT = HashSet<Transition>()
        val ids = java.util.IdentityHashMap<OutObject, String>()
        val tids = java.util.IdentityHashMap<OutObject, String>()
        fun describe(v: Vertex) = if (v.name.isNotEmpty()) "${label(v.type)} '${v.name}'" else "an unnamed ${label(v.type)}"
        val outgoing = LinkedHashMap<String, MutableList<Transition>>()
        for (t in model.transitions) outgoing.getOrPut(t.source) { ArrayList() }.add(t)
        fun outOf(id: String): List<Transition> = outgoing[id] ?: emptyList()
        fun describeT(t: Transition): String {
            val s = ix.get(t.source)
            return "the transition from ${if (s != null) describe(s) else "'${t.source}'"}${if (t.triggers.isNotEmpty()) " on ${t.triggers.joinToString(", ")}" else ""}"
        }

        // A vertex's place relative to `at` (a place that isn't a number, from an old file, is the origin's).
        fun position(v: Vertex, at: PointD): OutObject {
            val dx = v.x - at.x
            val dy = v.y - at.y
            if (finite(dx) && finite(dy)) return xy(dx, dy)
            lost.add("the place of ${describe(v)}, which is not a number")
            return xy(if (finite(dx)) dx else 0.0, if (finite(dy)) dy else 0.0)
        }
        fun colors(color: String, itemColors: Map<String, String>, into: OutObject) {
            if (ElementColors.isColor(color)) into["color"] = color
            val items = OutObject()
            for (k in ElementColors.ITEM_KEYS) {
                val c = itemColors[k]
                if (ElementColors.isColor(c)) items[ITEM_COLOR_FILE_KEY.getValue(k)] = c
            }
            if (items.count > 0) into["itemColors"] = items
        }
        // Where the places inside `v` are measured from (its own place, when it is a number: else written as the origin's).
        fun origin(v: Vertex?) = PointD(if (v != null && finite(v.x)) v.x else 0.0, if (v != null && finite(v.y)) v.y else 0.0)

        fun transitionLayout(t: Transition, trigger: String?, target: String?): OutObject? {
            val l = OutObject()
            if (!trigger.isNullOrEmpty()) l["trigger"] = trigger
            if (target != null) l["target"] = target
            val keys = l.count
            if (t.points.isNotEmpty() && t.kind != TransitionKind.Internal) {
                val f = origin(ix.frame(t))
                val bends = t.points.filter { finite(it.x - f.x) && finite(it.y - f.y) }
                if (bends.size < t.points.size) lost.add("bend points of ${describeT(t)} that are not numbers")
                if (bends.isNotEmpty()) l["bends"] = bends.map { xy(it.x - f.x, it.y - f.y) }
            }
            val lo = t.labelOffset
            if (lo != null && t.kind != TransitionKind.Internal) {
                if (finite(lo.x) && finite(lo.y)) l["label"] = xy(lo.x, lo.y)
                else lost.add("the label position of ${describeT(t)}, which is not a number")
            }
            colors(t.color, t.itemColors, l)
            return if (l.count > keys) l else null
        }
        // A transition's fields in the model file, its layout entry (null when it has nothing to store) and that entry
        // without what it stores; null when the file can't hold it (a transition to an initial pseudostate).
        fun transitionOf(t: Transition): WrittenTransition? {
            doneT.add(t)
            val o = OutObject()
            o["type"] = t.kind.key
            if (t.triggers.isNotEmpty()) o["trigger"] = t.triggers.joinToString(", ")
            if (t.guard.isNotEmpty()) o["guard"] = t.guard
            if (t.action.isNotEmpty()) o["action"] = t.action
            if (t.precondition.isNotEmpty()) o["precondition"] = t.precondition
            if (t.postcondition.isNotEmpty()) o["postcondition"] = t.postcondition
            var target: String? = null
            if (t.kind == TransitionKind.Internal) {
                if (t.target != t.source) lost.add("the target of the internal ${describeT(t).substring(4)}")
            } else {
                target = ix.ref(t.target)
                if (target == null) {
                    lost.add("${describeT(t)} to an initial pseudostate")
                    return null
                }
                o["target"] = target
            }
            tids[o] = t.id
            val key = OutObject()
            if (o["trigger"] != null) key["trigger"] = o["trigger"]
            if (target != null) key["target"] = target
            return WrittenTransition(o, transitionLayout(t, o["trigger"] as String?, target), key)
        }
        // The transitions leaving `v`, into the model object and layout entry. Layout entries are found by trigger and
        // target, then by order among identical ones: a transition with nothing to store gets an entry anyway (its
        // trigger and target) when a later one with the same trigger and target has one, so that each keeps its own.
        fun transitionsOf(v: Vertex, o: OutObject, l: OutObject) {
            val list = outOf(v.id)
            if (list.isEmpty()) return
            val mo = ArrayList<Any>()
            val written = ArrayList<Triple<OutObject?, OutObject, String>>()
            for (t in list) {
                val w = transitionOf(t) ?: continue
                mo.add(w.o)
                written.add(Triple(w.layout, w.key, Json.stringify(w.key)))
            }
            val lo = ArrayList<Any>()
            for ((i, w) in written.withIndex()) {
                val (entry, key, k) = w
                if (entry != null) lo.add(entry)
                else if (written.drop(i + 1).any { it.first != null && it.third == k }) lo.add(key)
            }
            if (mo.isNotEmpty()) o["transitions"] = mo
            if (lo.isNotEmpty()) l["transitions"] = lo
        }

        fun pointsOf(owner: Vertex?, points: List<Vertex>, o: OutObject, l: OutObject) {
            if (points.isEmpty()) return
            val mo = ArrayList<Any>()
            val lo = ArrayList<Any>()
            val at = origin(owner)
            for (p in points) {
                done.add(p)
                // On a submachine state, the points are references to the submachine's; on another state, its own.
                if (owner != null && (p.type == VertexType.ConnectionPointRef) != owner.submachine.isNotEmpty()) {
                    lost.add("${describe(p)} on ${describe(owner)}, which ${if (owner.submachine.isNotEmpty()) "is" else "is not"} a submachine state")
                    for (t in outOf(p.id)) doneT.add(t)
                    continue
                }
                val kind = if (p.type == VertexType.ConnectionPointRef) (if (p.pointKind == PointKind.Exit) "exitPoint" else "entryPoint") else p.type.key
                val po = OutObject()
                po["name"] = ix.pointName(p)
                po["type"] = kind
                val pl = OutObject()
                pl["name"] = ix.pointName(p)
                pl.assign(position(p, at))
                colors(p.color, p.itemColors, pl)
                if (p.group.isNotEmpty() && owner == null) pl["group"] = p.group
                transitionsOf(p, po, pl)
                ids[po] = p.id
                mo.add(po)
                lo.add(pl)
            }
            if (mo.isNotEmpty()) o["points"] = mo
            if (lo.isNotEmpty()) l["points"] = lo
        }

        lateinit var regionsOf: (Vertex?, List<Region>, OutObject, OutObject) -> Unit

        fun element(v: Vertex, at: PointD): Pair<OutObject, OutObject> {
            done.add(v)
            val o = OutObject()
            o["name"] = v.name
            o["type"] = v.type.key
            val l = OutObject()
            l["name"] = v.name
            l.assign(position(v, at))
            if (isResizable(v.type)) {
                val (dw, dh) = defaultSize(v.type)
                l["width"] = n1(if (finite(v.w) && v.w > 0) v.w else dw)
                l["height"] = n1(if (finite(v.h) && v.h > 0) v.h else dh)
            }
            colors(v.color, v.itemColors, l)
            if (v.group.isNotEmpty()) l["group"] = v.group
            ids[o] = v.id
            when (v.type) {
                VertexType.State -> {
                    if (v.stereotype.isNotEmpty()) o["stereotype"] = v.stereotype
                    if (v.submachine.isNotEmpty()) o["submachine"] = v.submachine
                    if (v.entry.isNotEmpty()) o["entry"] = v.entry
                    if (v.doActivity.isNotEmpty()) o["do"] = v.doActivity
                    if (v.exit.isNotEmpty()) o["exit"] = v.exit
                    if (v.deferrable.isNotEmpty()) o["defer"] = v.deferrable.joinToString(", ")
                    if (v.invariant.isNotEmpty()) o["invariant"] = v.invariant
                    val layout = v.regionLayout
                    if (v.regions.size > 1 && layout != null) l["regionLayout"] = if (layout == RegionLayout.Horizontal) "horizontal" else "vertical"
                    transitionsOf(v, o, l)
                    val points = model.vertices.filter { it.parent == v.id && (it.type == VertexType.EntryPoint || it.type == VertexType.ExitPoint || it.type == VertexType.ConnectionPointRef) }
                    pointsOf(v, points, o, l)
                    if (v.regions.isNotEmpty()) regionsOf(v, v.regions, o, l)
                }
                VertexType.Comment -> {
                    if (v.text.isNotEmpty()) o["text"] = v.text
                    val anchors = ArrayList<Any>()
                    for (a in v.anchors) {
                        val w = ix.anchor(a)
                        if (w == null) lost.add("the anchor of ${describe(v)} to '$a', which is not an element or a transition")
                        else if (w !== NOT_HELD) anchors.add(w)
                        else if (a in ix.v) lost.add("the anchor of ${describe(v)} to an initial pseudostate")
                        else lost.add("the anchor of ${describe(v)} to ${describeT(ix.t.getValue(a))}")
                    }
                    if (anchors.isNotEmpty()) o["anchors"] = anchors
                }
                VertexType.Final, VertexType.Terminate -> {
                    if (v.regions.isNotEmpty() || v.entry.isNotEmpty() || v.exit.isNotEmpty() || v.doActivity.isNotEmpty()) lost.add("the regions and behaviors of ${describe(v)}")
                    if (outOf(v.id).isNotEmpty()) {
                        for (t in outOf(v.id)) doneT.add(t)
                        lost.add("the transitions leaving ${describe(v)}")
                    }
                }
                else -> transitionsOf(v, o, l)
            }
            return o to l
        }

        // The fields of region `r` (owned by `owner`, null at the top level) into `o` and `l`.
        fun regionFieldsOf(r: Region, owner: Vertex?, o: OutObject, l: OutObject) {
            // Each element is written once (a region id used twice in a file edited by hand could hold a state's own owner).
            val all = model.vertices.filter { it.parent == r.id && !ix.isMachinePoint(it) && !ix.isBorder(it) }
            val kids = all.filter { it !in done }
            if (kids.size < all.size) lost.add("the region of ${if (owner != null) describe(owner) else "the state machine"} as it was: its id is used twice, and its elements are written once")
            val at = origin(owner)
            for ((key, type) in PSEUDO) {
                val found = kids.filter { it.type == type }
                if (found.isEmpty()) continue
                val v = found[0]
                for (x in found.drop(1)) {
                    done.add(x)
                    lost.add("${describe(x)}: a region has at most one")
                }
                done.add(v)
                val po = OutObject()
                val pl = position(v, at)
                if (v.group.isNotEmpty()) pl["group"] = v.group
                val outs = outOf(v.id)
                if (outs.isNotEmpty()) {
                    val t = outs[0]
                    doneT.add(t)
                    val target = ix.ref(t.target)
                    if (target == null) lost.add("the $key transition to an initial pseudostate")
                    else if (target != "" || unresolvedOf(t.target) == null) po["target"] = target
                    if (t.action.isNotEmpty()) po["action"] = t.action
                    if (t.guard.isNotEmpty() || t.triggers.isNotEmpty() || t.kind != TransitionKind.External || t.precondition.isNotEmpty() || t.postcondition.isNotEmpty()) {
                        lost.add("the guard, triggers, kind or conditions of the $key transition")
                    }
                    // Its route, when the file holds the transition (a target or an action).
                    val tl = transitionLayout(t, null, null)
                    if (tl != null && (po["target"] != null || po["action"] != null)) pl.assign(tl)
                    tids[po] = t.id
                }
                for (x in outs.drop(1)) {
                    doneT.add(x)
                    lost.add("a second transition leaving the $key pseudostate")
                }
                ids[po] = v.id
                o[key] = po
                l[key] = pl
            }
            val mo = ArrayList<Any>()
            val lo = ArrayList<Any>()
            for (v in kids) {
                if (v.type == VertexType.Initial || v.type == VertexType.ShallowHistory || v.type == VertexType.DeepHistory || v in done) continue
                if (!isElementKind(v.type)) {
                    lost.add("${describe(v)}, which only goes on the border of a state or at the top level")
                    continue
                }
                val (eo, el) = element(v, at)
                mo.add(eo)
                lo.add(el)
            }
            // A composite state's only region, empty: written so that the state stays composite.
            if (mo.isNotEmpty() || (owner != null && owner.regions.size == 1 && o["initial"] == null && o["history"] == null && o["deepHistory"] == null)) o["elements"] = mo
            if (lo.isNotEmpty()) l["elements"] = lo
        }

        regionsOf = { owner, regions, o, l ->
            if (regions.size == 1) regionFieldsOf(regions[0], owner, o, l)
            else {
                val mo = ArrayList<Any>()
                val lo = ArrayList<Any>()
                for (r in regions) {
                    val ro = OutObject()
                    ro["name"] = r.name
                    val rl = OutObject()
                    rl["name"] = r.name
                    val size = r.size
                    if (owner != null && size != null && finite(size) && n1(size) > 0) rl["size"] = n1(size)
                    regionFieldsOf(r, owner, ro, rl)
                    ids[ro] = r.id
                    mo.add(ro)
                    lo.add(rl)
                }
                o["regions"] = mo
                l["regions"] = lo
            }
        }

        val mroot = OutObject()
        mroot["format"] = 1.0
        mroot["name"] = model.name
        mroot["type"] = if (model.kind == MachineKind.Protocol) "protocol" else "behavioral"
        val lroot = OutObject()
        lroot["format"] = 1.0
        if (model.documentation.isNotEmpty()) mroot["documentation"] = model.documentation
        pointsOf(null, model.vertices.filter { ix.isMachinePoint(it) }, mroot, lroot)
        regionsOf(null, model.regions, mroot, lroot)
        for (v in model.vertices) if (v !in done) lost.add("${describe(v)}, which is not inside a known region")
        for (t in model.transitions) {
            if (t in doneT) continue
            val s = ix.get(t.source)
            lost.add(if (s != null && s.type == VertexType.Comment) "a transition leaving ${describe(s)}" else "${describeT(t)}, whose source does not exist")
        }

        val lines = HashMap<String, Int>()
        val modelText = Json.write(mroot, { o -> o.entries.all { !Json.isComposite(it.second) } }) { o, line ->
            ids[o]?.let { if (it !in lines) lines[it] = line }
            tids[o]?.let { if (it !in lines) lines[it] = line }
        }
        val layoutText = Json.write(lroot, { o ->
            val e = o.entries
            e.size == 2 && e[0].first == "x" && e[1].first == "y"
        })
        return Written(modelText, layoutText, lines, lost)
    }

    // ---------------------------------------------------------------- names

    /** Prefix of the names generated for each kind of element. */
    private fun generated(t: VertexType): String = when (t) {
        VertexType.EntryPoint -> "entry"
        VertexType.ExitPoint -> "exit"
        else -> t.key
    }

    /** Whether a name is a generated one, not shown on the diagram. */
    fun isGenerated(name: String?): Boolean = name != null && name.endsWith("*")

    /** A name as the diagram shows it: nothing for a generated one. */
    fun shown(name: String?): String = if (isGenerated(name)) "" else name ?: ""

    private val JS_SPACES = Regex(JsWhitespace.CLASS + "+")

    private class Named(val get: () -> String, val set: (String) -> Unit, val what: String, val prefix: String)

    /**
     * Gives every element, point and region that needs a name one: a generated name (`choice1*`, smallest free number)
     * for those without. With [fix] (converting an old file), it also replaces spaces, `*` and `.` in names with `_`,
     * numbers the names used twice (`Idle2`), and renames points named `history` or `deepHistory`; it returns the
     * renames. Without it, a name used twice stays as it is (the validator reports it, and the file is saved back as it
     * was). Changes [model] in place.
     */
    fun assignNames(model: FsmModel, fix: Boolean = false): List<Rename> {
        val renames = ArrayList<Rename>()
        val ix = Index(model)
        fun clean(s: String) = if (fix) JS_SPACES.replace(JsWhitespace.trim(s), "_").replace('*', '_').replace('.', '_') else s
        val taken = HashSet<String>()
        val machine = clean(model.name)
        if (machine != model.name) renames.add(Rename("the state machine", model.name, machine))
        model.name = machine
        taken.add(machine)
        // The named things in document order: regions of an owner with several, elements.
        val named = ArrayList<Named>()
        fun regionsNamed(regions: List<Region>, owner: String) {
            if (regions.size < 2) return
            for ((i, r) in regions.withIndex()) named.add(Named({ r.name }, { r.name = it }, "region ${i + 1} of $owner", "region"))
        }
        regionsNamed(model.regions, "the state machine")
        for (v in model.vertices) {
            if (!isElementKind(v.type)) continue
            named.add(Named({ v.name }, { v.name = it }, label(v.type), generated(v.type)))
            if (v.type == VertexType.State) regionsNamed(v.regions, if (v.name.isNotEmpty()) "'${v.name}'" else "a state")
        }
        // Names kept first (the first of each), then the others.
        val later = ArrayList<Named>()
        for (n in named) {
            val name = clean(n.get())
            if (name.isNotEmpty() && name !in taken) {
                if (name != n.get()) renames.add(Rename("the ${n.what}", n.get(), name))
                n.set(name)
                taken.add(name)
            } else later.add(n)
        }
        fun generate(prefix: String, used: Set<String>): String {
            var i = 1
            while ("$prefix$i*" in used) i++
            return "$prefix$i*"
        }
        for (n in later) {
            val old = n.get()
            val base = clean(old)
            val name: String
            if (base.isEmpty()) name = generate(n.prefix, taken)
            else if (!fix) continue
            else {
                var i = 2
                while ("$base$i" in taken) i++
                name = "$base$i"
                renames.add(Rename("the ${n.what}", old, name, "its name was used twice"))
            }
            n.set(name)
            taken.add(name)
        }
        // Points: unique on their owner.
        val byOwner = LinkedHashMap<String, MutableList<Vertex>>()
        for (v in model.vertices) {
            if (!(ix.isBorder(v) || ix.isMachinePoint(v)) || v.type == VertexType.ConnectionPointRef) continue
            val key = if (ix.isMachinePoint(v)) "" else v.parent
            byOwner.getOrPut(key) { ArrayList() }.add(v)
        }
        for (points in byOwner.values) {
            val used = points.map { it.name }.filterTo(HashSet()) { it.isNotEmpty() }
            val seen = HashSet<String>()
            for (p in points) {
                var name = clean(p.name)
                if (fix && (name == "history" || name == "deepHistory")) name += "2"
                if (name.isNotEmpty() && name !in seen) {
                    if (name != p.name) renames.add(Rename("the ${label(p.type)}", p.name, name))
                } else if (name.isEmpty()) name = generate(generated(p.type), used)
                else if (fix) {
                    var i = 2
                    while ("$name$i" in seen || "$name$i" in used) i++
                    renames.add(Rename("the ${label(p.type)}", p.name, "$name$i", "its name was used twice on its state"))
                    name = "$name$i"
                }
                p.name = name
                seen.add(name)
                used.add(name)
            }
        }
        return renames
    }

    // ---------------------------------------------------------------- layout

    private fun isNum(n: JsonNode?) = n != null && n.kind == JsonKind.Number

    /** Problems of the layout file's shape (the first one, enough for the warning), or null. */
    fun layoutShapeProblem(root: JsonNode): String? {
        val problems = ArrayList<Problem>()
        fun add(line: Int, message: String) = problems.add(Problem(line, message))
        val itemKeys = ITEM_COLOR_FILE_KEY.values.toList()
        fun check(node: JsonNode, what: String, allowed: Collection<String>, required: Collection<String>, each: (JsonField) -> Unit) {
            val fields = node.fields
            if (fields == null) {
                add(node.line, "$what must be an object.")
                return
            }
            for (f in fields) {
                if (f.key in allowed) each(f) else add(f.line, "Unknown field '${f.key}' in $what.")
            }
            for (r in required) if (fields.none { it.key == r }) add(node.line, "Missing field '$r' in $what.")
            if (fields.any { it.key == "regions" }) {
                for (f in fields) if (f.key in REGION_FIELDS) add(f.line, "Field '${f.key}' cannot be used with 'regions'.")
            }
        }
        fun point(n: JsonNode, what: String) = check(n, what, listOf("x", "y"), listOf("x", "y")) { f ->
            if (!isNum(f.value)) add(f.value.line, "Field '${f.key}' must be a number.")
        }
        fun listOfItems(f: JsonField, item: (JsonNode) -> Unit) {
            val items = f.value.items
            if (items == null) {
                add(f.value.line, "Field '${f.key}' must be a list.")
                return
            }
            items.forEach(item)
        }
        lateinit var common: (JsonField) -> Unit
        common = { f ->
            when (f.key) {
                "x", "y", "width", "height", "size" -> if (!isNum(f.value)) add(f.value.line, "Field '${f.key}' must be a number.")
                "name", "trigger", "target", "group" -> if (f.value.kind != JsonKind.String) add(f.value.line, "Field '${f.key}' must be a text.")
                "color" -> if (!ElementColors.isColor(f.value.str)) add(f.value.line, "Field 'color' must be a color (#rrggbb).")
                "itemColors" -> check(f.value, "'itemColors'", itemKeys, emptyList()) { g ->
                    if (!ElementColors.isColor(g.value.str)) add(g.value.line, "Field '${g.key}' must be a color (#rrggbb).")
                }
                "label" -> point(f.value, "'label'")
                "bends" -> listOfItems(f) { point(it, "a bend point") }
                "regionLayout" -> if (f.value.str != "horizontal" && f.value.str != "vertical") add(f.value.line, "Field 'regionLayout' must be \"horizontal\" or \"vertical\".")
                "transitions" -> listOfItems(f) { check(it, "a transition", listOf("trigger", "target", "bends", "label", "color", "itemColors"), emptyList(), common) }
                "points" -> listOfItems(f) { check(it, "a point", listOf("name", "x", "y", "color", "itemColors", "group", "transitions"), listOf("name"), common) }
                "initial", "history", "deepHistory" -> check(f.value, "'${f.key}'", listOf("x", "y", "group", "bends", "label", "color", "itemColors"), emptyList(), common)
                "elements" -> listOfItems(f) {
                    check(it, "an element", listOf("name", "x", "y", "width", "height", "color", "itemColors", "group", "regionLayout", "transitions", "points", "regions") + REGION_FIELDS, listOf("name"), common)
                }
                "regions" -> listOfItems(f) { check(it, "a region", listOf("name", "size") + REGION_FIELDS, listOf("name"), common) }
            }
        }
        if (root.fields == null) return "The layout file must hold a JSON object."
        check(root, "the layout", listOf("\$schema", "format", "points", "regions") + REGION_FIELDS, listOf("format")) { f ->
            when (f.key) {
                "format" -> if (f.value.number != 1.0) add(f.value.line, "Field 'format' must be 1.")
                "\$schema" -> if (f.value.kind != JsonKind.String) add(f.value.line, "Field '\$schema' must be a text.")
                else -> common(f)
            }
        }
        return problems.firstOrNull()?.let { "line ${it.line}: ${it.message}" }
    }

    private fun itemColorsOf(node: JsonNode): MutableMap<String, String> {
        val ic = LinkedHashMap<String, String>()
        for (f in node.fields ?: emptyList()) {
            val k = ITEM_COLOR_FROM_FILE[f.key]
            if (k != null && ElementColors.isColor(f.value.str)) ic[k] = f.value.str!!
        }
        return ic
    }

    /**
     * Gives the vertices of a model just read ([readModel]) their places and the transitions their routes, from the
     * layout file's text (null when there is none). Elements without an entry are placed automatically: default size,
     * the first free spot beside the elements already in their region, their state growing to fit. Entries that match
     * nothing are left out. Sets [ReadMachine.layoutProblem] and [ReadMachine.placed].
     */
    fun applyLayout(read: ReadMachine, layoutText: String?) {
        val model = read.model!!
        val ix = Index(model)
        read.placed = 0
        read.layoutProblem = null
        var root: JsonNode? = null
        if (layoutText != null) {
            try {
                root = Json.parse(layoutText)
                val problem = layoutShapeProblem(root)
                if (problem != null) {
                    read.layoutProblem = problem
                    root = null
                }
            } catch (e: JsonSyntaxException) {
                read.layoutProblem = "line ${e.line}: ${e.message}"
            }
        }
        // Positions relative to the owner, before they are made absolute.
        val rel = java.util.IdentityHashMap<Vertex, PointD>()
        val placedV = HashSet<Vertex>()
        val routes = LinkedHashMap<Transition, JsonNode>()
        fun numOf(n: JsonNode, k: String) = n.field(k)?.value?.number
        // The place, size, colors and group of an entry; `colors` false for an initial or history entry, whose are its arrow's.
        fun setBox(v: Vertex, entry: JsonNode, colors: Boolean) {
            val x = numOf(entry, "x")
            val y = numOf(entry, "y")
            if (x != null && y != null) {
                rel[v] = PointD(x, y)
                placedV.add(v)
            }
            val (dw, dh) = defaultSize(v.type)
            val w = numOf(entry, "width")
            val h = numOf(entry, "height")
            v.w = if (isResizable(v.type) && w != null && w > 0) w else dw
            v.h = if (isResizable(v.type) && h != null && h > 0) h else dh
            val color = entry.field("color")?.value?.str
            if (colors && ElementColors.isColor(color)) v.color = color!!
            val items = if (colors) entry.field("itemColors")?.value else null
            if (items?.fields != null) {
                val ic = itemColorsOf(items)
                if (ic.isNotEmpty()) v.itemColors = ic
            }
            val group = entry.field("group")?.value?.str
            if (!group.isNullOrEmpty()) v.group = group
        }
        // Matches transition entries to the transitions leaving the source: by trigger and target, then by order.
        fun matchTransitions(sourceId: String, entries: List<JsonNode>) {
            val list = model.transitions.filter { it.source == sourceId }
            val used = HashSet<Transition>()
            for (e in entries) {
                // The trigger as the model file may write it (`a,b`): the events it names.
                val trigger = splitEvents(e.field("trigger")?.value?.str ?: "").joinToString(", ")
                val target = e.field("target")?.value?.str
                val t = list.firstOrNull { x ->
                    x !in used && x.triggers.joinToString(", ") == trigger && (if (x.kind == TransitionKind.Internal) target == null else ix.ref(x.target) == target)
                } ?: continue
                used.add(t)
                routes[t] = e
            }
        }
        fun applyPoints(owner: Vertex?, node: JsonNode) {
            val points = model.vertices.filter { if (owner != null) it.parent == owner.id && ix.isBorder(it) else ix.isMachinePoint(it) }
            val used = HashSet<Vertex>()
            for (e in itemsOf(node, "points")) {
                val name = e.field("name")?.value?.str
                val p = points.firstOrNull { it !in used && ix.pointName(it) == name } ?: continue
                used.add(p)
                setBox(p, e, true)
                matchTransitions(p.id, itemsOf(e, "transitions"))
            }
        }
        fun applyRegion(regionId: String, node: JsonNode) {
            val kids = model.vertices.filter { it.parent == regionId && !ix.isMachinePoint(it) && !ix.isBorder(it) }
            for ((key, type) in PSEUDO) {
                val e = node.field(key)?.value ?: continue
                val v = kids.firstOrNull { it.type == type } ?: continue
                setBox(v, e, false)
                val t = model.transitions.firstOrNull { it.source == v.id }
                if (t != null) routes[t] = e
            }
            val used = HashSet<Vertex>()
            for (e in itemsOf(node, "elements")) {
                val name = e.field("name")?.value?.str
                val v = kids.firstOrNull { it !in used && isElementKind(it.type) && it.name == name } ?: continue
                used.add(v)
                setBox(v, e, true)
                if (v.type != VertexType.State) {
                    matchTransitions(v.id, itemsOf(e, "transitions"))
                    continue
                }
                when (e.field("regionLayout")?.value?.str) {
                    "horizontal" -> v.regionLayout = RegionLayout.Horizontal
                    "vertical" -> v.regionLayout = RegionLayout.Vertical
                }
                matchTransitions(v.id, itemsOf(e, "transitions"))
                applyPoints(v, e)
                val regs = v.regions
                val items = e.field("regions")?.value?.items
                if (regs.size == 1 && items == null) applyRegion(regs[0].id, e)
                else if (regs.size > 1 && items != null) {
                    for (item in items) {
                        val r = regs.firstOrNull { it.name == item.field("name")?.value?.str } ?: continue
                        val size = numOf(item, "size")
                        if (size != null && size > 0) r.size = size
                        applyRegion(r.id, item)
                    }
                }
            }
        }
        val rootNode = root
        if (rootNode != null) {
            applyPoints(null, rootNode)
            val items = rootNode.field("regions")?.value?.items
            if (model.regions.size == 1 && items == null) applyRegion(model.regions[0].id, rootNode)
            else if (model.regions.size > 1 && items != null) {
                for (item in items) {
                    val r = model.regions.firstOrNull { it.name == item.field("name")?.value?.str }
                    if (r != null) applyRegion(r.id, item)
                }
            }
        }

        // Automatic places, region by region, innermost first: a state grows to hold what was placed in it
        // automatically (a state that grew is in turn new to its own region) before the elements beside it are placed.
        val auto = HashSet<Vertex>()
        for (v in model.vertices) {
            if (v.w == 0.0 || v.h == 0.0 || v.w.isNaN() || v.h.isNaN()) {
                val (w, h) = defaultSize(v.type)
                v.w = w
                v.h = h
            }
        }
        fun grow(v: Vertex) {
            val regionIds = v.regions.mapTo(HashSet()) { it.id }
            val kids = model.vertices.filter { it.parent in regionIds && !ix.isBorder(it) }
            if (kids.none { it in auto }) return
            val w0 = v.w
            val h0 = v.h
            for (k in kids) {
                val p = rel.getValue(k)
                v.w = maxOf(v.w, n1(p.x + k.w + 20))
                v.h = maxOf(v.h, n1(p.y + k.h + 20))
            }
            if (v.w != w0 || v.h != h0) auto.add(v)
        }
        // The model lists a state before what it contains, and a state's regions together: reversed, a state's
        // regions come after everything inside them, its first region last.
        val allRegionIds = model.regions.mapTo(ArrayList()) { it.id }
        for (v in model.vertices) v.regions.mapTo(allRegionIds) { it.id }
        allRegionIds.reverse()
        for (rid in allRegionIds) {
            val owner = ix.regionOwner[rid]
            val kids = model.vertices.filter { it.parent == rid && !ix.isBorder(it) }
            val index = if (owner != null) owner.regions.indexOfFirst { it.id == rid } else 0
            for (v in kids) {
                if (v in placedV) continue
                val placed = kids.filter { it in placedV }.map { Triple(rel.getValue(it), it.w, it.h) }
                val p = if (placed.isNotEmpty()) PointD(placed.maxOf { it.first.x + it.second } + 40, placed.minOf { it.first.y })
                else if (owner != null) PointD(30.0, 50.0 + maxOf(0, index) * 80)
                else PointD(40.0, 40.0)
                rel[v] = PointD(n1(p.x), n1(p.y))
                placedV.add(v)
                auto.add(v)
                read.placed++
            }
            if (owner != null && index == 0 && owner.type == VertexType.State) grow(owner)
        }
        // Points without a place: on their state's border, entries on the left, exits on the right.
        for (v in model.vertices) {
            if (!ix.isBorder(v) || v in placedV) continue
            val owner = ix.get(v.parent) ?: continue
            val same = model.vertices.filter { it.parent == owner.id && ix.isBorder(it) && it !in placedV && it.type == v.type }
            val i = same.indexOf(v)
            val exit = v.type == VertexType.ExitPoint || (v.type == VertexType.ConnectionPointRef && v.pointKind == PointKind.Exit)
            rel[v] = PointD(n1(if (exit) owner.w - v.w / 2 else -v.w / 2), n1(minOf(owner.h - v.h, 10.0 + i * 24)))
            read.placed++
        }
        // Absolute coordinates, owners first (the model lists a state before what it contains).
        val busy = HashSet<Vertex>()
        fun abs(v: Vertex): PointD {
            if (!v.x.isNaN()) return PointD(v.x, v.y)
            val owner = if (busy.add(v)) ix.owner(v) else null
            val o = if (owner != null) abs(owner) else PointD(0.0, 0.0)
            val p = rel[v] ?: PointD(0.0, 0.0)
            v.x = n1(o.x + p.x)
            v.y = n1(o.y + p.y)
            return PointD(v.x, v.y)
        }
        for (v in model.vertices) abs(v)
        // Routes
        for ((t, e) in routes) {
            val f = ix.frame(t)
            val o = if (f != null) PointD(f.x, f.y) else PointD(0.0, 0.0)
            val bends = e.field("bends")?.value?.items
            if (!bends.isNullOrEmpty() && t.kind != TransitionKind.Internal) {
                t.points = bends.mapTo(mutableListOf()) { b -> PointD(n1(o.x + numOf(b, "x")!!), n1(o.y + numOf(b, "y")!!)) }
            }
            val label = e.field("label")?.value
            if (label != null && t.kind != TransitionKind.Internal) t.labelOffset = PointD(numOf(label, "x")!!, numOf(label, "y")!!)
            val color = e.field("color")?.value?.str
            if (ElementColors.isColor(color)) t.color = color!!
            val items = e.field("itemColors")?.value
            if (items?.fields != null) {
                val ic = itemColorsOf(items)
                if (ic.isNotEmpty()) t.itemColors = ic
            }
        }
        model.normalizeGroups()
    }

    /** Reads a machine's two files (the layout file's text null when there is none). */
    fun readMachine(modelText: String, layoutText: String?): ReadMachine {
        val read = readModel(modelText)
        if (!read.failed) applyLayout(read, layoutText)
        return read
    }

    /**
     * Resolves the references of [model] that named nothing when they were read (a transition's target, a comment's
     * anchors) and name something now, as reading its file again would ([written]: `writeMachine(model)`, which holds
     * every anchor). Changes the model in place; returns whether it changed.
     */
    fun resolveReferences(model: FsmModel, written: Written): Boolean {
        fun open(a: String) = a.startsWith(UNRESOLVED) || a.startsWith(UNRESOLVED_ANCHOR)
        val pending = model.transitions.any { !unresolvedOf(it.target).isNullOrEmpty() } || model.vertices.any { v -> v.anchors.any { open(it) } }
        if (!pending) return false
        val read = readModel(written.model)
        if (read.failed) return false
        val ids = derivedIds(model, written)
        val own = HashMap<String, String>()
        for ((k, v) in ids) own[v] = k
        val rt = read.model!!.transitions.associateBy { it.id }
        val rv = read.model.vertices.associateBy { it.id }
        var changed = false
        for (t in model.transitions) {
            if (unresolvedOf(t.target).isNullOrEmpty()) continue
            val target = ids[t.id]?.let { rt[it] }?.target ?: continue
            val id = own[target] ?: continue
            t.target = id
            changed = true
        }
        for (v in model.vertices) {
            if (v.anchors.none { open(it) }) continue
            val anchors = ids[v.id]?.let { rv[it] }?.anchors ?: emptyList<String>()
            v.anchors = v.anchors.mapIndexedTo(mutableListOf()) { i, a ->
                val id = if (open(a) && i < anchors.size) own[anchors[i]] else null
                if (id == null) a
                else {
                    changed = true
                    id
                }
            }
        }
        return changed
    }

    /**
     * The ids reading [model] back from its file would give (what fsm-cli calls the elements, in generated code's traces
     * and in its problems), by the model's own ids: vertices and transitions.
     */
    fun derivedIds(model: FsmModel, written: Written = writeMachine(model)): Map<String, String> {
        val read = readModel(written.model)
        val output = HashMap<String, String>()
        if (read.failed) return output
        val byLine = HashMap<Int, String>()
        val byLineT = HashMap<Int, String>()
        for (v in read.model!!.vertices) byLine[read.lines.getValue(v.id)] = v.id
        for (t in read.model.transitions) byLineT[read.lines.getValue(t.id)] = t.id
        for (v in model.vertices) {
            val id = written.lines[v.id]?.let { byLine[it] }
            if (id != null) output[v.id] = id
        }
        for (t in model.transitions) {
            val id = written.lines[t.id]?.let { byLineT[it] }
            if (id != null) output[t.id] = id
        }
        return output
    }
}
