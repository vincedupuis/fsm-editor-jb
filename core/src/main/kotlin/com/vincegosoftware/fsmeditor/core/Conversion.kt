package com.vincegosoftware.fsmeditor.core

/** An old file to convert: its path (`/`-separated, absolute) and text. */
class ConvertInput(val path: String, val text: String)

class Converted(
    val path: String,
    /** The converted model (its ids are the old file's); null when the file couldn't be read. */
    val model: FsmModel? = null,
    /** The texts of the two new files. */
    val modelText: String = "",
    val layoutText: String = "",
    /** Names changed, as sentences. */
    val renames: List<String> = emptyList(),
    /** How many unnamed elements got a generated name. */
    val generated: Int = 0,
    /** What was left out, as sentences. */
    val losses: List<String> = emptyList(),
    /** Why the file could not be read (nothing else is set then). */
    val error: String? = null,
)

/**
 * Converting files of earlier versions (XMI, [Xmi]) to the JSON format ([Format]). Machines linked by submachine
 * references are converted together: a connection point reference names its point by xmi:id in the other file, and
 * becomes that point's name, which the conversion of the other file may give or change. A port of the Visual Studio
 * edition's `Conversion.cs` (the VS Code edition's `src/convert.ts`).
 *
 * Names: unnamed elements get generated names (`choice1*`, hidden on the diagram), spaces, `*` and `.` in names become
 * `_`, a name used twice gets a number (`Idle2`). What the JSON format can't hold is left out and reported.
 */
object Conversion {
    private fun label(t: VertexType): String = when (t) {
        VertexType.Initial -> "initial pseudostate"
        VertexType.ShallowHistory -> "shallow history"
        VertexType.DeepHistory -> "deep history"
        VertexType.Choice -> "choice"
        VertexType.Junction -> "junction"
        VertexType.Final -> "final state"
        VertexType.Terminate -> "terminate pseudostate"
        VertexType.EntryPoint -> "entry point"
        VertexType.ExitPoint -> "exit point"
        else -> t.key
    }

    private class Info(val renames: List<String>, val generated: Int, val losses: MutableList<String>)

    /** Converts old files together (a machine and the machines linked to it by submachine references). */
    fun convertGroup(inputs: List<ConvertInput>): List<Converted> {
        val read = ArrayList<Pair<String, XmiModel>>()
        val results = ArrayList<Converted>()
        for (i in inputs) {
            try {
                read.add(i.path to Xmi.read(i.text))
            } catch (e: Exception) {
                results.add(Converted(i.path, error = e.message ?: e.toString()))
            }
        }
        fun find(path: String) = read.firstOrNull { it.first == path }?.second
        // Names first, in every file: the points' new names are what the references become.
        val named = HashMap<String, Info>()
        for ((path, m) in read) {
            val losses = oldOnly(m)
            val unnamed = countUnnamed(m.model)
            val renames = Format.assignNames(m.model, true).map { r -> "${r.what} '${r.from}' is now '${r.to}'${if (r.why != null) " (${r.why})" else ""}" }
            named[path] = Info(renames, unnamed, losses)
        }
        for ((path, m) in read) {
            val info = named.getValue(path)
            for (v in m.model.vertices) {
                if (v.type != VertexType.State || v.submachine.isEmpty()) continue
                val hash = v.submachine.indexOf('#')
                val file = if (hash >= 0) v.submachine.substring(0, hash) else v.submachine
                val id = if (hash >= 0) v.submachine.substring(hash + 1).split('#')[0] else ""
                val sub = find(SlashPaths.join(SlashPaths.dirname(path), Hrefs.decodeUri(file)))
                val found = if (sub != null && (id.isEmpty() || sub.xmiId == id)) sub else null
                v.submachine = Hrefs.decodeUri(file)
                for (r in m.model.vertices) {
                    if (r.type != VertexType.ConnectionPointRef || r.parent != v.id) continue
                    val point = found?.model?.vertices?.firstOrNull { p -> p.id == r.ref && (p.type == VertexType.EntryPoint || p.type == VertexType.ExitPoint) }
                    if (point != null) r.ref = point.name
                    else if (r.ref.isNotEmpty()) info.losses.add("the connection point reference of state '${v.name}' to point '${r.ref}' of $file, which was not found: it keeps that id as the point's name")
                }
            }
        }
        for ((path, m) in read) {
            val info = named.getValue(path)
            val w = Format.writeMachine(m.model)
            results.add(Converted(path, m.model, w.model, w.layout, info.renames, info.generated, info.losses + w.lost))
        }
        return inputs.map { i -> results.first { it.path == i.path } }
    }

