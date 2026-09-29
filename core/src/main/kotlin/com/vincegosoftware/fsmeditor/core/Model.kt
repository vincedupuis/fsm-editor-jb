package com.vincegosoftware.fsmeditor.core

/*
 * In-memory UML state machine model. Files store it as XMI with UML DI
 * (see Xmi); the diagram editor works on this form.
 *
 * The hierarchy is flat: every vertex points to its owner through
 * Vertex.parent, which is either a region id (root region of the machine or a
 * region of a composite state) or, for connection points, the id of the owning
 * state.
 *
 * Entry/exit points whose parent is a top-level region are connection points
 * of the state machine itself; a submachine state that references this machine
 * exposes them through ConnectionPointRef vertices placed on its border.
 */

enum class VertexType {
    State,
    Final,
    Initial,
    ShallowHistory,
    DeepHistory,
    Choice,
    Junction,
    Fork,
    Join,
    EntryPoint,
    ExitPoint,
    Terminate,
    ConnectionPointRef,
    Comment;

    val isPseudostate: Boolean
        get() = this == Initial || this == ShallowHistory || this == DeepHistory || this == Choice ||
            this == Junction || this == Fork || this == Join || this == EntryPoint || this == ExitPoint || this == Terminate

    val isPointType: Boolean
        get() = this == EntryPoint || this == ExitPoint || this == ConnectionPointRef

    /** The name used in files and messages: `shallowHistory`, `connectionPointRef`… */
    val key: String
        get() = name[0].lowercaseChar() + name.substring(1)

    /** Lowercase label used in validation messages. */
    val label: String
        get() = when (this) {
            State -> "state"
            Final -> "final state"
            Initial -> "initial pseudostate"
            ShallowHistory -> "shallow history"
            DeepHistory -> "deep history"
            Choice -> "choice"
            Junction -> "junction"
            Fork -> "fork"
            Join -> "join"
            EntryPoint -> "entry point"
            ExitPoint -> "exit point"
            ConnectionPointRef -> "connection point reference"
            Terminate -> "terminate"
            Comment -> "comment"
        }

    /** Title-case label used in the editor. */
    val title: String
        get() = when (this) {
            State -> "State"
            Final -> "Final State"
            Initial -> "Initial"
            ShallowHistory -> "Shallow History"
            DeepHistory -> "Deep History"
            Choice -> "Choice"
            Junction -> "Junction"
            Fork -> "Fork"
            Join -> "Join"
            EntryPoint -> "Entry Point"
            ExitPoint -> "Exit Point"
            Terminate -> "Terminate"
            ConnectionPointRef -> "Connection Point Reference"
            Comment -> "Comment"
        }

    /** Default size (width, height) of a new or unplaced vertex. */
    val defaultSize: Pair<Double, Double>
        get() = when (this) {
            State -> 140.0 to 60.0
            Final -> 26.0 to 26.0
            Initial -> 20.0 to 20.0
            ShallowHistory, DeepHistory -> 26.0 to 26.0
            Choice -> 28.0 to 28.0
            Junction -> 14.0 to 14.0
            Fork, Join -> 90.0 to 8.0
            EntryPoint, ExitPoint, ConnectionPointRef -> 16.0 to 16.0
            Terminate -> 20.0 to 20.0
            Comment -> 160.0 to 64.0
        }

    companion object {
        fun parseKey(key: String?): VertexType? = entries.firstOrNull { it.key == key }
    }
}

enum class PointKind { Entry, Exit }

enum class TransitionKind {
    External, Internal, Local;

    val key: String get() = name.lowercase()
}

enum class MachineKind { Behavioral, Protocol }

enum class RegionLayout { Vertical, Horizontal }

data class PointD(val x: Double, val y: Double) {
    override fun toString() = "$x,$y"
}

class Region(var id: String = "", var name: String = "") {
    fun copy() = Region(id, name)
}

