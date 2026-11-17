package com.vincegosoftware.fsmeditor.editor

import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.DocumentReferenceProvider
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.Format
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.MachineTexts
import com.vincegosoftware.fsmeditor.core.MergeHost
import com.vincegosoftware.fsmeditor.core.MergeSession
import com.vincegosoftware.fsmeditor.core.Merge
import com.vincegosoftware.fsmeditor.core.TraceView
import com.vincegosoftware.fsmeditor.services.Git
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * The Diagram tab of a state machine file. The machine is two files, the model and its layout: the IDE's documents
 * hold their texts (the Text tab edits the model's), and the diagram edits both as one undoable command ([FileSource]).
 * Saving, the modified mark and undo/redo are the IDE's, over both documents.
 *
 * A file with merge conflicts opens as the diagram merge: the result (this file, starting as the current side) beside
 * the incoming and current sides, read-only, until Complete Merge.
 */
class FsmFileEditor(private val project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor, DocumentReferenceProvider, PaneSessionHost {

    val source = FileSource(project, file, this)
    val pane = DiagramPane(project, source, file, this, { undo() } to { redo() })
    private val root = JPanel(BorderLayout())
    private var merge: MergeSession? = null
    private val sides = ArrayList<DiagramPane>()

    init {
        root.add(pane.panel, BorderLayout.CENTER)
        pane.sessionHost = this
        pane.onOpenTextMerge = ::openTextMerge
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { themeChanged() })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { themeChanged() })
        // A file with merge conflicts opens as the result of the diagram merge, its two sides beside it.
        if (source.merge != null && !source.readOnly) ApplicationManager.getApplication().invokeLater({ openMerge() }, project.disposed)
    }

    private fun themeChanged() {
        pane.panel.themeChanged()
        sides.forEach { it.panel.themeChanged() }
    }

    /** The Trace Player's highlights of this machine, or null. */
    fun showTrace(view: TraceView?) = pane.showTrace(view)

    fun exportPng() = pane.exportPng()

    fun undo() {
        val undo = UndoManager.getInstance(project)
        if (undo.isUndoAvailable(this)) undo.undo(this)
    }

    fun redo() {
        val undo = UndoManager.getInstance(project)
        if (undo.isRedoAvailable(this)) undo.redo(this)
    }

    /** The IDE's merge of the file's text (Git's conflict resolution), for conflicts the diagram merge can't open. */
    private fun openTextMerge() {
        val provider = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)?.mergeProvider
        if (provider != null) AbstractVcsHelper.getInstance(project).showMergeDialog(listOf(file), provider)
        else FsmDocuments.openAsText(project, file)
    }

    // ---------------------------------------------------------------- merge

    private fun openMerge() {
        val m = source.merge ?: return
        if (merge != null || disposed) return
        val incoming = DiagramPane(project, FixedSource(file.path, m.incoming), file, this)
        val current = DiagramPane(project, FixedSource(file.path, m.current), file, this)
        val read = { t: MachineTexts -> Format.readMachine(t.model, t.layout).model }
        val session = MergeSession(file.path, m.current, m.incoming, m.base, read, object : MergeHost {
            override fun edit(model: FsmModel, label: String): String? = source.edit(model)?.ifEmpty { null }
            override fun state(): Any = source.state
        }) { source.state.model?.copy() }
        merge = session
        for (p in listOf(incoming, current)) {
            p.sessionHost = this
            p.sessionChanged = { session.update() }
            sides.add(p)
        }
        pane.sessionChanged = { session.update() }
        session.attach(incoming, "incoming")
        session.attach(current, "current")
        session.attach(pane, "result")
        root.removeAll()
        root.add(JBSplitter(false, 0.34f).apply {
            firstComponent = pane.panel
            secondComponent = JBSplitter(false, 0.5f).apply {
                firstComponent = incoming.panel
                secondComponent = current.panel
            }
        }, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
        if (session.conflictCount == 0) pane.toast("The two sides of the conflicts describe the same machine.")
    }

    /** Complete Merge: writes the result (without markers), saves both files, marks them resolved in Git, and closes the sides. */
    override fun complete(pane: DiagramPane) {
        if (merge == null) return
        // Nothing chosen: the documents still hold the markers, and the result is the current side.
        if (Merge.hasConflictMarkers(FileDocumentManager.getInstance().getDocument(file)?.text)) source.writeTexts(source.state)
        source.save()
        // A save that failed leaves the merge as it is: the file keeps its conflicts on disk.
        if (source.documents().any { FileDocumentManager.getInstance().isDocumentUnsaved(it) }) {
            NotificationGroupManager.getInstance().getNotificationGroup("FSM Editor")
                .createNotification("${file.name}: the merge could not be completed, the files could not be saved. The file keeps its conflicts.", NotificationType.ERROR).notify(project)
            return
        }
        val layout = source.layoutFile()
        val files = listOfNotNull(File(file.path), layout?.let { File(it.path) })
        val name = file.name
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = Git.rootOf(files[0]) == null || Git.add(*files.toTypedArray())
            ApplicationManager.getApplication().invokeLater({
                file.refresh(true, false)
                val group = NotificationGroupManager.getInstance().getNotificationGroup("FSM Editor")
                if (!resolved) group.createNotification("The merge was saved, but could not be marked resolved in Git.", NotificationType.WARNING).notify(project)
                else group.createNotification("$name: merge completed.", NotificationType.INFORMATION).notify(project)
            }, project.disposed)
        }
        endMerge()
    }

    private fun endMerge() {
        merge = null
        source.merge = null
        pane.sessionChanged = null
        for (p in sides) Disposer.dispose(p)
        sides.clear()
        pane.showDiff(null)
        root.removeAll()
        root.add(pane.panel, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    override fun navigate(pane: DiagramPane, dir: Int, key: String?) {
        merge?.navigate(dir, key)
    }

    override fun choose(pane: DiagramPane, key: String?, side: String) {
        merge?.choose(key, side)
    }

    override fun viewChanged(pane: DiagramPane, x: Double, y: Double, zoom: Double) {
        for (p in sides + this.pane) if (p !== pane) p.setView(x, y, zoom)
    }

    // ---------------------------------------------------------------- FileEditor

    override fun getDocumentReferences(): Collection<DocumentReference> =
        source.documents().map { DocumentReferenceManager.getInstance().create(it) }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = pane.panel.canvas

    override fun getName() = "Diagram"

    override fun setState(state: FileEditorState) {}

    override fun isModified() = source.documents().any { FileDocumentManager.getInstance().isDocumentUnsaved(it) }

    override fun isValid() = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getFile(): VirtualFile = file

    private var disposed = false

    override fun dispose() {
        disposed = true
    }
}
