package com.vincegosoftware.fsmeditor.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.FsmIcons
import com.vincegosoftware.fsmeditor.MachineNameValidator
import com.vincegosoftware.fsmeditor.codegen.CodeGeneration
import com.vincegosoftware.fsmeditor.core.DiffPane
import com.vincegosoftware.fsmeditor.core.Format
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.Issue
import com.vincegosoftware.fsmeditor.core.MachineInfo
import com.vincegosoftware.fsmeditor.core.MachineState
import com.vincegosoftware.fsmeditor.core.PaneDiff
import com.vincegosoftware.fsmeditor.core.SubmachineInfo
import com.vincegosoftware.fsmeditor.core.TraceHighlight
import com.vincegosoftware.fsmeditor.core.TraceView
import com.vincegosoftware.fsmeditor.core.Validation
import com.vincegosoftware.fsmeditor.services.FsmWorkspace
import com.vincegosoftware.fsmeditor.trace.TracePlayerService
import java.io.IOException

/** What a pane does for the diagram diff and merge it is a side of (the diff viewer's or the file editor's). */
interface PaneSessionHost {
    fun navigate(pane: DiagramPane, dir: Int, key: String?)
    fun showLayout(pane: DiagramPane, show: Boolean) {}
    fun viewChanged(pane: DiagramPane, x: Double, y: Double, zoom: Double)
    fun choose(pane: DiagramPane, key: String?, side: String) {}
    fun complete(pane: DiagramPane) {}
}

/**
 * A diagram of a [DiagramSource]: the [EditorPanel] and what it asks of its host. Diagram edits go to the source, and
 * every new state of the source (an undo, the Text tab, a change on disk) is shown on the diagram; the diagram's own
 * edits aren't shown again, so its focus, typing and drags stay. After each change the machine is validated against
 * the other machines of the project. A pane can be a side of a diagram diff or merge ([DiffPane]).
 *
 * [file] is the machine's file (for a version, the file it is a version of): submachine paths resolve from it.
 */
