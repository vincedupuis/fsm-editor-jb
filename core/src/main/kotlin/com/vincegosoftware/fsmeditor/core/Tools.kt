package com.vincegosoftware.fsmeditor.core

/** An item of the diagram toolbox. */
class ToolDefinition(
    val id: String,
    val label: String,
    /** Single-letter shortcut, or null. */
    val key: Char? = null,
    /** A mode (transition, region) rather than an element to place. */
    val isMode: Boolean = false,
    /** Builds the element placed by this tool (id, parent and position are set by the caller). */
    val create: ((DiagramSession) -> Vertex)? = null,
)

class ToolGroup(val title: String, val items: List<ToolDefinition>)

object Tools {
    private fun make(type: VertexType, w: Double, h: Double, name: String = "") = Vertex(type = type, name = name, w = w, h = h)

    val groups: List<ToolGroup> = listOf(
        ToolGroup(
            "States",
            listOf(
                ToolDefinition("state", "State", 's') { make(VertexType.State, 140.0, 60.0, it.uniqueName("State")) },
                ToolDefinition("composite", "Composite State") { s ->
                    make(VertexType.State, 280.0, 190.0, s.uniqueName("Composite")).also { it.regions.add(Region(s.newId("r"))) }
                },
                ToolDefinition("orthogonal", "Orthogonal State") { s ->
                    make(VertexType.State, 340.0, 250.0, s.uniqueName("Orthogonal")).also {
                        it.regions.add(Region(s.newId("r")))
                        it.regions.add(Region(s.newId("r")))
                        it.regionLayout = RegionLayout.Vertical
                    }
                },
                ToolDefinition("submachine", "Submachine State") { s ->
                    make(VertexType.State, 170.0, 60.0, s.uniqueName("Sub")).also { it.submachine = s.machines.firstOrNull()?.href ?: "" }
                },
                ToolDefinition("connectionPointRef", "Connection Point Ref") { make(VertexType.ConnectionPointRef, 16.0, 16.0) },
                ToolDefinition("final", "Final State", 'x') { make(VertexType.Final, 26.0, 26.0) },
            ),
        ),
        ToolGroup(
            "Pseudostates",
            listOf(
                ToolDefinition("initial", "Initial", 'i') { make(VertexType.Initial, 20.0, 20.0) },
                ToolDefinition("shallowHistory", "Shallow History", 'h') { make(VertexType.ShallowHistory, 26.0, 26.0) },
                ToolDefinition("deepHistory", "Deep History") { make(VertexType.DeepHistory, 26.0, 26.0) },
                ToolDefinition("choice", "Choice", 'c') { make(VertexType.Choice, 28.0, 28.0) },
                ToolDefinition("junction", "Junction", 'j') { make(VertexType.Junction, 14.0, 14.0) },
                ToolDefinition("fork", "Fork") { make(VertexType.Fork, 90.0, 8.0) },
                ToolDefinition("join", "Join") { make(VertexType.Join, 90.0, 8.0) },
                ToolDefinition("entryPoint", "Entry Point") { make(VertexType.EntryPoint, 16.0, 16.0) },
                ToolDefinition("exitPoint", "Exit Point") { make(VertexType.ExitPoint, 16.0, 16.0) },
                ToolDefinition("terminate", "Terminate") { make(VertexType.Terminate, 20.0, 20.0) },
            ),
        ),
        ToolGroup(
            "Connections",
            listOf(
                ToolDefinition("transition", "Transition", 't', isMode = true),
                ToolDefinition("region", "Add Region", 'r', isMode = true),
            ),
        ),
        ToolGroup(
            "Annotations",
            listOf(
                ToolDefinition("comment", "Comment", 'n') { make(VertexType.Comment, 160.0, 64.0).also { it.text = "Note" } },
            ),
        ),
    )

    val all: List<ToolDefinition> get() = groups.flatMap { it.items }

    fun byId(id: String?): ToolDefinition? = all.firstOrNull { it.id == id }

    fun byKey(key: Char): ToolDefinition? = all.firstOrNull { it.key == key.lowercaseChar() }

    /** Pseudostate kinds that can be swapped for one another from the properties panel. */
    val swappable: List<List<VertexType>> = listOf(
        listOf(VertexType.Initial, VertexType.ShallowHistory, VertexType.DeepHistory, VertexType.Choice, VertexType.Junction, VertexType.Terminate),
        listOf(VertexType.Fork, VertexType.Join),
        listOf(VertexType.EntryPoint, VertexType.ExitPoint),
    )
}
