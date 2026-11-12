package com.vincegosoftware.fsmeditor.trace

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.TraceLog
import com.vincegosoftware.fsmeditor.core.TraceMachine
import com.vincegosoftware.fsmeditor.core.TraceSession
import com.vincegosoftware.fsmeditor.core.TraceView
import com.vincegosoftware.fsmeditor.core.Xmi
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import com.vincegosoftware.fsmeditor.services.FsmWorkspace
import com.vincegosoftware.fsmeditor.settings.FsmSettings
import java.io.File
import javax.swing.Timer

/**
 * The Trace Player, as src/trace/player.ts of the VS Code edition: the [TraceSession] of the open logs, each followed
 * as it grows (its size and modification time are checked twice a second); the machines of the project and the open
 * documents for it to resolve records; playing; the highlights it hands to the open diagrams; and the questions it asks
 * (which machine the lines of a log naming none are about). UI thread only.
 */
@Service(Service.Level.PROJECT)
class TracePlayerService(private val project: Project) : Disposable {
    val session = TraceSession()
    private val listeners = mutableListOf<() -> Unit>()
    private var views: Map<String, TraceView> = emptyMap()
    /** Size and modification time of each log when it was last read. */
    private val stamps = HashMap<TraceLog, Pair<Long, Long>>()
    private val follow = Timer(500) { poll() }
    private val playTimer = Timer(0) { tick() }.apply { isRepeats = false }
    private val refreshTimer = Timer(200) { refresh() }.apply { isRepeats = false }
    /** Parsed machine files, with the version they were read from. */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, TraceMachine>>()
    private var machinesDirty = true
    /** The machines are being read in the background. */
    private var loading = false
    private var watching = false
    private var asking = false
    private val toAsk = ArrayDeque<TraceLog>()

    /** Runs [listener] when the tool window should show the session again. */
    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** The highlights of a machine file at the cursor, or null. */
    fun viewFor(path: String): TraceView? = views[session.viewKey(path)]

    // ---------------------------------------------------------------- logs

    /** "Open Trace…": asks for log files (unless given), opens them and shows the tool window. */
    fun open(files: List<String>? = null) {
        val list = files ?: FileChooser.chooseFiles(
            FileChooserDescriptor(true, false, false, false, false, true).withTitle("Open Trace: Log Files"),
            project, null,
        ).map { it.path }
        if (list.isEmpty()) return
        if (!watching) {
            watching = true
            FsmWorkspace.getInstance(project).onAnyMachineChanged(this) { machinesChanged() }
        }
        session.setFormats(FsmSettings.getInstance().state.traceFormats)
        val added = list.mapNotNull { session.open(it) }
        show()
        if (added.isEmpty()) return
        follow.start()
        refresh()
        for (trace in added) read(trace)
        update()
    }