class Vertex(
    var id: String = "",
    var type: VertexType = VertexType.State,
    var name: String = "",
    var parent: String = "",
    var x: Double = 0.0,
    var y: Double = 0.0,
    var w: Double = 0.0,
    var h: Double = 0.0,
) {
    // state
    var regions: MutableList<Region> = mutableListOf()
    var regionLayout: RegionLayout = RegionLayout.Vertical
    var entry: String = ""
    var exit: String = ""
    var doActivity: String = ""
    var deferrable: MutableList<String> = mutableListOf()
    var invariant: String = ""
    /** Submachine state: XMI href of the referenced state machine, e.g. `Payment.fsm#sm`. */
    var submachine: String = ""
    var stereotype: String = ""

    // connectionPointRef: xmi:id of the entry/exit point, inside the submachine's file
    var ref: String = ""
    var pointKind: PointKind = PointKind.Entry

    // comment
    var text: String = ""
    var anchors: MutableList<String> = mutableListOf()

    val centerX: Double get() = x + w / 2
    val centerY: Double get() = y + h / 2
    val center: PointD get() = PointD(x + w / 2, y + h / 2)

    fun copy(): Vertex {
        val v = Vertex(id, type, name, parent, x, y, w, h)
        v.regions = regions.mapTo(mutableListOf()) { it.copy() }
        v.regionLayout = regionLayout
        v.entry = entry
        v.exit = exit
        v.doActivity = doActivity
        v.deferrable = deferrable.toMutableList()
        v.invariant = invariant
        v.submachine = submachine
        v.stereotype = stereotype
        v.ref = ref
        v.pointKind = pointKind
        v.text = text
        v.anchors = anchors.toMutableList()
        return v
    }
}

class Transition(
    var id: String = "",
    var source: String = "",
    var target: String = "",
    var kind: TransitionKind = TransitionKind.External,
) {
    var triggers: MutableList<String> = mutableListOf()
    var guard: String = ""
    var effect: String = ""
    /** Protocol state machines only. */
    var precondition: String = ""
    var postcondition: String = ""
    /** Bend points; empty for a straight line. */
    var points: MutableList<PointD> = mutableListOf()
    /** Offset of the label from its default place, or null when it was never moved. */
    var labelOffset: PointD? = null

    fun copy(): Transition {
        val t = Transition(id, source, target, kind)
        t.triggers = triggers.toMutableList()
        t.guard = guard
        t.effect = effect
        t.precondition = precondition
        t.postcondition = postcondition
        t.points = points.toMutableList()
        t.labelOffset = labelOffset
        return t
    }
}

class FsmModel {
    /** xmi:id of the state machine, used by other files to reference it. */
    var id: String = "sm"
    var name: String = ""
    var kind: MachineKind = MachineKind.Behavioral
    var context: String = ""
    var documentation: String = ""
    var regions: MutableList<Region> = mutableListOf()
    var vertices: MutableList<Vertex> = mutableListOf()
    var transitions: MutableList<Transition> = mutableListOf()

    fun copy(): FsmModel {
        val m = FsmModel()
        m.id = id
        m.name = name
        m.kind = kind
        m.context = context
        m.documentation = documentation
        m.regions = regions.mapTo(mutableListOf()) { it.copy() }
        m.vertices = vertices.mapTo(mutableListOf()) { it.copy() }
        m.transitions = transitions.mapTo(mutableListOf()) { it.copy() }
        return m
    }

    /** Fills in defaults so partially specified models are safe to use. */
    fun normalize(): FsmModel {
        if (id.isEmpty()) id = "sm"
        if (regions.isEmpty()) regions.add(Region("r_root"))
        return this
    }

    /** Entry/exit points owned by the state machine itself (placed in a top-level region). */
    fun machineConnectionPoints(): List<ConnectionPointInfo> {
        val top = regions.map { it.id }.toSet()
        return vertices
            .filter { (it.type == VertexType.EntryPoint || it.type == VertexType.ExitPoint) && it.parent in top }
            .map { ConnectionPointInfo(it.id, it.name, if (it.type == VertexType.EntryPoint) PointKind.Entry else PointKind.Exit) }
    }

