package com.vincegosoftware.fsmeditor.core

class LoadException(message: String) : Exception(message)

/**
 * Reading a machine's model from a file's text, in either format: the JSON format ([Format]), or the XMI of earlier
 * versions ([Xmi], read as it is, without converting it). A port of the Visual Studio edition's `Load.cs` (the VS Code
 * edition's `src/load.ts`).
 */
object Load {
    /**
     * The model of a `.fsm` file's text, and of its layout file's text when given (else every element is placed
     * automatically). Throws [LoadException] when the text can't be read (the first problem, with its line).
     */
    fun loadModel(text: String, layout: String? = null): FsmModel {
        val r = Common.call("load", Common.args("model" to Common.str(text), "layout" to Common.str(layout))) as Map<*, *>
        (r["error"] as? String)?.let { throw LoadException(it) }
        return ModelJson.read(r["model"])
    }

    /**
     * The submachine paths of a model, relative to its file (`Payment.fsm`; an old file's hrefs without their `#id`,
     * decoded: [old]).
     */
    fun submachineFiles(model: FsmModel, old: Boolean): List<String> =
        model.vertices.filter { it.type == VertexType.State && it.submachine.isNotEmpty() }
            .map { if (old) Hrefs.decodeUri(it.submachine.split('#')[0]) else it.submachine }

    /** The submachine paths of a file's text (either format); none when it can't be read. */
    fun submachineFilesOf(text: String): List<String> {
        val r = Common.call("load", Common.args("model" to Common.str(text))) as Map<*, *>
        return if (r.containsKey("error")) emptyList() else ModelJson.strings(r, "submachines")
    }
}
