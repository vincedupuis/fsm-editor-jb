package com.vincegosoftware.fsmeditor.core

/** The format can't hold an edit: what it would leave out. */
class FormatLossException(val lost: List<String>) : Exception("the file can't hold ${lost.joinToString("; ")}.")

/** A machine's two files as the diagram edits them, at one point of its history. */
class MachineState(
    /** The model file's text: what saving writes (the file's own text when it can't be read). */
    val modelText: String,
    /** The layout file's text: what saving writes; null when there is none to write (no file, unreadable model). */
    val layoutText: String? = null,
    /** The model the diagram works on; null when the model file can't be read. */
    val model: FsmModel? = null,
    /** Why the model file can't be read: no diagram then. */
    val problems: List<Problem>? = null,
    /** Line of each element in [modelText], by id. */
    val lines: Map<String, Int> = emptyMap(),
    /** Elements placed automatically when the files were read (no layout entry). */
    val placed: Int = 0,
    /** Why the layout file couldn't be read, or null. */
    val layoutProblem: String? = null,
) {
    /** The same texts (what saving would write). */
    fun sameFiles(other: MachineState?): Boolean = other != null && modelText == other.modelText && layoutText == other.layoutText
}

/**
 * The two files of a machine and the diagram's model: reading them into a state, writing an edit of the model into
 * one, and finding the old files linked to one (they are converted together). The parts of the VS Code edition's
 * `src/fsmEditorProvider.ts` that use no editor API, shared by the editions. A port of the Visual Studio edition's
 * `MachineFiles.cs`.
 */
object MachineFiles {
    /** The layout file of a model file. */
    fun layoutPathOf(path: String) = "$path.layout"

    /** The state of a document whose files hold these texts (a new-format model; an old one is converted first). */
    fun stateOf(modelText: String, layoutText: String?): MachineState {
        val read = Format.readMachine(modelText, layoutText)
        if (read.failed) return MachineState(modelText, problems = read.problems)
        return MachineState(modelText, layoutText, read.model, null, read.lines, read.placed, read.layoutProblem)
    }

    /**
     * The state after an edit of the diagram: its model, written. The model is changed in place as saving it and
     * reading it again would: generated names given to what has none, and references that named nothing and name
     * something now resolved. Throws [FormatLossException] when the format can't hold it.
     */
    fun write(model: FsmModel): MachineState {
        Format.assignNames(model)
        val w = Format.writeMachine(model)
        if (w.lost.isNotEmpty()) throw FormatLossException(w.lost)
        Format.resolveReferences(model, w)
        return MachineState(w.model, w.layout, model, null, w.lines)
    }

    /**
     * The old files (XMI) linked to [path] by submachine references, transitively and both ways (the files it uses,
     * the files using it), with their texts: they are converted together. [others]: the workspace's old files (path and
     * text), for the machines using this one; [readText] reads a file by path (null when it can't).
     */
    fun linkedOldFiles(path: String, text: String, others: List<ConvertInput>, readText: (String) -> String?): List<ConvertInput> {
        val found = ArrayList<Pair<String, String>>()
        found.add(path to text)
        fun has(p: String) = found.any { it.first == p }
        val known = others.filter { it.path != path && Format.isOldFormat(it.text) }.distinctBy { it.path }
        fun uses(p: String, t: String) = Load.submachineFilesOf(t).map { SlashPaths.join(SlashPaths.dirname(p), it) }
        var changed = true
        while (changed) {
            changed = false
            for ((p, t) in found.toList()) {
                for (u in uses(p, t)) {
                    if (has(u)) continue
                    val sub = known.firstOrNull { it.path == u }?.text ?: readText(u)
                    if (sub != null && Format.isOldFormat(sub)) {
                        found.add(u to sub)
                        changed = true
                    }
                }
            }
            for (o in known) {
                if (has(o.path)) continue
                if (uses(o.path, o.text).any { has(it) }) {
                    found.add(o.path to o.text)
                    changed = true
                }
            }
        }
        return found.map { ConvertInput(it.first, it.second) }
    }

    /** The line of an element of an old file (XMI), found by its xmi:id; 1 when it isn't found. */
    fun oldLineOf(text: String, id: String?): Int {
        val at = if (id.isNullOrEmpty()) -1 else text.indexOf("xmi:id=\"$id\"")
        return if (at < 0) 1 else text.substring(0, at).count { it == '\n' } + 1
    }

    /** The message saying what converting old files did: the files converted, those skipped, every rename and loss. */
    fun conversionMessage(converted: List<Pair<String, Converted>>, skipped: List<String>): String {
        val lines = ArrayList<String>()
        for ((file, r) in converted) {
            for (x in r.renames) lines.add("$file: $x.")
            if (r.generated > 0) lines.add("$file: ${r.generated} unnamed element${if (r.generated == 1) "" else "s"} got a generated name (ending with *, not shown on the diagram).")
            for (l in r.losses) lines.add("$file: left out $l.")
        }
        var message = "Saved in the new format (the model in .fsm, the layout in .fsm.layout): ${converted.joinToString(", ") { it.first }}."
        if (skipped.isNotEmpty()) message += " Not converted, as they have unsaved changes or can't be read: ${skipped.joinToString(", ")}."
        if (lines.isNotEmpty()) message += " " + lines.joinToString(" ")
        return message
    }
}