    companion object {
        fun createDefault(name: String = "StateMachine"): FsmModel {
            val m = FsmModel()
            m.id = "sm"
            m.name = name
            m.regions.add(Region("r_root"))
            m.vertices.add(Vertex("v_init", VertexType.Initial, "", "r_root", 60.0, 80.0, 20.0, 20.0))
            m.vertices.add(Vertex("v_idle", VertexType.State, "Idle", "r_root", 160.0, 60.0, 140.0, 60.0))
            m.transitions.add(Transition("t_init", "v_init", "v_idle"))
            return m
        }
    }
}

class ConnectionPointInfo(val id: String, val name: String, val kind: PointKind)

/** A state machine found in the project that submachine states can reference. */
class MachineInfo(
    /** href relative to the referencing file, e.g. `Payment.fsm#sm`. */
    val href: String,
    val name: String,
    /** Path shown to the user (relative to the project when possible). */
    val file: String,
    val points: List<ConnectionPointInfo>,
)

/** What is known about a machine referenced by submachine states. */
class SubmachineInfo(
    val found: Boolean,
    val name: String = "",
    val file: String = "",
    val points: List<ConnectionPointInfo> = emptyList(),
)

/** Tree navigation over the flat model. */
class ModelIndex(val model: FsmModel) {
    val vertices = HashMap<String, Vertex>()
    val transitions = HashMap<String, Transition>()
    /** Owner state of each region; null for top-level regions. */
    val regionOwner = HashMap<String, Vertex?>()

    init {
        for (r in model.regions) regionOwner[r.id] = null
        for (v in model.vertices) {
            vertices[v.id] = v
            for (r in v.regions) regionOwner[r.id] = v
        }
        for (t in model.transitions) transitions[t.id] = t
    }

    fun vertex(id: String?): Vertex? = if (id == null) null else vertices[id]

    fun transition(id: String?): Transition? = if (id == null) null else transitions[id]

    fun isRegion(id: String?): Boolean = id != null && regionOwner.containsKey(id)

    fun isTopRegion(id: String?): Boolean = model.regions.any { it.id == id }

    /** Vertices drawn on the border of a state (entry/exit points of a state and connection point references). */
    fun isBorderVertex(v: Vertex): Boolean =
        v.type == VertexType.ConnectionPointRef ||
            ((v.type == VertexType.EntryPoint || v.type == VertexType.ExitPoint) && vertices.containsKey(v.parent))

    fun childrenOf(regionOrStateId: String): List<Vertex> = model.vertices.filter { it.parent == regionOrStateId }

    fun connectionPoints(state: Vertex): List<Vertex> = model.vertices.filter { it.parent == state.id && it.type.isPointType }

    fun outgoing(id: String): List<Transition> = model.transitions.filter { it.source == id }

    fun incoming(id: String): List<Transition> = model.transitions.filter { it.target == id }

    /** The state that directly contains [v] (through a region, or as a connection point). */
    fun ownerState(v: Vertex): Vertex? {
        if (isBorderVertex(v)) return vertex(v.parent)
        return regionOwner[v.parent]
    }

    /** Enclosing states, innermost first. */
    fun ancestors(v: Vertex): List<Vertex> {
        val output = ArrayList<Vertex>()
        val seen = hashSetOf(v.id)
        var o = ownerState(v)
        while (o != null && seen.add(o.id)) {
            output.add(o)
            o = ownerState(o)
        }
        return output
    }

    fun depth(v: Vertex): Int = ancestors(v).size

    fun isInside(v: Vertex, stateId: String): Boolean = ancestors(v).any { it.id == stateId }

    fun descendants(v: Vertex): List<Vertex> = model.vertices.filter { it !== v && isInside(it, v.id) }

    fun initialOf(regionId: String): Vertex? = model.vertices.firstOrNull { it.parent == regionId && it.type == VertexType.Initial }
}
