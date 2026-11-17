package com.vincegosoftware.fsmeditor.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.DocumentReferenceProvider
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.vincegosoftware.fsmeditor.core.ConflictMarkerException
import com.vincegosoftware.fsmeditor.core.ConvertInput
import com.vincegosoftware.fsmeditor.core.Conversion
import com.vincegosoftware.fsmeditor.core.Converted
import com.vincegosoftware.fsmeditor.core.FormatLossException
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.core.MachineState
import com.vincegosoftware.fsmeditor.core.MachineTexts
import com.vincegosoftware.fsmeditor.core.Merge
import com.vincegosoftware.fsmeditor.core.Problem
import com.vincegosoftware.fsmeditor.core.Format
import com.vincegosoftware.fsmeditor.services.FsmWorkspace
import com.vincegosoftware.fsmeditor.services.Git
import java.beans.PropertyChangeListener
import java.io.File
import java.io.IOException
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.min

/** An old file (XMI), converted when it was read: what saving it converts and says. */
class OldFile(val text: String, val result: Converted)

/** The layout file when it couldn't be read: not overwritten without asking. */
class UnreadableLayout(val text: String, val problem: String)

/** A file with merge conflicts: its two sides (the layout file's too when it has conflicts), and Git's common version when known. */
class MergeInfo(val current: MachineTexts, val incoming: MachineTexts, val base: MachineTexts?)

/**
 * What a diagram shows and edits: a machine's state ([MachineState]: the texts of its model and layout files and the
 * model), what reading it found, and its edits. [FileSource] is a machine file and its layout file, through the IDE's
 * documents; [FixedSource] is a version of a machine, read-only (a side of a diff or merge).
 */
abstract class DiagramSource {
    /** Path of the model file (of the file a version is of), for submachine paths and messages. */
    abstract val path: String
    abstract val readOnly: Boolean
    abstract val state: MachineState
    /** What the diagram shows when it isn't the state's model (a borrowed layout), or null. */
    var displayModel: FsmModel? = null
    var old: OldFile? = null
        protected set
    var unreadableLayout: UnreadableLayout? = null
        protected set
    /** No layout file: an older version in a diff borrows the newer one's layout. */
    var layoutMissing = false
        protected set
    var merge: MergeInfo? = null
    var mergeFallback = false
        protected set
    var layoutConflict = false
        protected set

    private val listeners = ArrayList<() -> Unit>()

    /** Runs [listener] (on the UI thread) when the state changes, until [parent] is disposed. */
    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    protected fun fireChanged() = listeners.toList().forEach { it() }

    /** Raises a change without a new state (the diagram resyncs). */
    fun refresh() = fireChanged()

    /** An edit of the diagram: a message when it can't be made ("": refused without a message), else null. */
    abstract fun edit(model: FsmModel): String?
    open fun undo() {}
    open fun redo() {}

