package com.vincegosoftware.fsmeditor.core

import java.util.Collections
import java.util.WeakHashMap

/** What a pane of a diagram diff or merge shows: its role, the marks of its elements and the list to step through. */
class PaneDiff(
    /** `old`, `new`; `incoming`, `current`, `result`. */
    val role: String,
    val title: String,
    /** How many changes (or conflicts). */
    val count: Int,
    val showLayout: Boolean = false,
    /** The conflicts, in the result pane only. */
    val conflicts: List<ConflictRow>? = null,
) {
    /** By element id: `added`, `removed`, `changed`; `conflict`, `merged`, `resolved`. */
    val marks = LinkedHashMap<String, String>()

    /** What changed in each marked element, by id. */
    val details = LinkedHashMap<String, List<String>>()
}

/** A conflict of a merge, as the result pane lists it. */
class ConflictRow(
    val key: String,
    val label: String,
    /** `differs`, `only in incoming`, `only in current`. */
    val how: String,
    val details: List<String>,
    /** `incoming`, `current`, or null when not chosen yet. */
    val choice: String?,
)

/** An open diagram, as sessions see it. */
interface DiffPane {
    /** The model it shows (with its layout), or null when it can't be read. */
    fun currentModel(): FsmModel?

    /** Shows the pane's role, marks and list; null: no diff any more. */
    fun showDiff(diff: PaneDiff?)

    /** Selects and shows an element (null: selects nothing). */
    fun reveal(id: String?)

    /** Says something to the user, in the pane. */
    fun toast(message: String)
}

/**
 * A diagram diff: two panes, the older version and the newer one. The logic of the VS Code edition's `DiffSession`
 * (`src/sessions.ts`), without editor APIs: the editions link their panes through it. A port of the Visual Studio
 * edition's `Sessions.cs`.
 */
class DiffSession(val older: DiffPane, val newer: DiffPane, private val olderTitle: String, private val newerTitle: String) {
    var showLayout = false
        private set
    private var index = -1
    var changes: List<Change> = emptyList()
        private set

    fun other(pane: DiffPane): DiffPane = if (pane === older) newer else older

    /** Compares the two panes again and shows the result in both. */
    fun update() {
        val a = older.currentModel()
        val b = newer.currentModel()
        val all = if (a != null && b != null) Compare.compareModels(a, b) else emptyList()
        changes = if (showLayout) all else all.filter { Compare.isModelChange(it) }
        if (index >= changes.size) index = -1
        for ((pane, role) in listOf(older to "old", newer to "new")) {
            val diff = PaneDiff(role, if (role == "old") olderTitle else newerTitle, changes.size, showLayout)
            for (c in changes) {
                val id = (if (role == "old") c.oldId else c.newId) ?: continue
                diff.marks[id] = c.kind.name.lowercase()
                diff.details[id] = if (c.kind == ChangeKind.Changed) Compare.describeChange(c, showLayout) else listOf(if (c.kind == ChangeKind.Added) "Added" else "Removed")
            }
            pane.showDiff(diff)
        }
    }

    /** Shows layout changes too, or not (off by default). */
    fun setShowLayout(show: Boolean) {
        showLayout = show
        update()
    }

    /** The next ([dir] > 0) or previous change, selected in both panes. */
    fun navigate(dir: Int) {
        if (changes.isEmpty()) return
        val n = changes.size
        index = (index + (if (dir < 0) -1 else 1) + n) % n
        val c = changes[index]
        older.reveal(c.oldId)
        newer.reveal(c.newId)
    }

    /** A pane closed: the other one is no longer part of a diff. */
    fun remove(pane: DiffPane) = other(pane).showDiff(null)
}

/** Two texts of a machine: its model file and its layout file (null when there is none). */
class MachineTexts(val model: String, val layout: String?)

/** What the merge needs from the edition. */
interface MergeHost {
    /** Applies a model to the result as an undoable edit; a message when the format can't hold it. */
    fun edit(model: FsmModel, label: String): String?

    /** The result's state now: a new object at each edit, undo or redo (an earlier one again when it comes back). */
    fun state(): Any
}

/**
 * A diagram merge: the conflicted file is the result, beside its two sides (incoming, current). The logic of the VS
 * Code edition's `MergeSession` (`src/sessions.ts`), without editor APIs. A port of the Visual Studio edition's
 * `Sessions.cs`.
 */
