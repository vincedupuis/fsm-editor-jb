package com.vincegosoftware.fsmeditor.editor

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.DiffSession
import com.vincegosoftware.fsmeditor.core.Format
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.core.MachineTexts
import com.vincegosoftware.fsmeditor.core.Merge
import com.vincegosoftware.fsmeditor.services.Git
import java.io.File
import javax.swing.JComponent

/** A diagram diff: a machine file and its version in Git ([older], called [olderLabel]: `HEAD` or `Index`). */
class DiagramDiffRequest(val project: Project, val file: VirtualFile, val older: MachineTexts, val olderLabel: String) : DiffRequest() {
    override fun getTitle() = "${file.name} ($olderLabel) ↔ ${file.name}"
}

/** Shows [DiagramDiffRequest]s in the IDE's diff window (or tab): two diagrams side by side. */
class DiagramDiffTool : FrameDiffTool {
    override fun getName() = "Diagram"

    override fun canShow(context: DiffContext, request: DiffRequest) = request is DiagramDiffRequest

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer = DiagramDiffViewer(request as DiagramDiffRequest)
}

/**
 * The two diagrams of a diff: the older version on the left, read-only, and the file on the right, editable (its
 * documents, as in its Diagram tab). They are linked by Core's [DiffSession]: marks, the changes to step through, and a
 * view that scrolls and zooms together.
 */
class DiagramDiffViewer(request: DiagramDiffRequest) : FrameDiffTool.DiffViewer, PaneSessionHost {
    private val older: DiagramPane
    private val newer: DiagramPane
    private val session: DiffSession
    private val splitter = JBSplitter(false, 0.5f)
    /** What the panes and the file's source hang on: the diff window may dispose the viewer without the [Disposer]. */
    private val lifetime = Disposer.newDisposable("FSM diagram diff")

    init {
        val project = request.project
        val file = request.file
        older = DiagramPane(project, FixedSource(file.path, request.older), file, lifetime)
        val newerSource = FileSource(project, file, lifetime)
        newer = DiagramPane(project, newerSource, file, lifetime)
        // An older version without its layout file borrows the newer one's, for the elements with the same names.
        val olderSource = older.source
        val newerLayout = newerSource.state.layoutText
        if (olderSource.layoutMissing && olderSource.old == null && newerLayout != null && olderSource.state.problems == null) {
            val read = Format.readMachine(olderSource.state.modelText, newerLayout)
            if (!read.failed) {
                olderSource.displayModel = read.model
                olderSource.refresh()
                older.toast("This version has no layout file: its elements are drawn where the newer version has them.")
            }
        }
        session = DiffSession(older, newer, "${file.name} (${request.olderLabel})", file.name)
        for (p in listOf(older, newer)) {
            p.sessionHost = this
            p.sessionChanged = { session.update() }
        }
        splitter.firstComponent = older.panel
        splitter.secondComponent = newer.panel
        session.update()
    }

    override fun getComponent(): JComponent = splitter

    override fun getPreferredFocusedComponent(): JComponent = newer.panel.canvas

    override fun init(): FrameDiffTool.ToolbarComponents = FrameDiffTool.ToolbarComponents()

    override fun navigate(pane: DiagramPane, dir: Int, key: String?) = session.navigate(dir)

    override fun showLayout(pane: DiagramPane, show: Boolean) = session.setShowLayout(show)

    override fun viewChanged(pane: DiagramPane, x: Double, y: Double, zoom: Double) {
        (if (pane === older) newer else older).setView(x, y, zoom)
    }

    override fun dispose() {
        Disposer.dispose(lifetime)
    }

    companion object {
        /**
         * Compares a machine file with its version in Git, as diagrams: the file's changes not staged yet (the index's
         * version, on the left) when it has some, else its changes since the last commit (`HEAD`'s version).
         */
        fun compare(project: Project, file: VirtualFile) {
            val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return
            if (Merge.hasConflictMarkers(text)) {
                // A file with merge conflicts: its diagram is the merge.
                FsmDocuments.openInDiagram(project, file)
                return
            }
            val path = File(file.path)
            ApplicationManager.getApplication().executeOnPooledThread {
                val root = Git.rootOf(path)
                var label = "Index"
                var rev = ""
                var older = root?.let { Git.show(path, rev) }
                if (older != null && older.replace("\r\n", "\n") == text.replace("\r\n", "\n")) {
                    label = "HEAD"
                    rev = "HEAD"
                    older = Git.show(path, rev)
                }
                val layout = if (older != null) Git.show(File(MachineFiles.layoutPathOf(path.path)), rev) else null
                ApplicationManager.getApplication().invokeLater({
                    when {
                        root == null -> Messages.showWarningDialog(project, "${file.name} is not in a Git repository: there is no earlier version to compare it with.", "Compare as Diagrams")
                        older == null -> Messages.showWarningDialog(project, "${file.name} is untracked: Git has no earlier version to compare it with.", "Compare as Diagrams")
                        else -> DiffManager.getInstance().showDiff(project, DiagramDiffRequest(project, file, MachineTexts(older, layout), label))
                    }
                }, project.disposed)
            }
        }
    }
}
