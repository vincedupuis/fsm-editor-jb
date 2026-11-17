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
        if (Format.isOldFormat(text)) {
            try {
                return Xmi.fromXmi(text)
            } catch (e: XmlParseException) {
                throw LoadException(e.message ?: "")
            } catch (e: XmiException) {
                throw LoadException(e.message ?: "")
            }
        }
        val read = Format.readMachine(text, layout)
        if (read.failed) throw LoadException("Line ${read.problems!![0].line}: ${read.problems[0].message}")
        return read.model!!
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
        if (!Format.isOldFormat(text)) {
            // Paths only: no layout to apply.
            val read = Format.readModel(text)
            return if (read.failed) emptyList() else submachineFiles(read.model!!, false)
        }
        return try {
            submachineFiles(loadModel(text), true)
        } catch (_: LoadException) {
            emptyList()
        }
    }
}
