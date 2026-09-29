package com.vincegosoftware.fsmeditor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.vincegosoftware.fsmeditor.codegen.CodeGeneration
import com.vincegosoftware.fsmeditor.core.DiagramSession
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.SvgExport
import com.vincegosoftware.fsmeditor.core.XmiException
import com.vincegosoftware.fsmeditor.core.XmlParseException
import com.vincegosoftware.fsmeditor.core.Xmi
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import java.io.IOException

/** An action on the selected or open state machine file. */
abstract class FsmFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected fun fileOf(e: AnActionEvent): VirtualFile? {
        (e.getData(PlatformDataKeys.FILE_EDITOR) as? FsmFileEditor)?.let { return it.file }
        return e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { FsmDocuments.isFsm(it) }
    }

    override fun update(e: AnActionEvent) {
        val ok = e.project != null && fileOf(e) != null && isApplicable(e)
        // In context menus, only show up for state machines.
        e.presentation.isEnabled = ok
        e.presentation.isVisible = ok || !e.isFromContextMenu
    }

    protected open fun isApplicable(e: AnActionEvent) = true
}

class GenerateCodeAction : FsmFileAction() {
    override fun actionPerformed(e: AnActionEvent) {
        CodeGeneration.generate(e.project ?: return, fileOf(e))
    }
}

class ExportSvgAction : FsmFileAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = fileOf(e) ?: return
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return
        val model = try {
            if (text.isBlank()) FsmModel.createDefault(file.nameWithoutExtension) else Xmi.fromXmi(text)
        } catch (ex: XmlParseException) {
            Messages.showErrorDialog(project, "${file.name} is not a readable state machine: ${ex.message}", "Export as SVG")
            return
        } catch (ex: XmiException) {
            Messages.showErrorDialog(project, "${file.name} is not a readable state machine: ${ex.message}", "Export as SVG")
            return
        }
        val session = DiagramSession(model)
        (e.getData(PlatformDataKeys.FILE_EDITOR) as? FsmFileEditor)?.let { editor ->
            // Names of submachines as the open diagram knows them.
            val open = editor.component as? com.vincegosoftware.fsmeditor.editor.EditorPanel
            open?.session?.let {
                session.submachines = it.submachines
                session.machines = it.machines
            }
        }
        FsmDocuments.saveSvg(project, file, SvgExport.export(session))
    }
}

class OpenAsTextAction : FsmFileAction() {
    override fun isApplicable(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val file = fileOf(e) ?: return false
        return e.getData(PlatformDataKeys.FILE_EDITOR) is FsmFileEditor || !FsmDocuments.isDiagramSelected(project, file) || !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        FsmDocuments.openAsText(e.project ?: return, fileOf(e) ?: return)
    }
}

class OpenInDiagramAction : FsmFileAction() {
    override fun isApplicable(e: AnActionEvent): Boolean = e.getData(PlatformDataKeys.FILE_EDITOR) !is FsmFileEditor

    override fun actionPerformed(e: AnActionEvent) {
        FsmDocuments.openInDiagram(e.project ?: return, fileOf(e) ?: return)
    }
}

/** File › New › State Machine: a new .fsm file with an initial pseudostate and one state. */
class NewStateMachineAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        var folder = e.getData(LangDataKeys.IDE_VIEW)?.orChooseDirectory?.virtualFile
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { if (it.isDirectory) it else it.parent }
        if (folder == null) {
            val start = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            folder = FileChooser.chooseFile(FileChooserDescriptor(false, true, false, false, false, false).withTitle("Create the State Machine In"), project, start)
                ?: return
        }
        val target = folder
        val name = Messages.showInputDialog(project, "Name of the new state machine:", "New State Machine", FsmIcons.FILE, "StateMachine", NameValidator(target))
            ?.trim() ?: return
        create(project, target, name)?.let { FsmDocuments.openInDiagram(project, it) }
    }

    private fun create(project: Project, folder: VirtualFile, name: String): VirtualFile? =
        try {
            WriteCommandAction.writeCommandAction(project).withName("New State Machine").compute<VirtualFile, IOException> {
                val file = folder.createChildData(this, "$name.fsm")
                VfsUtil.saveText(file, Xmi.toXmi(FsmModel.createDefault(name)))
                file
            }
        } catch (ex: IOException) {
            Messages.showErrorDialog(project, ex.message ?: ex.toString(), "New State Machine")
            null
        }

    private class NameValidator(private val folder: VirtualFile) : InputValidatorEx {
        override fun getErrorText(inputString: String): String? {
            val name = inputString.trim()
            if (!Regex("^[\\w .-]+$").matches(name)) return "Use letters, digits, spaces, dots, dashes or underscores."
            if (folder.findChild("$name.fsm") != null) return "$name.fsm already exists."
            return null
        }

        override fun checkInput(inputString: String) = getErrorText(inputString) == null

        override fun canClose(inputString: String) = checkInput(inputString)
    }
}