class DiagramPane(
    private val project: Project,
    val source: DiagramSource,
    val file: VirtualFile,
    parent: Disposable,
    /** Undo and redo from the toolbar (the file editor's own, or the source's). */
    private val undoRedo: Pair<() -> Unit, () -> Unit> = { source.undo() } to { source.redo() },
) : EditorHost, DiffPane, Disposable {
    val panel = EditorPanel(this)
    private val syncAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var generation = 0
    /** The state the diagram shows (the last one shown, or made by its own edit). */
    private var shown: MachineState? = null
    private var shownDisplay: FsmModel? = null
    private var pendingReveal: String? = null
    private val notices = ArrayList<String>()
    private var trace: TraceView? = null
    private var traceIds: Map<String, String> = emptyMap()
    private var traceIdsOf: MachineState? = null
    var sessionHost: PaneSessionHost? = null
    /** The diagram diff or merge this pane is a side of, or null. */
    var session: Any? = null
    var onOpenAsText: (() -> Unit)? = null
    var onOpenTextMerge: (() -> Unit)? = null

    init {
        Disposer.register(parent, this)
        source.onChanged(this) { scheduleSync() }
        FsmWorkspace.getInstance(project).onAnyMachineChanged(this) { scheduleSync() }
        project.getServiceIfCreated(TracePlayerService::class.java)?.let { showTrace(it.viewFor(file.path)) }
        // Notices to show once the diagram is up: what opening found. Opening changes nothing on disk.
        val placed = source.state.placed
        if (placed > 0) notices.add("$placed element${if (placed == 1) " was" else "s were"} placed automatically: the layout file had no place for ${if (placed == 1) "it" else "them"}.")
        source.unreadableLayout?.let { notices.add("The layout file could not be read (${it.problem}): the elements were placed automatically. It is replaced only if you confirm when changing the diagram.") }
        if (source.old != null && !source.readOnly) notices.add("This file is in the old format (XMI): the first change writes the new format, a .fsm model file and a .fsm.layout layout file, and saving it converts the files linked to it by submachines.")
        if (source.layoutConflict && !source.readOnly) notices.add("The layout file has merge conflicts: the diagram uses the current positions, and the first change writes a clean layout file.")
        panel.canvas.onViewChanged {
            if (panel.diff != null && !panel.viewFromPartner) sessionHost?.viewChanged(this, panel.canvas.viewX, panel.canvas.viewY, panel.canvas.zoom)
        }
        scheduleSync()
    }

    fun scheduleSync() {
        if (disposed) return
        syncAlarm.cancelAllRequests()
        syncAlarm.addRequest(::sync, 30)
    }

    private class Resolved(val issues: List<Issue>, val submachines: Map<String, SubmachineInfo>, val machines: List<MachineInfo>)

    // ---------------------------------------------------------------- source → diagram

    private fun sync() {
        if (project.isDisposed) return
        val gen = ++generation
        val state = source.state
        val display = source.displayModel
        val model = display ?: state.model
        if (state.problems != null || model == null) {
            val error = state.problems?.takeIf { it.isNotEmpty() }?.joinToString("\n") { "Line ${it.line}: ${it.message}" } ?: "This file cannot be read."
            shown = null
            panel.update(null, emptyList(), error, null, null, source.mergeFallback)
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
                // The source changed meanwhile: showing this older state would take the diagram back to it.
                if (source.state !== state || source.displayModel !== display) {
                    scheduleSync()
                    return@finishOnUiThread
                }
                // The diagram's own edit coming back: it already shows it.
                val same = state === shown && display === shownDisplay
                shown = state
                shownDisplay = display
                panel.update(if (same) null else model.copy(), r.issues, null, r.submachines, r.machines)
                panel.setReadOnly(source.readOnly)
                trace?.let { panel.setTrace(mapTrace(it)) }
                while (notices.isNotEmpty()) panel.toast(notices.removeAt(0))
                pendingReveal?.let {
                    pendingReveal = null
                    panel.reveal(it)
                }
                sessionChanged?.invoke()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Runs after each sync: the diff or merge compares again. */
    var sessionChanged: (() -> Unit)? = null

    // ---------------------------------------------------------------- diagram → source

    override fun write(model: FsmModel): String? {
        val problem = source.edit(model)
        if (problem == null) {
            // The new state is the diagram's own: not shown again.
            shown = source.state
            shownDisplay = null
            return null
        }
        // Back to what the source holds.
        shown = null
        scheduleSync()
        return problem.ifEmpty { null }
    }

    override fun undo() = undoRedo.first()

    override fun redo() = undoRedo.second()

    override fun generateCode() = CodeGeneration.generate(project, file)

    override fun showTracePlayer() = TracePlayerService.getInstance(project).show()

    override fun exportSvg() {
        val svg = panel.exportSvg() ?: return
        FsmDocuments.saveSvg(project, file, svg)
    }

    override fun exportPng() {
        val image = panel.exportPng() ?: return
        FsmDocuments.savePng(project, file, image)
    }

    override fun openAsText() = onOpenAsText?.invoke() ?: FsmDocuments.openAsText(project, file)

    override fun openTextMerge() = onOpenTextMerge?.invoke() ?: FsmDocuments.openAsText(project, file)

    override fun openSubmachine(path: String) {
        if (path.isEmpty()) return
        if (!FsmDocuments.openSubmachine(project, file, path)) panel.toast("The referenced state machine file $path does not exist.")
    }

    override fun askSubmachineName(proposed: String, namesInside: Set<String>): String? {
        val folder = file.parent ?: return null
        var value = proposed
        var n = 2
        while (folder.findChild("$value.fsm") != null) value = proposed + n++
        return Messages.showInputDialog(
            project, "Name of the new state machine, saved next to this one:", "Extract to Submachine", FsmIcons.FILE, value, MachineNameValidator(folder, namesInside),
        )?.trim()
    }

    override fun createMachineFiles(name: String, modelText: String, layoutText: String): Boolean {
        val folder = file.parent ?: return false
        return try {
            WriteCommandAction.writeCommandAction(project).withName("Extract to Submachine").run<IOException> {
                VfsUtil.saveText(folder.createChildData(this@DiagramPane, "$name.fsm"), modelText)
                VfsUtil.saveText(folder.findChild("$name.fsm.layout") ?: folder.createChildData(this@DiagramPane, "$name.fsm.layout"), layoutText)
            }
            true
        } catch (ex: IOException) {
            Messages.showErrorDialog(project, "$name.fsm could not be written (${ex.message}). Nothing was changed.", "Extract to Submachine")
            false
        }
    }

    // ---------------------------------------------------------------- trace

    /** The Trace Player's highlights of this machine, or null. */
    fun showTrace(view: TraceView?) {
        trace = view
        panel.setTrace(view?.let(::mapTrace))
    }

    /**
     * The Trace Player's highlights with the ids of this diagram: the trace's ids are those reading the file gives
     * ([Format.derivedIds]), which elements created since the file was read, and an old file's (by their xmi:ids,
     * until it is saved converted), don't have.
     */
    private fun mapTrace(view: TraceView): TraceView {
        val state = source.state
        val model = state.model ?: return view
        if (traceIdsOf !== state) {
            traceIds = Format.derivedIds(model).entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() }
            traceIdsOf = state
        }
        val back = traceIds
        fun map(ids: List<String>) = ids.map { back[it] ?: it }
        return TraceView(view.instances.map { i ->
            TraceHighlight(map(i.active), map(i.taken), map(i.via), map(i.current), i.event).also {
                it.label = i.label
                it.color = i.color
            }
        })
    }

    // ---------------------------------------------------------------- diff and merge

    override fun currentModel(): FsmModel? = source.displayModel ?: source.state.model

    override fun showDiff(diff: PaneDiff?) = panel.showDiff(diff)

    override fun reveal(id: String?) {
        if (panel.session == null) pendingReveal = id ?: "" else panel.reveal(id ?: "")
    }

    override fun toast(message: String) = panel.toast(message)

    override fun diffNavigate(dir: Int, key: String?) {
        sessionHost?.navigate(this, dir, key)
    }

    override fun diffShowLayout(show: Boolean) {
        sessionHost?.showLayout(this, show)
    }

    override fun mergeChoose(key: String?, side: String) {
        sessionHost?.choose(this, key, side)
    }

    override fun mergeComplete() {
        sessionHost?.complete(this)
    }

    fun setView(x: Double, y: Double, zoom: Double) = panel.setViewFromPartner(x, y, zoom)

    private var disposed = false

    override fun dispose() {
        disposed = true
    }

    companion object {
        fun findFile(path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/'))
    }
}