    companion object {
        /** Reads a machine's texts: converts an old file (with [linked], the old files linked to it), splits conflict markers. */
        internal fun read(modelText: String, layoutRaw: String?, linked: () -> List<ConvertInput>, path: String, base: () -> MachineTexts?): Read {
            if (Merge.hasConflictMarkers(modelText)) return readConflicted(modelText, layoutRaw, base)
            if (Format.isOldFormat(modelText)) {
                val result = Conversion.convertGroup(linked()).firstOrNull { it.path == path } ?: Conversion.convertGroup(listOf(ConvertInput(path, modelText))).first()
                if (result.error != null) return Read(MachineState(modelText, problems = listOf(Problem(1, result.error!!))))
                return Read(MachineState(result.modelText, result.layoutText, result.model), old = OldFile(modelText, result))
            }
            var layoutText = layoutRaw?.takeIf { it.isNotBlank() }
            // Only the layout file has merge conflicts: its current side (saving writes it clean).
            var layoutConflict = false
            if (layoutText != null && Merge.hasConflictMarkers(layoutText)) {
                try {
                    layoutText = Merge.splitConflicts(layoutText).current
                    layoutConflict = true
                } catch (_: ConflictMarkerException) {
                    // unreadable: placed automatically
                }
            }
            val s = MachineFiles.stateOf(modelText, layoutText)
            val unreadable = if (s.layoutProblem != null && layoutText != null && !layoutConflict) UnreadableLayout(layoutText, s.layoutProblem!!) else null
            // An unreadable layout file is left for automatic places: writing the state as it is keeps that file.
            val state = if (unreadable != null) MachineState(s.modelText, null, s.model, s.problems, s.lines, s.placed, s.layoutProblem) else s
            return Read(state, unreadable = unreadable, layoutMissing = layoutText == null, layoutConflict = layoutConflict)
        }

        /**
         * A model file with merge conflicts: its two sides (the layout file's too when it has conflicts) are the diagram
         * merge's, and the result starts as the current side. When a side isn't a valid machine file, no merge: its problems.
         */
        private fun readConflicted(modelText: String, layoutRaw: String?, base: () -> MachineTexts?): Read {
            fun fail(message: String) = Read(MachineState(modelText, problems = listOf(Problem(1, message))), mergeFallback = true)
            val model = try {
                Merge.splitConflicts(modelText)
            } catch (e: ConflictMarkerException) {
                return fail("The file has merge conflicts whose markers can't be read: ${e.message}")
            }
            var current = layoutRaw
            var incoming = layoutRaw
            if (layoutRaw != null && Merge.hasConflictMarkers(layoutRaw)) {
                try {
                    val split = Merge.splitConflicts(layoutRaw)
                    current = split.current
                    incoming = split.incoming
                } catch (_: ConflictMarkerException) {
                    current = null
                    incoming = null
                }
            }
            for ((side, text) in listOf("current" to model.current, "incoming" to model.incoming)) {
                val r = Format.readModel(text)
                if (r.failed) return fail("The file has merge conflicts, and its $side side is not a valid state machine file: line ${r.problems!![0].line}: ${r.problems!![0].message}")
            }
            val s = MachineFiles.stateOf(model.current, current)
            return Read(
                MachineState(s.modelText, current, s.model, s.problems, s.lines, s.placed, s.layoutProblem),
                merge = MergeInfo(MachineTexts(model.current, current), MachineTexts(model.incoming, incoming), base()),
            )
        }
    }

    internal class Read(
        val state: MachineState,
        val old: OldFile? = null,
        val unreadable: UnreadableLayout? = null,
        val layoutMissing: Boolean = false,
        val merge: MergeInfo? = null,
        val mergeFallback: Boolean = false,
        val layoutConflict: Boolean = false,
    )

    internal fun apply(r: Read) {
        old = r.old
        unreadableLayout = r.unreadable
        layoutMissing = r.layoutMissing
        merge = r.merge
        mergeFallback = r.mergeFallback
        layoutConflict = r.layoutConflict
    }
}

/** A version of a machine (a side of a diff or merge): its texts, read-only. */
class FixedSource(override val path: String, texts: MachineTexts) : DiagramSource() {
    override val readOnly = true
    override val state: MachineState

    init {
        val r = read(texts.model, texts.layout, { listOf(ConvertInput(path, texts.model)) }, path) { null }
        apply(r)
        merge = null
        state = r.state
    }

    override fun edit(model: FsmModel): String = "This diagram is read-only."
}

/**
 * A machine file and its layout file, through the IDE's documents: the state is read from their texts (the same
 * texts give the same state object, so that an undo comes back to the state it left), an edit of the diagram writes
 * both documents in one undoable command, and the IDE saves them. The Text tab edits the same model document.
 *
 * Opening a file changes nothing: an old file (XMI) is converted, a file with merge conflicts split into its sides,
 * in memory; the first edit writes the new texts, and saving an old file converts the files linked to it.
 */
