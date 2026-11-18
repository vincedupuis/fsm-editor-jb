package com.vincegosoftware.fsmeditor.core

/**
 * The model as JSON, the form fsm-editor-common takes and gives (its `api.schema.json`, "model"): the VS Code edition's
 * model, flat, with ids and absolute coordinates. Vertices and transitions are written as the clipboard writes them
 * ([ClipboardJson]), whose JSON is the same. A port of the Visual Studio edition's `ModelJson.cs`.
 */
object ModelJson {
    fun write(m: FsmModel): String =
        ClipboardJson.JsonObjectWriter().num("version", 1.0).str("name", m.name)
            .str("kind", if (m.kind == MachineKind.Protocol) "protocol" else "behavioral").str("documentation", m.documentation)
            .raw("regions", "[" + m.regions.joinToString(",", transform = ::writeRegion) + "]")
            .raw("vertices", "[" + m.vertices.joinToString(",", transform = ClipboardJson::writeVertex) + "]")
            .raw("transitions", "[" + m.transitions.joinToString(",", transform = ClipboardJson::writeTransition) + "]")
            .toString()

    private fun writeRegion(r: Region): String =
        ClipboardJson.JsonObjectWriter().str("id", r.id).str("name", r.name).also { w -> r.size?.let { w.num("size", it) } }.toString()

    /** A model from its parsed JSON ([PlainJson.parse]). */
    fun read(json: Any?): FsmModel {
        val o = json as? Map<*, *> ?: emptyMap<String, Any?>()
        val m = FsmModel()
        m.name = str(o, "name")
        m.kind = if (str(o, "kind") == "protocol") MachineKind.Protocol else MachineKind.Behavioral
        m.documentation = str(o, "documentation")
        m.regions = objects(o, "regions").mapTo(mutableListOf(), ::readRegion)
        m.vertices = objects(o, "vertices").mapTo(mutableListOf(), ::readVertex)
        m.transitions = objects(o, "transitions").mapTo(mutableListOf(), ::readTransition)
        return m
    }

    private fun readRegion(r: Map<*, *>) = Region(str(r, "id"), str(r, "name"), r["size"] as? Double)

    private fun readVertex(item: Map<*, *>): Vertex = Vertex(
        id = str(item, "id"),
        type = VertexType.parseKey(str(item, "type")) ?: VertexType.State,
        name = str(item, "name"),
        parent = str(item, "parent"),
        x = num(item, "x"),
        y = num(item, "y"),
        w = num(item, "w"),
        h = num(item, "h"),
    ).apply {
        regions = objects(item, "regions").mapTo(mutableListOf(), ::readRegion)
        regionLayout = when (str(item, "regionLayout")) {
            "horizontal" -> RegionLayout.Horizontal
            "vertical" -> RegionLayout.Vertical
            else -> null
        }
        entry = str(item, "entry")
        exit = str(item, "exit")
        doActivity = str(item, "doActivity")
        deferrable = strings(item, "deferrable")
        invariant = str(item, "invariant")
        submachine = str(item, "submachine")
        stereotype = str(item, "stereotype")
        ref = str(item, "ref")
        pointKind = if (str(item, "pointKind") == "exit") PointKind.Exit else PointKind.Entry
        text = str(item, "text")
        anchors = strings(item, "anchors")
        color = ClipboardJson.readColor(item, "color")
        itemColors = ClipboardJson.readItemColors(item)
        group = str(item, "group")
    }

    private fun readTransition(item: Map<*, *>): Transition = Transition(
        id = str(item, "id"),
        source = str(item, "source"),
        target = str(item, "target"),
        kind = when (str(item, "kind")) {
            "internal" -> TransitionKind.Internal
            "local" -> TransitionKind.Local
            else -> TransitionKind.External
        },
    ).apply {
        triggers = strings(item, "triggers")
        guard = str(item, "guard")
        action = str(item, "action")
        precondition = str(item, "precondition")
        postcondition = str(item, "postcondition")
        points = objects(item, "points").mapTo(mutableListOf()) { PointD(num(it, "x"), num(it, "y")) }
        labelOffset = (item["labelOffset"] as? Map<*, *>)?.let { PointD(num(it, "x"), num(it, "y")) }
        color = ClipboardJson.readColor(item, "color")
        itemColors = ClipboardJson.readItemColors(item)
    }

    fun str(o: Map<*, *>?, key: String): String = o?.get(key) as? String ?: ""

    /** A number; null (a number JSON can't hold) and a missing one are 0. */
    fun num(o: Map<*, *>?, key: String): Double = o?.get(key) as? Double ?: 0.0

    fun strings(o: Map<*, *>?, key: String): MutableList<String> = (o?.get(key) as? List<*>)?.filterIsInstanceTo(mutableListOf()) ?: mutableListOf()

    fun objects(o: Map<*, *>?, key: String): List<Map<*, *>> = (o?.get(key) as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
}
