package com.vincegosoftware.fsmeditor.core

enum class ChangeKind { Added, Removed, Changed }

/** A property that differs, with its two values as text ("" when absent). */
class PropertyChange(val name: String, val old: String, val new: String, /** A property of the drawing, not of the model. */ val layout: Boolean)

class Change(
    /** What identifies the element in the newer version (see [Compare.itemsOf]). */
    val key: String,
    /** Its key in the older version, when it differs (a transition whose target or trigger changed). */
    val oldKey: String?,
    val kind: ChangeKind,
    /** `machine`, `state`, `choice`…, `region`, `transition`. */
    val what: String,
    /** How a person names it: `state 'Off'`, `transition Off -> On (powerOn)`. */
    val label: String,
    /** The element's id in each version. */
    val oldId: String?,
    val newId: String?,
    /** For [ChangeKind.Changed]: what differs. */
    val properties: List<PropertyChange> = emptyList(),
    /** The properties as lines (`guard: a() → b()`): all of them, and the model's only. */
    val described: List<String> = emptyList(),
    val modelDescribed: List<String> = emptyList(),
    /** Whether it shows when layout changes are hidden. */
    val modelChange: Boolean = false,
)

/** Properties as text, in the order they were set. */
class Props {
    private val values = LinkedHashMap<String, String>()

    operator fun get(key: String): String? = values[key]

    operator fun set(key: String, value: String) {
        values[key] = value
    }

    val keys: List<String> get() = values.keys.toList()
}

/** What can be compared in a model: an element by key, with its properties. */
class CompareItem(val key: String, val id: String, val what: String, val label: String, val model: Props, val layout: Props)

/** The items of a model in order, by key. */
class CompareItems {
    val list = ArrayList<CompareItem>()
    val byKey = HashMap<String, CompareItem>()

    operator fun get(key: String?): CompareItem? = if (key == null) null else byKey[key]

    fun has(key: String?) = key != null && key in byKey

    internal fun set(item: CompareItem) {
        if (item.key !in byKey) list.add(item) else list[list.indexOfFirst { it.key == item.key }] = item
        byKey[item.key] = item
    }
}

/**
 * Comparing two versions of a machine, for the diagram diff (two panes) and merge (three panes): fsm-editor-common's
 * ([Common]). Elements are matched by what identifies them in the file (an element by its name, a transition by its
 * source, trigger and target...); each match lists the properties that differ, model and layout apart.
 */
object Compare {
    /** The elements of a model by the key that matches them across versions (their ids only). */
    fun itemsOf(model: FsmModel): CompareItems {
        val items = CompareItems()
        val r = Common.call("keys", Common.args("model" to ModelJson.write(model))) as? Map<*, *> ?: return items
        for ((k, v) in r) items.set(CompareItem(k as String, v as? String ?: "", "", "", Props(), Props()))
        return items
    }

    /** What changed from [older] to [newer], in the order of the newer version (removed ones last). */
    fun compareModels(older: FsmModel, newer: FsmModel): List<Change> {
        val r = Common.call("compare", Common.args("older" to ModelJson.write(older), "newer" to ModelJson.write(newer))) as List<*>
        return r.filterIsInstance<Map<*, *>>().map { c ->
            Change(
                key = ModelJson.str(c, "key"),
                oldKey = c["oldKey"] as? String,
                kind = when (ModelJson.str(c, "kind")) {
                    "added" -> ChangeKind.Added
                    "removed" -> ChangeKind.Removed
                    else -> ChangeKind.Changed
                },
                what = ModelJson.str(c, "what"),
                label = ModelJson.str(c, "label"),
                oldId = c["oldId"] as? String,
                newId = c["newId"] as? String,
                properties = ModelJson.objects(c, "properties").map {
                    PropertyChange(ModelJson.str(it, "name"), ModelJson.str(it, "old"), ModelJson.str(it, "new"), it["layout"] == true)
                },
                described = ModelJson.strings(c, "described"),
                modelDescribed = ModelJson.strings(c, "modelDescribed"),
                modelChange = c["modelChange"] == true,
            )
        }
    }

    /** Whether a change shows when layout changes are hidden. */
    fun isModelChange(c: Change): Boolean = c.modelChange

    /** A change's properties as lines: `guard: a() → b()`. */
    fun describeChange(c: Change, layout: Boolean): List<String> = if (layout) c.described else c.modelDescribed
}