class MergeSession(
    val id: String,
    val current: MachineTexts,
    val incoming: MachineTexts,
    baseTexts: MachineTexts?,
    /** The model of a side's texts (with its layout), or null when it can't be read. */
    read: (MachineTexts) -> FsmModel?,
    private val host: MergeHost,
    /** The model of the result, as it is now. */
    private val result: () -> FsmModel?,
) {
    private val sides = LinkedHashMap<String, DiffPane>()
    private val results = ArrayList<DiffPane>()
    private val currentModel = read(current)
    private val incomingModel = read(incoming)
    val conflicts: List<Change> = if (currentModel != null && incomingModel != null) Compare.compareModels(currentModel, incomingModel) else emptyList()

    /**
     * The choices made, by state of the result: undo and redo take them back with the result. A state reached
     * otherwise (an edit of the result) keeps the choices of the one before.
     */
    private val history: MutableMap<Any, Map<String, String>> = Collections.synchronizedMap(WeakHashMap())
    private var last: Map<String, String> = emptyMap()

    /** Changes of each side since the common version, when it is known: highlighted lightly. */
    private val ours: List<Change>
    private val theirs: List<Change>
    private var index = -1

    init {
        val baseModel = baseTexts?.let(read)
        if (baseModel != null && currentModel != null && incomingModel != null) {
            ours = Compare.compareModels(baseModel, currentModel).filter { Compare.isModelChange(it) }
            theirs = Compare.compareModels(baseModel, incomingModel).filter { Compare.isModelChange(it) }
        } else {
            ours = emptyList()
            theirs = emptyList()
        }
    }

    val conflictCount: Int get() = conflicts.size
    val sidePanes: Map<String, DiffPane> get() = sides
    val resultPanes: List<DiffPane> get() = results

    /** The choices of the result as it is now (by conflict key: `incoming` or `current`). */
    val choices: Map<String, String>
        get() {
            val state = host.state()
            val choices = history.getOrPut(state) { LinkedHashMap(last) }
            last = choices
            return choices
        }

    /** [role]: `incoming`, `current` or `result`. */
    fun attach(pane: DiffPane, role: String) {
        if (role == "result") {
            if (pane !in results) results.add(pane)
        } else sides[role] = pane
        update()
    }

    private fun detailsOf(c: Change): List<String> =
        if (c.kind == ChangeKind.Changed) Compare.describeChange(c, true) else listOf(if (c.kind == ChangeKind.Added) "Only in incoming" else "Only in current")

    /** The item of a conflict in the result, by its key in either version. */
    private fun itemIn(items: CompareItems, c: Change): CompareItem? = items[c.oldKey ?: c.key] ?: items[c.key]

    fun update() {
        val conflictKeys = conflicts.flatMap { listOf(it.key, it.oldKey ?: it.key) }.toHashSet()
        val merged = (ours + theirs).filter { it.key !in conflictKeys && (it.oldKey ?: it.key) !in conflictKeys }
        val choices = choices
        val list = conflicts.map { c ->
            ConflictRow(
                c.key, c.label,
                if (c.kind == ChangeKind.Changed) "differs" else if (c.kind == ChangeKind.Added) "only in incoming" else "only in current",
                detailsOf(c), choices[c.key],
            )
        }
        for ((role, pane) in sides) {
            val diff = PaneDiff(role, if (role == "incoming") "theirs" else "yours", conflicts.size)
            for (c in if (role == "incoming") theirs else ours) {
                if (c.newId != null && c.key !in conflictKeys) diff.marks[c.newId] = "merged"
            }
            for (c in conflicts) {
                val id = (if (role == "incoming") c.newId else c.oldId) ?: continue
                diff.marks[id] = "conflict"
                diff.details[id] = detailsOf(c)
            }
            pane.showDiff(diff)
        }
        val r = result()
        val items = if (r != null) Compare.itemsOf(r) else CompareItems()
        val resultDiff = PaneDiff("result", "${choices.size} of ${conflicts.size} chosen", conflicts.size, conflicts = list)
        for (c in merged) {
            val it = items[c.key]
            if (!it?.id.isNullOrEmpty()) resultDiff.marks[it!!.id] = "merged"
        }
        for (c in conflicts) {
            val it = itemIn(items, c)
            if (it == null || it.id.isEmpty()) continue
            resultDiff.marks[it.id] = if (c.key in choices) "resolved" else "conflict"
            resultDiff.details[it.id] = detailsOf(c)
        }
        for (pane in results) pane.showDiff(resultDiff)
    }

    /** The result with conflict [c] taken from [side]. */
    private fun take(model: FsmModel, c: Change, side: String): FsmModel {
        val source = if (side == "incoming") incomingModel!! else currentModel!!
        val keys = if (side == "incoming") arrayOf(c.key, c.oldKey ?: c.key) else arrayOf(c.oldKey ?: c.key, c.key)
        return Merge.adopt(model, source, *keys)
    }

    /** The next or previous conflict ([dir]), or the one of [key], shown in every pane. */
    fun navigate(dir: Int, key: String? = null) {
        if (conflicts.isEmpty()) return
        val n = conflicts.size
        index = if (key != null) maxOf(0, conflicts.indexOfFirst { it.key == key }) else (index + (if (dir < 0) -1 else 1) + n) % n
        val conflict = conflicts[index]
        sides["incoming"]?.reveal(conflict.newId)
        sides["current"]?.reveal(conflict.oldId)
        val r = result()
        val it = if (r != null) itemIn(Compare.itemsOf(r), conflict) else null
        for (p in results) p.reveal(it?.id)
    }

    /** Takes one [side] (`incoming` or `current`) for the conflict of [key], or for all of them ([key] null). */
    fun choose(key: String?, side: String) {
        var model = result() ?: return
        if (incomingModel == null || currentModel == null) return
        val chosen = if (side == "incoming") "incoming" else "current"
        val list = if (key == null) conflicts else conflicts.filter { it.key == key }
        val choices = LinkedHashMap(choices)
        for (c in list) {
            model = take(model, c, chosen)
            choices[c.key] = chosen
        }
        val problem = host.edit(model, if (chosen == "incoming") "Take Incoming" else "Keep Current")
        // The choices go with the result's new state (the same one when the choice changed nothing in it).
        if (problem != null) {
            for (p in results) p.toast(problem)
        } else {
            history[host.state()] = choices
            last = choices
        }
        update()
    }

    fun remove(pane: DiffPane) {
        results.remove(pane)
        sides.entries.removeIf { it.value === pane }
    }
}
