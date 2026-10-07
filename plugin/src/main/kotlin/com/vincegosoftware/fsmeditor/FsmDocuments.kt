package com.vincegosoftware.fsmeditor

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.ide.scratch.ScratchFileCreationHelper
import com.intellij.lang.xml.XMLLanguage
import com.intellij.lang.xml.XMLParserDefinition
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.FileViewProvider
import com.intellij.psi.impl.source.xml.XmlFileImpl
import com.intellij.psi.tree.IFileElementType
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.Hrefs
import com.vincegosoftware.fsmeditor.core.Xmi
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import java.io.IOException
import javax.swing.Icon

object FsmIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/fsm.svg", FsmIcons::class.java)
}

/**
 * The XML dialect of `*.fsm` files. A language of its own (rather than XML)
 * lists state machines in File › New › Scratch File.
 */
object FsmLanguage : XMLLanguage(XMLLanguage.INSTANCE, "FSM") {
    override fun getDisplayName() = "FSM State Machine"
}

/** Parses `*.fsm` files as XML. */
class FsmParserDefinition : XMLParserDefinition() {
    override fun getFileNodeType() = FILE

    override fun createFile(viewProvider: FileViewProvider) = XmlFileImpl(viewProvider, FILE)

    companion object {
        private val FILE = IFileElementType(FsmLanguage)
    }
}

/**
 * New FSM scratch files start with the same machine as File › New › State Machine,
 * in the diagram: without a caret offset the file opens in its first editor, not the text.
 */
class FsmScratchCreationHelper : ScratchFileCreationHelper() {
    override fun prepareText(project: Project, context: Context, dataContext: DataContext): Boolean {
        if (!context.text.isNullOrBlank()) return false
        context.text = Xmi.toXmi(FsmModel.createDefault("StateMachine"))
        context.caretOffset = -1
        return true
    }
}

/** `*.fsm` files: XMI documents, shown as XML in the Text tab. */
object FsmFileType : XmlLikeFileType(FsmLanguage) {
    override fun getName() = "FSM State Machine"
    override fun getDescription() = "UML state machine (XMI)"
    override fun getDefaultExtension() = "fsm"
    override fun getIcon(): Icon = FsmIcons.FILE
}

/** Opening state machine files in the diagram or as text. */
object FsmDocuments {
    const val DIAGRAM_EDITOR_ID = "fsm-diagram"
    private const val TEXT_EDITOR_ID = "text-editor"

    fun isFsm(file: VirtualFile?): Boolean = file != null && !file.isDirectory && file.extension.equals("fsm", ignoreCase = true)

    fun openInDiagram(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        manager.setSelectedEditor(file, DIAGRAM_EDITOR_ID)
    }

    fun openAsText(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        manager.setSelectedEditor(file, TEXT_EDITOR_ID)
    }

    /** Whether the diagram of [file] is the selected tab. */
    fun isDiagramSelected(project: Project, file: VirtualFile): Boolean =
        FileEditorManagerEx.getInstanceEx(project).getSelectedEditor(file) is FsmFileEditor

    /** Opens the machine a submachine href of [from] points to; false when the file doesn't exist. */
    fun openHref(project: Project, from: VirtualFile, href: String): Boolean {
        val (path, _) = Hrefs.resolve(from.path, href)
        val target = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/')) ?: return false
        openInDiagram(project, target)
        return true
    }

    /** Asks where to save an SVG drawing of [file] and writes it. */
    fun saveSvg(project: Project, file: VirtualFile, svg: String) {
        val descriptor = FileSaverDescriptor("Export as SVG", "Save the diagram as an SVG image")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, file.nameWithoutExtension + ".svg") ?: return
        try {
            target.file.writeText(svg, Charsets.UTF_8)
            target.getVirtualFile(true)
            WindowManager.getInstance().getStatusBar(project)?.info = "Exported to ${target.file.name}."
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "SVG Export Failed")
        }
    }
}