    fun show() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.show()
    }

    /** Reads the logs that changed on disk. */
    private fun poll() {
        var any = false
        for (trace in session.traces.toList()) {
            val file = File(trace.path)
            val stamp = if (file.exists()) file.length() to file.lastModified() else -1L to -1L
            if (stamps[trace] == stamp) continue
            if (stamp.first < 0) session.missing(trace) else read(trace)
            stamps[trace] = stamp
            any = true
        }
        if (any) update()
    }

    /** Reads what was added to a log, then asks for its machine when its lines name none. */
    private fun read(trace: TraceLog) {
        val file = File(trace.path)
        stamps[trace] = if (file.exists()) file.length() to file.lastModified() else -1L to -1L
        session.readNew(trace)
        if (session.shouldAsk(trace)) askMachine(trace)
    }

    fun close(trace: TraceLog) {
        stamps.remove(trace)
        session.close(trace)
        if (session.traces.isEmpty()) {
            follow.stop()
            playTimer.stop()
        }
        update()
    }

    /** Reads a log again from the start with another format. */
    fun setFormat(trace: TraceLog, name: String) {
        if (!session.setFormat(trace, name)) return
        read(trace)
        session.rebuild()
        update()
    }

    /** The setting changed: the formats are read again. */
    fun formatsChanged() {
        session.setFormats(FsmSettings.getInstance().state.traceFormats)
        changed()
    }

    /** Asks which machine the lines of [trace] naming none are about (one question at a time, once the machines are read). */
    fun askMachine(trace: TraceLog) {
        toAsk.addLast(trace)
        askNext()
    }

    private fun askNext() {
        if (asking || loading || toAsk.isEmpty()) return
        if (machinesDirty) {
            refresh() // asks when the machines are read
            return
        }
        asking = true
        try {
            while (toAsk.isNotEmpty()) {
                val t = toAsk.removeFirst()
                if (t !in session.traces) continue
                val open = openFiles()
                val workspace = FsmWorkspace.getInstance(project)
                val machines = session.machines.sortedWith(compareBy<TraceMachine> { it.file !in open }.thenBy { it.model.name.lowercase() })
                if (machines.isEmpty()) {
                    Messages.showWarningDialog(project, "${t.name} has lines without a state machine, and the project has none.", "Trace Player")
                    continue
                }
                val pick = MachinePicker(
                    project, "State machine of ${t.name}", "Its lines name no state machine: which one are they about?",
                    machines.map { Triple(it.model.name, workspace.display(it.file), it.file in open) },
                    machines.indexOfFirst { it.file == t.machineFile },
                ).choose()
                if (pick < 0 || t !in session.traces) continue
                session.chooseMachine(t, machines[pick].file)
                update()
            }
        } finally {
            asking = false
        }
    }

    // ---------------------------------------------------------------- machines

    private fun machinesChanged() {
        machinesDirty = true
        if (session.traces.isNotEmpty()) refreshTimer.restart()
    }

    /** Rebuilds the timeline, after reading the machine files again when they changed. */
    private fun refresh() {
        if (machinesDirty) {
            loadMachines()
            return
        }
        session.rebuild()
        update()
    }

    /** The .fsm files open in editors. */
    private fun openMachineFiles(): List<VirtualFile> = FileEditorManager.getInstance(project).openFiles.filter { FsmDocuments.isFsm(it) }

    /** Paths of the .fsm files open in editors (as [TraceMachine.file]). */
    private fun openFiles(): Set<String> = openMachineFiles().map { File(it.path).absolutePath }.toSet()

    /**
     * Reads the machines of the project and the open ones (with their unsaved changes), the open ones first, in the
     * background (searching the project is too slow for the UI thread); then rebuilds the timeline and asks the
     * questions that waited for them.
     */
    private fun loadMachines() {
        machinesDirty = false
        loading = true
        val open = openMachineFiles()
        val workspace = FsmWorkspace.getInstance(project)
        ReadAction.nonBlocking<List<TraceMachine>> {
            val files = LinkedHashSet<VirtualFile>(open)
            files.addAll(workspace.findMachineFiles())
            files.mapNotNull { readMachine(it) }
        }
            .expireWith(this)
            .coalesceBy(this, "machines")
            .finishOnUiThread(ModalityState.nonModal()) { machines ->
                loading = false
                session.setMachines(machines) { path ->
                    LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/'))?.let { readMachine(it) }
                }
                if (machinesDirty) {
                    loadMachines() // changed again meanwhile
                    return@finishOnUiThread
                }
                session.rebuild()
                update()
                askNext()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun readMachine(vf: VirtualFile): TraceMachine? {
        return ReadAction.compute<TraceMachine?, RuntimeException> {
            try {
                val document = FileDocumentManager.getInstance().getCachedDocument(vf)
                val key = if (document != null) "d${document.modificationStamp}" else "f${vf.modificationStamp}:${vf.timeStamp}"
                parsed[vf.path]?.let { if (it.first == key) return@compute it.second }
                val machine = TraceMachine(File(vf.path).absolutePath, Xmi.fromXmi(document?.text ?: VfsUtilCore.loadText(vf)))
                parsed[vf.path] = key to machine
                machine
            } catch (_: Exception) {
                null
            }
        }
    }

    // ---------------------------------------------------------------- cursor

    fun seek(index: Int) {
        session.seek(index)
        update()
    }

    fun step(delta: Int) {
        session.step(delta)
        update()
    }

    fun first() {
        session.first()
        update()
    }

    fun play() {
        session.play()
        update()
        schedule()
    }

    fun pause() {
        session.pause()
        playTimer.stop()
        update()
    }

    fun goLive() {
        playTimer.stop()
        session.goLive()
        update()
    }

    fun setSpeed(speed: Double) {
        if (speed > 0) session.speed = speed
        changed()
    }

    private fun tick() {
        session.advance()
        update()
        schedule()
    }

    private fun schedule() {
        playTimer.stop()
        val delay = session.nextDelay()
        if (delay == null) {
            changed()
            return
        }
        playTimer.initialDelay = delay.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        playTimer.start()
    }

    /** Highlights at the cursor for the diagrams, and the tool window. */
    private fun update() {
        if (!session.playing) playTimer.stop()
        val before = views
        views = session.views()
        if (before.isNotEmpty() || views.isNotEmpty()) {
            for (editor in FileEditorManager.getInstance(project).allEditors.filterIsInstance<FsmFileEditor>()) editor.showTrace(viewFor(editor.file.path))
        }
        changed()
    }

    override fun dispose() {
        follow.stop()
        playTimer.stop()
        refreshTimer.stop()
    }

    companion object {
        const val TOOL_WINDOW = "Trace Player"

        fun getInstance(project: Project): TracePlayerService = project.service()
    }
}
