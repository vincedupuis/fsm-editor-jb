package com.vincegosoftware.fsmeditor.editor

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.DocumentReferenceProvider
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.codegen.CodeGeneration
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.Issue
import com.vincegosoftware.fsmeditor.core.MachineInfo
import com.vincegosoftware.fsmeditor.core.SubmachineInfo
import com.vincegosoftware.fsmeditor.core.Validation
import com.vincegosoftware.fsmeditor.core.XmiException
import com.vincegosoftware.fsmeditor.core.XmlParseException
import com.vincegosoftware.fsmeditor.core.Xmi
import com.vincegosoftware.fsmeditor.services.FsmWorkspace
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import kotlin.math.min

/**
 * The diagram tab of a state machine file. The IDE's document holds the XMI
 * (the Text tab edits the same document); the diagram works on the parsed
 * model. Diagram edits are written back as one undoable command, and document
 * changes (undo, the Text tab, a reload) are parsed and shown on the diagram.
 * After each change the machine is validated against the other machines of
 * the project.
 */
class FsmFileEditor(private val project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor, DocumentReferenceProvider, EditorHost {

    private val document: Document = FileDocumentManager.getInstance().getDocument(file)
        ?: throw IllegalStateException("No document for ${file.path}")
    private val panel = EditorPanel(this)
    private val syncAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var generation = 0
    /** The last XMI this editor wrote, with the model it came from. */
    private var echoText: String? = null
    private var echoModel: FsmModel? = null

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleSync()
        }, this)
        FsmWorkspace.getInstance(project).onAnyMachineChanged(this) { scheduleSync() }
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { panel.themeChanged() })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { panel.themeChanged() })
        scheduleSync()
    }

    private fun scheduleSync() {
        syncAlarm.cancelAllRequests()
        syncAlarm.addRequest(::sync, 30)
    }

    // ---------------------------------------------------------------- document → diagram

    private class Resolved(val issues: List<Issue>, val submachines: Map<String, SubmachineInfo>, val machines: List<MachineInfo>)

    private fun sync() {
        if (project.isDisposed) return
        val gen = ++generation
        val text = document.text
        val echo = echoText != null && echoText == text
        var model: FsmModel? = null
        var error: String? = null
        try {
            // Our own edit coming back: keep the model the diagram already has.
            model = when {
                echo -> echoModel!!.copy()
                text.isBlank() -> FsmModel.createDefault(file.nameWithoutExtension)
                else -> Xmi.fromXmi(text)
            }
        } catch (e: XmlParseException) {
            error = e.message
        } catch (e: XmiException) {
            error = e.message
        }
        if (model == null) {
            panel.update(null, emptyList(), error, null, null)
            return
        }
        val snapshot = model.copy()
        val workspace = FsmWorkspace.getInstance(project)
        ReadAction.nonBlocking<Resolved> {
            val resolved = workspace.resolve(file, snapshot)
            Resolved(Validation.validate(snapshot, resolved.second), resolved.second, resolved.first)
        }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { r ->
                if (gen != generation) return@finishOnUiThread // a newer sync is on its way
                panel.update(if (echo) null else model, r.issues, null, r.submachines, r.machines)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    // ---------------------------------------------------------------- diagram → document

    override fun write(model: FsmModel) {
        val old = document.text
        val xmi = Xmi.toXmi(model)
        echoText = xmi
        echoModel = model.copy()
        if (xmi == old) return
        if (!document.isWritable && ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file)).hasReadonlyFiles()) {
            panel.toast("The file is read-only; the change was not saved.")
            echoText = null
            scheduleSync()
            return
        }
        // Replace only what changed, as one undoable command.
        var prefix = 0
        val max = min(old.length, xmi.length)
        while (prefix < max && old[prefix] == xmi[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == xmi[xmi.length - 1 - suffix]) suffix++
        WriteCommandAction.writeCommandAction(project).withName("Edit State Machine").run<RuntimeException> {
            document.replaceString(prefix, old.length - suffix, xmi.substring(prefix, xmi.length - suffix))
        }
    }

    override fun undo() {
        val undo = UndoManager.getInstance(project)
        if (undo.isUndoAvailable(this)) undo.undo(this)
    }

    override fun redo() {
        val undo = UndoManager.getInstance(project)
        if (undo.isRedoAvailable(this)) undo.redo(this)
    }

    override fun generateCode() = CodeGeneration.generate(project, file)

    override fun exportSvg() {
        val svg = panel.exportSvg() ?: return
        FsmDocuments.saveSvg(project, file, svg)
    }

    override fun openAsText() = FsmDocuments.openAsText(project, file)

    override fun openSubmachine(href: String) {
        if (href.isEmpty()) return
        if (!FsmDocuments.openHref(project, file, href)) {
            panel.toast("The referenced state machine file ${href.split('#')[0]} does not exist.")
        }
    }

    // ---------------------------------------------------------------- FileEditor

    override fun getDocumentReferences(): Collection<DocumentReference> =
        listOf(DocumentReferenceManager.getInstance().create(document))

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.canvas

    override fun getName() = "Diagram"

    override fun setState(state: FileEditorState) {}

    override fun isModified() = FileDocumentManager.getInstance().isFileModified(file)

    override fun isValid() = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getFile(): VirtualFile = file

    override fun dispose() {}
}
