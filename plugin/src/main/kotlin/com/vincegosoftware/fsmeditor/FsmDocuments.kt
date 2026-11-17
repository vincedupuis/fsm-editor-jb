package com.vincegosoftware.fsmeditor

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.json.JsonFileType
import com.intellij.json.JsonLanguage
import com.intellij.openapi.fileTypes.ex.FileTypeIdentifiableByVirtualFile
import com.intellij.lang.xml.XMLLanguage
import com.intellij.lang.xml.XMLParserDefinition
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
import com.vincegosoftware.fsmeditor.core.Hrefs
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.InvalidPathException
import javax.imageio.ImageIO
import javax.swing.Icon

object FsmIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/fsm.svg", FsmIcons::class.java)
}

/** The XML dialect of old `*.fsm` files (XMI), shown as XML in the Text tab. */
object FsmLanguage : XMLLanguage(XMLLanguage.INSTANCE, "FSM") {
    override fun getDisplayName() = "FSM State Machine (XMI)"
}

/** Parses old `*.fsm` files as XML. */
class FsmParserDefinition : XMLParserDefinition() {
    override fun getFileNodeType() = FILE

    override fun createFile(viewProvider: FileViewProvider) = XmlFileImpl(viewProvider, FILE)

    companion object {
        private val FILE = IFileElementType(FsmLanguage)
    }
}

/**
 * `*.fsm` files: the model of a state machine, JSON, shown in the Text tab with the JSON editor and the format's
 * schema (`FsmSchemas`). Its layout file, `*.fsm.layout`, is plain JSON.
 */
object FsmFileType : JsonFileType(JsonLanguage.INSTANCE) {
    override fun getName() = "FSM State Machine"
    override fun getDescription() = "UML state machine"
    override fun getDefaultExtension() = "fsm"
    override fun getIcon(): Icon = FsmIcons.FILE
}

/** `*.fsm` files of earlier versions (XMI 2.5.1 with UML DI), told apart by their content: shown as XML in the Text tab. */
object OldFsmFileType : XmlLikeFileType(FsmLanguage), FileTypeIdentifiableByVirtualFile {
    override fun getName() = "FSM State Machine (XMI)"
    override fun getDescription() = "UML state machine of an earlier version (XMI)"
    override fun getDefaultExtension() = "fsm"
    override fun getIcon(): Icon = FsmIcons.FILE

    override fun isMyFileType(file: VirtualFile): Boolean {
        if (file.isDirectory || !file.name.endsWith(".fsm", ignoreCase = true) || file.length == 0L) return false
        return try {
            val head = file.inputStream.use { it.readNBytes(64) }.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            head.startsWith("<")
        } catch (_: IOException) {
            false
        }
    }
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

    fun isLayout(file: VirtualFile?): Boolean = file != null && !file.isDirectory && file.name.endsWith(".fsm.layout", ignoreCase = true)

    /** Opens the machine a submachine state of [from] references (its file path, relative to [from]); false when the file doesn't exist. */
    fun openSubmachine(project: Project, from: VirtualFile, ref: String): Boolean {
        val path = try {
            Hrefs.resolvePath(from.path, ref)
        } catch (_: InvalidPathException) {
            return false // not a file path: a file that doesn't exist
        }
        val target = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/'))
        if (target == null || target.isDirectory) return false
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

    /** Asks where to save a PNG image of [file] and writes [image]. */
    fun savePng(project: Project, file: VirtualFile, image: BufferedImage) {
        val descriptor = FileSaverDescriptor("Export as PNG", "Save the diagram as a PNG image")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, file.nameWithoutExtension + ".png") ?: return
        try {
            if (!ImageIO.write(image, "png", target.file)) throw IOException("No PNG writer is available.")
            target.getVirtualFile(true)
            WindowManager.getInstance().getStatusBar(project)?.info = "Exported to ${target.file.name}."
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "PNG Export Failed")
        }
    }
}
