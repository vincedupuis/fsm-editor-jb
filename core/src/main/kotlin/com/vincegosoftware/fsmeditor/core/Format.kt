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

    fun defaultSize(t: VertexType): Pair<Double, Double> = t.defaultSize

    fun isResizable(t: VertexType) = t == VertexType.State || t == VertexType.Comment || t == VertexType.Fork || t == VertexType.Join

    /** Whether a name is a generated one, not shown on the diagram. */
    fun isGenerated(name: String?): Boolean = name != null && name.endsWith("*")

    /** A name as the diagram shows it: nothing for a generated one. */
    fun shown(name: String?): String = if (isGenerated(name)) "" else name ?: ""

    /** Reads a machine's two files (no layout file: [layoutText] null). */
    fun readMachine(modelText: String, layoutText: String?): ReadMachine {
        val r = Common.call("read", Common.args("model" to Common.str(modelText), "layout" to Common.str(layoutText))) as Map<*, *>
        (r["problems"] as? List<*>)?.let { problems ->
            return ReadMachine(problems = problems.filterIsInstance<Map<*, *>>().map { Problem(ModelJson.num(it, "line").toInt(), ModelJson.str(it, "message")) })
        }
        return ReadMachine(model = ModelJson.read(r["model"]), lines = lines(r)).apply {
            layoutProblem = r["layoutProblem"] as? String
            placed = ModelJson.num(r, "placed").toInt()
        }
    }

    /** Reads a model file alone (every element placed automatically). */
    fun readModel(text: String): ReadMachine = readMachine(text, null)

    /**
     * Writes a machine into its two files, giving generated names to what has none and resolving the references that
     * name something now; [Written.lost] lists what the format can't hold (nothing is written then). The model written
     * (names given, references resolved) is read back into [model] when it changed.
     */
    fun writeMachine(model: FsmModel): Written {
        val r = Common.call("write", Common.args("model" to ModelJson.write(model))) as Map<*, *>
        (r["lost"] as? List<*>)?.let { return Written("", "", emptyMap(), it.filterIsInstance<String>()) }
        r["model"]?.let {
            val m = ModelJson.read(it)
            model.name = m.name
            model.kind = m.kind
            model.context = m.context
            model.documentation = m.documentation
            model.regions = m.regions
            model.vertices = m.vertices
            model.transitions = m.transitions
        }
        return Written(ModelJson.str(r, "modelText"), ModelJson.str(r, "layoutText"), lines(r), emptyList())
    }

    /**
     * The ids reading [model] back from its file would give (what fsm-cli calls the elements, in generated code's
     * traces and in its problems), by the model's own ids.
     */
    fun derivedIds(model: FsmModel): Map<String, String> {
        val r = Common.call("derivedIds", Common.args("model" to ModelJson.write(model))) as? Map<*, *> ?: return emptyMap()
        return r.entries.associate { (k, v) -> k as String to (v as? String ?: "") }
    }

    private fun lines(r: Map<*, *>): MutableMap<String, Int> =
        (r["lines"] as? Map<*, *>)?.entries?.associateTo(HashMap()) { (k, v) -> k as String to ((v as? Double)?.toInt() ?: 0) } ?: HashMap()
}
