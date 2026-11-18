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
 * The diagram merge (three panes): splitting a file with conflict markers into its two sides, and taking one side's
 * version of an element into the result, fsm-editor-common's ([Common]).
 */
object Merge {
    private val START = Regex("^<{7}(?: |\\r?$)", RegexOption.MULTILINE)
    private val END = Regex("^>{7}(?: |\\r?$)", RegexOption.MULTILINE)

    /** Whether a text holds Git's conflict markers. */
    fun hasConflictMarkers(text: String?): Boolean = text != null && START.containsMatchIn(text) && END.containsMatchIn(text)

    /**
     * Splits a text with conflict markers into its two sides: `<<<<<<<` current `=======` incoming `>>>>>>>`, and the
     * three-part style, whose base section (`|||||||`) is left out. Throws [ConflictMarkerException] when the markers
     * don't nest.
     */
    fun splitConflicts(text: String): ConflictSplit {
        val r = Common.call("splitConflicts", Common.args("text" to Common.str(text))) as Map<*, *>
        (r["error"] as? String)?.let { throw ConflictMarkerException(it) }
        return ConflictSplit(ModelJson.str(r, "current"), ModelJson.str(r, "incoming"), ModelJson.num(r, "conflicts").toInt())
    }

    /** The result with the element of [keys] (see [Compare]: its key in either version) as [source] has it. */
    fun adopt(result: FsmModel, source: FsmModel, vararg keys: String): FsmModel {
        val list = keys.joinToString(",", "[", "]") { Common.str(it) }
        return ModelJson.read(Common.call("adopt", Common.args("result" to ModelJson.write(result), "source" to ModelJson.write(source), "keys" to list)))
    }
}
