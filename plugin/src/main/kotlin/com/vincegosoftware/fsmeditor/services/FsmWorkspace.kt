package com.vincegosoftware.fsmeditor.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.MachineInfo
import com.vincegosoftware.fsmeditor.core.MachineSummary
import com.vincegosoftware.fsmeditor.core.SubmachineInfo
import com.vincegosoftware.fsmeditor.core.SubmachineResolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The state machines of the project: lists the *.fsm files submachine states
 * can reference, reads their names and connection points (preferring unsaved
 * text of open documents), and re-syncs every open diagram when any of them
 * changes.
 */
@Service(Service.Level.PROJECT)
class FsmWorkspace(private val project: Project) : Disposable {
    private class Cached(val key: String, val summary: MachineSummary?)

    private val summaries = ConcurrentHashMap<String, Cached>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(".fsm", ignoreCase = true) }) changed()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FsmDocuments.isFsm(FileDocumentManager.getInstance().getFile(event.document))) changed()
            }
        }, this)
    }

    /** Runs [listener] (on the UI thread) whenever a state machine file changes, until [parent] is disposed. */
    fun onAnyMachineChanged(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun changed() {
        alarm.cancelAllRequests()
        alarm.addRequest({ listeners.forEach { it() } }, 150)
    }

    /** Path shown to the user: relative to the project folder when possible. */
    fun display(path: String): String {
        val base = project.basePath?.trimEnd('/') ?: return path
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else path
    }

    /** The *.fsm files of the project (skipping excluded and tool folders). Read action. */
    fun findMachineFiles(): List<VirtualFile> {
        val output = ArrayList<VirtualFile>()
        val index = ProjectFileIndex.getInstance(project)
        val roots = ProjectRootManager.getInstance(project).contentRoots.toMutableList()
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let { base ->
            if (roots.none { VfsUtilCore.isAncestor(it, base, false) }) roots.add(base)
        }
        for (root in roots) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (output.size >= MAX_FILES) return false
                    if (file.isDirectory) {
                        return file == root || (file.name !in SKIPPED_FOLDERS && !file.name.startsWith(".") && !index.isExcluded(file))
                    }
                    if (FsmDocuments.isFsm(file) && output.none { it == file }) output.add(file)
                    return true
                }
            })
        }
        return output.sortedBy { it.path.lowercase() }
    }

    /** Summary of a machine file, cached by document or file modification stamp. Read action. */
    fun summaryOf(file: VirtualFile): MachineSummary? {
        if (!file.isValid) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val key = if (document != null) "d${document.modificationStamp}" else "f${file.modificationStamp}:${file.timeStamp}"
        summaries[file.path]?.let { if (it.key == key) return it.summary }
        val summary = try {
            MachineSummary.tryRead(document?.text ?: VfsUtilCore.loadText(file))
        } catch (_: IOException) {
            null
        }
        summaries[file.path] = Cached(key, summary)
        return summary
    }

    /** Everything validation needs to know about the other machines. Read action. */
    fun resolve(file: VirtualFile, model: FsmModel): Pair<List<MachineInfo>, Map<String, SubmachineInfo>> {
        val summary = { path: String -> file.fileSystem.findFileByPath(path.replace('\\', '/'))?.let { summaryOf(it) } }
        val machines = SubmachineResolver.listMachines(file.path, findMachineFiles().map { it.path }, summary, ::display)
        val submachines = SubmachineResolver.resolve(file.path, model, machines, summary, ::display)
        return machines to submachines
    }

    override fun dispose() {}

    companion object {
        private const val MAX_FILES = 500
        private val SKIPPED_FOLDERS = setOf("bin", "obj", "node_modules", "packages", "build", "out", "dist", "target")

        fun getInstance(project: Project): FsmWorkspace = project.service()
    }
}
