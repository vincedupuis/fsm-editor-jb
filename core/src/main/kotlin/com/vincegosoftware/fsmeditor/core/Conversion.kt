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
 * Converting files of earlier versions (XMI) to the JSON format, fsm-editor-common's ([Common]). Machines linked by
 * submachine references are converted together. Unnamed elements get generated names (`choice1*`), spaces, `*` and
 * `.` in names become `_`, a name used twice gets a number (`Idle2`). What the JSON format can't hold is left out and
 * reported.
 */
object Conversion {
    /** Converts old files together (a machine and the machines linked to it by submachine references). */
    fun convertGroup(inputs: List<ConvertInput>): List<Converted> {
        val list = inputs.joinToString(",", "[", "]") { Common.args("path" to Common.str(it.path), "text" to Common.str(it.text)) }
        val r = Common.call("convert", Common.args("inputs" to list)) as List<*>
        return r.filterIsInstance<Map<*, *>>().map { c ->
            Converted(
                path = ModelJson.str(c, "path"),
                model = c["model"]?.let(ModelJson::read),
                modelText = ModelJson.str(c, "modelText"),
                layoutText = ModelJson.str(c, "layoutText"),
                renames = ModelJson.strings(c, "renames"),
                generated = ModelJson.num(c, "generated").toInt(),
                losses = ModelJson.strings(c, "losses"),
                error = c["error"] as? String,
            )
        }
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
