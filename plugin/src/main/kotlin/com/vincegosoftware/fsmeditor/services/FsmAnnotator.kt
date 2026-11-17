package com.vincegosoftware.fsmeditor.services

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.Format
import com.vincegosoftware.fsmeditor.core.Json
import com.vincegosoftware.fsmeditor.core.JsonSyntaxException
import com.vincegosoftware.fsmeditor.core.Load
import com.vincegosoftware.fsmeditor.core.LoadException
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.core.Severity
import com.vincegosoftware.fsmeditor.core.Validation

/**
 * Problems of a state machine file in its Text tab and in the Problems tool window: why the model file can't be
 * read (its shape; JSON syntax errors are the JSON support's), else the validation results, each at the line of its
 * element (an old file's at its `xmi:id`).
 */
class FsmAnnotator : ExternalAnnotator<FsmAnnotator.Input, FsmAnnotator.Result>(), DumbAware {
    class Input(val file: VirtualFile, val text: String, val workspace: FsmWorkspace)

    /** Problems by line (1-based; 0: the first line), with their severity. */
    class Result(val problems: List<Triple<Int, HighlightSeverity, String>>, val old: Boolean)

    override fun collectInformation(file: PsiFile): Input? {
        val vf = file.virtualFile ?: return null
        if (!FsmDocuments.isFsm(vf)) return null
        return Input(vf, file.text, FsmWorkspace.getInstance(file.project))
    }

    private fun severity(s: Severity) = when (s) {
        Severity.Error -> HighlightSeverity.ERROR
        Severity.Warning -> HighlightSeverity.WARNING
        Severity.Info -> HighlightSeverity.WEAK_WARNING
    }

    override fun doAnnotate(input: Input): Result? {
        if (input.text.isBlank()) return null
        if (Format.isOldFormat(input.text)) {
            val model = try {
                Load.loadModel(input.text)
            } catch (e: LoadException) {
                return Result(listOf(Triple(1, HighlightSeverity.ERROR, e.message ?: "")), true)
            }
            val submachines = ApplicationManager.getApplication().runReadAction(Computable { input.workspace.resolve(input.file, model).second })
            val issues = Validation.validate(model, submachines)
            return Result(issues.map { Triple(MachineFiles.oldLineOf(input.text, it.id), severity(it.severity), it.message) }, true)
        }
        try {
            Json.parse(input.text)
        } catch (_: JsonSyntaxException) {
            return null // reported by the JSON support
        }
        val read = Format.readModel(input.text)
        if (read.failed) return Result(read.problems!!.map { Triple(it.line, HighlightSeverity.ERROR, it.message) }, false)
        val model = read.model!!
        val submachines = ApplicationManager.getApplication().runReadAction(Computable { input.workspace.resolve(input.file, model).second })
        val issues = Validation.validate(model, submachines)
        return Result(issues.map { Triple(read.lines[it.id] ?: 1, severity(it.severity), it.message) }, false)
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for ((line, severity, message) in result.problems) {
            val l = (line - 1).coerceIn(0, maxOf(0, document.lineCount - 1))
            if (document.lineCount == 0) return
            val start = document.getLineStartOffset(l)
            val end = document.getLineEndOffset(l)
            val text = document.getText(TextRange(start, end))
            val first = start + (text.length - text.trimStart().length)
            val last = end - (text.length - text.trimEnd().length)
            holder.newAnnotation(severity, message).range(if (last > first) TextRange(first, last) else TextRange(start, end)).create()
        }
    }
}