    /** Elements that need a name and have none. */
    private fun countUnnamed(m: FsmModel): Int {
        val namedTypes = setOf(
            VertexType.State, VertexType.Choice, VertexType.Junction, VertexType.Fork, VertexType.Join, VertexType.Final,
            VertexType.Terminate, VertexType.Comment, VertexType.EntryPoint, VertexType.ExitPoint,
        )
        var n = m.vertices.count { it.type in namedTypes && it.name.isEmpty() }
        for (regions in listOf(m.regions) + m.vertices.map { it.regions }) {
            if (regions.size > 1) n += regions.count { it.name.isEmpty() }
        }
        return n
    }

    /** What an old file holds that the JSON format has no place for, besides what the writer reports. */
    private fun oldOnly(x: XmiModel): MutableList<String> {
        val m = x.model
        val output = ArrayList<String>()
        fun describe(v: Vertex) = "the ${label(v.type)}${if (v.name.isNotEmpty()) " '${v.name}'" else ""}"
        fun pseudo(v: Vertex) = v.type == VertexType.Initial || v.type == VertexType.ShallowHistory || v.type == VertexType.DeepHistory
        if (x.context.isNotEmpty()) output.add("the context '${x.context}' of the state machine")
        if (m.regions.size == 1 && m.regions[0].name.isNotEmpty()) output.add("the name '${m.regions[0].name}' of the state machine's region")
        for (v in m.vertices) {
            if (v.type == VertexType.State && v.regions.size == 1 && v.regions[0].name.isNotEmpty()) output.add("the name '${v.regions[0].name}' of the region of state '${v.name}'")
            if (pseudo(v) && v.name.isNotEmpty()) output.add("the name of ${describe(v)}")
            if (pseudo(v) && (ElementColors.isColor(v.color) || v.itemColors.isNotEmpty())) output.add("the color of ${describe(v)}")
            if (!Format.isResizable(v.type) && v.type != VertexType.Comment) {
                val (w, h) = Format.defaultSize(v.type)
                if (Math.abs(v.w - w) > 0.05 || Math.abs(v.h - h) > 0.05) {
                    output.add("the size of ${describe(v)} (${Num.js(v.w)} × ${Num.js(v.h)}): it is drawn at its default size, around the same center")
                    v.x += (v.w - w) / 2
                    v.y += (v.h - h) / 2
                    v.w = w
                    v.h = h
                }
            }
        }
        return output
    }
}

/** Paths as the VS Code edition handles them: absolute, `/`-separated. */
object SlashPaths {
    /** The directory of a path (`/` for one at the root). */
    fun dirname(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i <= 0) "/" else p.substring(0, i)
    }

    /** [rel] resolved against the directory [dir], with `.` and `..` segments resolved. */
    fun join(dir: String, rel: String): String {
        val output = ArrayList<String>()
        for (s in (if (rel.startsWith("/")) rel else "$dir/$rel").split('/')) {
            if (s.isEmpty() || s == ".") continue
            if (s == "..") {
                if (output.isNotEmpty()) output.removeAt(output.size - 1)
            } else output.add(s)
        }
        return "/" + output.joinToString("/")
    }
}
