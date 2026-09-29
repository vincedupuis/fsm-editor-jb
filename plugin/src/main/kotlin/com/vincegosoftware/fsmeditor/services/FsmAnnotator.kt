package com.vincegosoftware.fsmeditor.services

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.Issue
import com.vincegosoftware.fsmeditor.core.Severity
import com.vincegosoftware.fsmeditor.core.Validation
import com.vincegosoftware.fsmeditor.core.XmiException
import com.vincegosoftware.fsmeditor.core.XmlParseException
import com.vincegosoftware.fsmeditor.core.Xmi

/**
 * Validation results of a state machine file in its Text tab and in the
 * Problems tool window: each issue marks the `xmi:id` of the offending element.
 */
class FsmAnnotator : ExternalAnnotator<FsmAnnotator.Input, FsmAnnotator.Result>(), DumbAware {
    class Input(val file: VirtualFile, val text: String, val workspace: FsmWorkspace)

    class Result(val issues: List<Issue>, val error: String?)

    override fun collectInformation(file: PsiFile): Input? {
        val vf = file.virtualFile ?: return null
        if (!FsmDocuments.isFsm(vf)) return null
        return Input(vf, file.text, FsmWorkspace.getInstance(file.project))
    }

    override fun doAnnotate(input: Input): Result {
        if (input.text.isBlank()) return Result(emptyList(), null)
        val model = try {
            Xmi.fromXmi(input.text)
        } catch (e: XmlParseException) {
            // Malformed XML is already reported by the XML support.
            return Result(emptyList(), null)
        } catch (e: XmiException) {
            return Result(emptyList(), e.message)
        }
        val submachines = ApplicationManager.getApplication().runReadAction(Computable { input.workspace.resolve(input.file, model).second })
        return Result(Validation.validate(model, submachines), null)
    }

    override fun apply(file: PsiFile, result: Result, holder: AnnotationHolder) {
        val text = file.text
        if (result.error != null) {
            holder.newAnnotation(HighlightSeverity.ERROR, result.error).range(firstElement(text)).create()
            return
        }
        for (issue in result.issues) {
            val severity = when (issue.severity) {
                Severity.Error -> HighlightSeverity.ERROR
                Severity.Warning -> HighlightSeverity.WARNING
                Severity.Info -> HighlightSeverity.WEAK_WARNING
            }
            holder.newAnnotation(severity, issue.message).range(rangeOf(text, issue.id)).create()
        }
    }

    /** The value of the `xmi:id` attribute of element [id], or the state machine's name when [id] is empty. */
    private fun rangeOf(text: String, id: String): TextRange {
        if (id.isNotEmpty()) {
            val needle = "xmi:id=\"$id\""
            val i = text.indexOf(needle)
            if (i >= 0) return TextRange(i + 8, i + needle.length - 1)
        }
        val machine = Regex("xmi:type=\"uml:(?:Protocol)?StateMachine\"").find(text)
        if (machine != null) return TextRange(machine.range.first, machine.range.last + 1)
        return firstElement(text)
    }

    private fun firstElement(text: String): TextRange {
        val start = Regex("<[A-Za-z]").find(text)?.range?.first ?: 0
        val end = text.indexOf('>', start).takeIf { it >= 0 }?.plus(1) ?: text.length
        return TextRange(start, maxOf(start, end))
    }
}