class FileSource(private val project: Project, val file: VirtualFile, parent: Disposable) : DiagramSource() {
    private val modelDocument: Document = FileDocumentManager.getInstance().getDocument(file)
        ?: throw IllegalStateException("No document for ${file.path}")
    /** States by the texts of the two documents (a few, most recent last). */
    private val states = object : LinkedHashMap<Pair<String, String?>, MachineState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String?>, MachineState>?) = size > 200
    }
    /** "Save the Model Only" was chosen for an unreadable layout file: edits leave it as it is. */
    private var keepLayout = false
    private var readKey: Pair<String, String?>? = null

    /** Undo and redo of the two documents, for a diagram that isn't a file editor of its own (a side of a diff). */
    val undoHandle = UndoHandle()

    override val path: String get() = file.path
    override val readOnly: Boolean get() = !file.isInLocalFileSystem && !file.isWritable

    val layoutPath: String get() = MachineFiles.layoutPathOf(file.path)

    fun layoutFile(): VirtualFile? = file.parent?.findChild(file.name + ".layout")?.takeIf { it.isValid && !it.isDirectory }

    private fun layoutDocument(): Document? = layoutFile()?.let { FileDocumentManager.getInstance().getDocument(it) }

    fun documents(): List<Document> = listOfNotNull(modelDocument, layoutDocument())

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.document === modelDocument || event.document === layoutDocument()) fireChanged()
            }
        }, parent)
        val bus = ApplicationManager.getApplication().messageBus.connect(parent)
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path == layoutPath || it.path == file.path }) fireChanged()
            }
        })
        bus.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                if (document === modelDocument) saving()
            }
        })
        reread(true)
    }

    private fun texts(): Pair<String, String?> = modelDocument.text to layoutDocument()?.text

    /** The old files of the project, for the machines linked to an old one (converted with it). */
    private fun linkedOldFiles(text: String): List<ConvertInput> {
        val others = FsmWorkspace.getInstance(project).findMachineFiles().mapNotNull { f ->
            val t = readText(f) ?: return@mapNotNull null
            if (Format.isOldFormat(t)) ConvertInput(f.path, t) else null
        }
        return MachineFiles.linkedOldFiles(file.path, text, others) { p -> LocalFileSystem.getInstance().findFileByPath(p)?.let(::readText) }
    }

    private fun readText(f: VirtualFile): String? = try {
        FileDocumentManager.getInstance().getCachedDocument(f)?.text ?: VfsUtilCore.loadText(f)
    } catch (_: IOException) {
        null
    }

    /**
     * Reads the documents again when they changed otherwise than by this source's edits (the Text tab, a reload, a
     * change on disk); [first]: when the file is opened, which decides the merge.
     */
    private fun reread(first: Boolean = false) {
        val key = texts()
        val r = read(key.first, key.second, { linkedOldFiles(key.first) }, file.path) { Git.commonVersion(File(file.path)) }
        val keepMerge = merge
        val keepOld = old
        apply(r)
        // A merge goes on until it is completed: the result's edits remove the markers.
        if (!first && keepMerge != null && r.merge == null) merge = keepMerge
        // An old file edited in the new format is still converted (with the files linked to it) when saved.
        if (old == null && keepOld != null && !Format.isOldFormat(key.first)) old = keepOld
        states[key] = r.state
        readKey = key
    }

    override val state: MachineState
        get() {
            val key = texts()
            states[key]?.let { return it }
            reread()
            return states[key]!!
        }

    override fun edit(model: FsmModel): String? {
        if (readOnly) return "This diagram is read-only."
        val next = try {
            MachineFiles.write(model)
        } catch (e: FormatLossException) {
            return "The change can't be saved: ${e.message}"
        }
        val current = state
        if (next.sameFiles(current)) return null
        var writeLayout = !keepLayout
        val unreadable = unreadableLayout
        if (unreadable != null && writeLayout && layoutDocument()?.text == unreadable.text) {
            val answer = Messages.showDialog(
                project,
                "${file.name}.layout could not be read when the diagram was opened (${unreadable.problem}), so its elements were placed automatically. Replace it with the diagram's layout?",
                "Edit State Machine", arrayOf("Overwrite It", "Save the Model Only", Messages.getCancelButton()), 0, Messages.getWarningIcon(),
            )
            when (answer) {
                0 -> unreadableLayout = null
                1 -> {
                    keepLayout = true
                    writeLayout = false
                }
                else -> return ""
            }
        }
        val targets = listOfNotNull(file, if (writeLayout) layoutFile() else null)
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(targets).hasReadonlyFiles()) return "The file is read-only; the change was not saved."
        var layoutDoc = if (writeLayout) layoutDocument() else null
        if (writeLayout && layoutDoc == null) {
            // Created outside the undoable command, empty (an empty layout file is none): undo leaves an empty file.
            try {
                val folder = file.parent ?: return "The layout file can't be created next to ${file.name}."
                val created = WriteAction.compute<VirtualFile, IOException> { folder.findChild(file.name + ".layout") ?: folder.createChildData(this, file.name + ".layout") }
                layoutDoc = FileDocumentManager.getInstance().getDocument(created)
            } catch (e: IOException) {
                return "${file.name}.layout could not be created: ${e.message}"
            }
        }
        val layoutText = if (writeLayout) next.layoutText else layoutDoc?.text ?: layoutDocument()?.text
        // The state of the new texts is the diagram's own (its ids), so that it isn't shown again.
        val state = MachineState(next.modelText, if (writeLayout) next.layoutText else null, model.copy(), null, next.lines)
        states[next.modelText to (if (writeLayout) next.layoutText else layoutDocument()?.text)] = state
        if (layoutConflict && writeLayout) layoutConflict = false
        WriteCommandAction.writeCommandAction(project).withName("Edit State Machine").run<RuntimeException> {
            replace(modelDocument, next.modelText)
            if (writeLayout && layoutDoc != null && layoutText != null) replace(layoutDoc, layoutText)
        }
        return null
    }

    /** Writes [state]'s texts into the documents as they are (Complete Merge without a choice: the current side). */
    fun writeTexts(s: MachineState) {
        val layoutText = s.layoutText ?: s.model?.let { MachineFiles.write(it.copy()).layoutText }
        var layoutDoc = layoutDocument()
        if (layoutDoc == null && layoutText != null) {
            val folder = file.parent ?: return
            val created = WriteAction.compute<VirtualFile, IOException> { folder.findChild(file.name + ".layout") ?: folder.createChildData(this, file.name + ".layout") }
            layoutDoc = FileDocumentManager.getInstance().getDocument(created)
        }
        states[s.modelText to layoutText] = s
        WriteCommandAction.writeCommandAction(project).withName("Complete Merge").run<RuntimeException> {
            replace(modelDocument, s.modelText)
            if (layoutDoc != null && layoutText != null) replace(layoutDoc, layoutText)
        }
    }

    /** Saves both documents. */
    fun save() {
        val manager = FileDocumentManager.getInstance()
        for (d in documents()) manager.saveDocument(d)
    }

    override fun undo() {
        val undo = UndoManager.getInstance(project)
        if (undo.isUndoAvailable(undoHandle)) undo.undo(undoHandle)
    }

    override fun redo() {
        val undo = UndoManager.getInstance(project)
        if (undo.isRedoAvailable(undoHandle)) undo.redo(undoHandle)
    }

    /** The model document of an old file is saved in the new format: the old files linked to it are converted too. */
    private fun saving() {
        val o = old ?: return
        if (Format.isOldFormat(modelDocument.text)) return
        old = null
        ApplicationManager.getApplication().invokeLater {
            val message = convertLinked(o)
            NotificationGroupManager.getInstance().getNotificationGroup("FSM Editor").createNotification(message, NotificationType.INFORMATION).notify(project)
        }
    }

    private fun convertLinked(o: OldFile): String {
        val converted = arrayListOf(file.name to o.result)
        val skipped = ArrayList<String>()
        for (r in Conversion.convertGroup(linkedOldFiles(o.text))) {
            if (r.path == file.path) continue
            val target = LocalFileSystem.getInstance().findFileByPath(r.path)
            val name = File(r.path).name
            val document = target?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            if (r.error != null || target == null || (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document))) {
                skipped.add(name)
                continue
            }
            try {
                WriteAction.run<IOException> {
                    target.setBinaryContent(r.modelText.toByteArray(Charsets.UTF_8))
                    val folder = target.parent
                    val layout = folder.findChild(target.name + ".layout") ?: folder.createChildData(this, target.name + ".layout")
                    layout.setBinaryContent(r.layoutText.toByteArray(Charsets.UTF_8))
                }
                converted.add(name to r)
            } catch (_: IOException) {
                skipped.add(name)
            }
        }
        return MachineFiles.conversionMessage(converted, skipped)
    }

    /** The two documents for the undo manager: a minimal file editor (the diagram's own, in a diff, isn't one). */
    inner class UndoHandle : UserDataHolderBase(), FileEditor, DocumentReferenceProvider {
        override fun getDocumentReferences(): Collection<DocumentReference> = documents().map { DocumentReferenceManager.getInstance().create(it) }
        override fun getComponent(): JComponent = JPanel()
        override fun getPreferredFocusedComponent(): JComponent? = null
        override fun getName() = "State Machine"
        override fun setState(state: FileEditorState) {}
        override fun isModified() = false
        override fun isValid() = file.isValid
        override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
        override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
        override fun getCurrentLocation(): FileEditorLocation? = null
        override fun getFile(): VirtualFile = this@FileSource.file
        override fun dispose() {}
    }

    companion object {
        /** Replaces only what changed in [document]. */
        fun replace(document: Document, text: String) {
            val old = document.text
            if (old == text) return
            var prefix = 0
            val max = min(old.length, text.length)
            while (prefix < max && old[prefix] == text[prefix]) prefix++
            var suffix = 0
            while (suffix < max - prefix && old[old.length - 1 - suffix] == text[text.length - 1 - suffix]) suffix++
            document.replaceString(prefix, old.length - suffix, text.substring(prefix, text.length - suffix))
        }
    }
}
