package com.vincegosoftware.fsmeditor.core

enum class Severity {
    Error, Warning, Info;

    val key: String get() = name.lowercase()
}

class Issue(
    /** Id of the offending vertex or transition ("" for the machine itself). */
    val id: String,
    val severity: Severity,
    val message: String,
) {
    override fun toString() = "${severity.key} $id: $message"
}

/** Checks the UML 2.5.1 well-formedness rules that apply to state machines. */
object Validation {
    /** [submachines]: what is known about the machines referenced by submachine states, keyed by href. */
    fun validate(model: FsmModel, submachines: Map<String, SubmachineInfo> = emptyMap()): List<Issue> {
        val ix = ModelIndex(model)
        val issues = ArrayList<Issue>()
        fun add(id: String, severity: Severity, message: String) {
            issues.add(Issue(id, severity, message))
        }
        val protocol = model.kind == MachineKind.Protocol

        fun infoOf(href: String?): SubmachineInfo? = if (href == null) null else submachines[href]
        fun machineName(href: String): String {
            val n = infoOf(href)?.name
            return if (!n.isNullOrEmpty()) "'$n'" else "'$href'"
        }

        // Identity
        val seen = HashSet<String>()
        for (id in model.vertices.map { it.id } + model.transitions.map { it.id }) {
            if (!seen.add(id)) add(id, Severity.Error, "Duplicate id '$id'.")
        }

        // Containment
        for (v in model.vertices) {
            if (v.type == VertexType.EntryPoint || v.type == VertexType.ExitPoint) {
                // Either on the border of a state, or a connection point of the machine itself (top-level region).
                val owner = ix.vertex(v.parent)
                val machineLevel = model.regions.any { it.id == v.parent }
                if (machineLevel) {
                    if (v.name.isEmpty()) add(v.id, Severity.Warning, "Connection points of the state machine need a name so submachine states can reference them.")
                } else if (owner == null || owner.type != VertexType.State) {
                    add(v.id, Severity.Error, "${name(v)}: entry and exit points belong on the border of a state or at the top level of the state machine.")
                } else if (owner.submachine.isNotEmpty()) {
                    add(v.id, Severity.Error, "A submachine state cannot own entry/exit points; use a connection point reference to a point of ${machineName(owner.submachine)} instead.")
                } else if (owner.regions.isEmpty()) {
                    add(v.id, Severity.Warning, "Only composite states can have entry/exit points; add a region to ${name(owner)}.")
                }
            } else if (v.type == VertexType.ConnectionPointRef) {
                val owner = ix.vertex(v.parent)
                if (owner == null || owner.type != VertexType.State || owner.submachine.isEmpty()) {
                    add(v.id, Severity.Error, "A connection point reference must be placed on the border of a submachine state.")
                }
            } else if (!ix.isRegion(v.parent)) {
                add(v.id, Severity.Error, "${name(v)} is not contained in a known region.")
            }
        }

        // Regions
        val allRegions = model.regions + model.vertices.flatMap { it.regions }
        for (r in allRegions) {
            val kids = ix.childrenOf(r.id)
            fun count(t: VertexType) = kids.filter { it.type == t }
            val owner = ix.regionOwner[r.id]
            val where = if (owner != null) "region of ${name(owner)}" else "top-level region"
            for ((type, label) in listOf(
                VertexType.Initial to "initial pseudostate",
                VertexType.ShallowHistory to "shallow history",
                VertexType.DeepHistory to "deep history",
            )) {
                val found = count(type)
                for (k in found.drop(1)) add(k.id, Severity.Error, "A $where can contain at most one $label.")
            }
            if ((count(VertexType.ShallowHistory).isNotEmpty() || count(VertexType.DeepHistory).isNotEmpty()) && owner == null) {
                for (k in kids.filter { it.type == VertexType.ShallowHistory || it.type == VertexType.DeepHistory }) {
                    add(k.id, Severity.Warning, "History pseudostates are only meaningful inside a composite state.")
                }
            }
            // Default entry of a composite state targeted directly needs an initial pseudostate.
            if (owner != null && count(VertexType.Initial).isEmpty()) {
                val entered = model.transitions.any {
                    it.target == owner.id && it.kind != TransitionKind.Internal && !ix.isInside(ix.vertex(it.source) ?: owner, owner.id)
                }
                if (entered && kids.any { it.type == VertexType.State }) {
                    val which = if (r.name.isNotEmpty()) "region '${r.name}'" else "region"
                    add(owner.id, Severity.Warning, "${name(owner)} is entered by default but its $which has no initial pseudostate.")
                }
            }
        }

        // Names unique within a region
        val byRegion = HashMap<String, HashSet<String>>()
        for (v in model.vertices) {
            if (v.type != VertexType.State || v.name.isEmpty()) continue
            val names = byRegion.getOrPut(v.parent) { HashSet() }
            if (!names.add(v.name)) add(v.id, Severity.Warning, "Another state named '${v.name}' exists in the same region.")
        }

        fun checkReference(v: Vertex, outgoing: List<Transition>, incoming: List<Transition>) {
            val owner = ix.vertex(v.parent)
            val kind = v.pointKind
            val kindText = if (kind == PointKind.Exit) "exit" else "entry"
            val info = if (owner != null && owner.submachine.isNotEmpty()) infoOf(owner.submachine) else null
            val target = if (info != null && info.found) info.points.firstOrNull { it.id == v.ref } else null
            val label = target?.name?.takeIf { it.isNotEmpty() }?.let { "'$it'" } ?: "connection point reference"
            if (v.ref.isEmpty()) {
                add(v.id, Severity.Error, "The connection point reference does not refer to an entry or exit point of the submachine.")
            } else if (owner != null && owner.submachine.isNotEmpty() && info != null && info.found) {
                if (target == null) {
                    add(v.id, Severity.Error, "${machineName(owner.submachine)} has no entry or exit point with id '${v.ref}'.")
                } else if (target.kind != kind) {
                    val targetKind = if (target.kind == PointKind.Exit) "exit" else "entry"
                    add(v.id, Severity.Error, "$label is an $targetKind point of ${machineName(owner.submachine)}, but it is referenced as an $kindText point.")
                }
            }
            if (kind == PointKind.Entry) {
                if (outgoing.isNotEmpty()) add(v.id, Severity.Error, "Entry reference $label cannot have outgoing transitions; execution continues at the entry point inside the submachine.")
                if (incoming.isEmpty()) add(v.id, Severity.Warning, "Entry reference $label has no incoming transition.")
                for (t in incoming) {
                    val src = ix.vertex(t.source)
                    if (src != null && owner != null && (src.id == owner.id || ix.isInside(src, owner.id))) {
                        add(t.id, Severity.Error, "Transitions into entry reference $label must come from outside ${name(owner)}.")
                    }
                }
            } else {
                if (incoming.isNotEmpty()) add(v.id, Severity.Error, "Exit reference $label cannot have incoming transitions; it is reached when the submachine leaves through its exit point.")
                if (outgoing.isEmpty()) add(v.id, Severity.Warning, "Exit reference $label has no outgoing transition.")
            }
            if (owner != null) {
                val dup = model.vertices.firstOrNull {
                    it !== v && it.type == VertexType.ConnectionPointRef && it.parent == owner.id && it.ref.isNotEmpty() && it.ref == v.ref
                }
                if (dup != null && model.vertices.indexOf(dup) < model.vertices.indexOf(v)) {
                    add(v.id, Severity.Warning, "${name(owner)} already references $label.")
                }
            }
        }

        // Vertices
        for (v in model.vertices) {
            val outgoing = ix.outgoing(v.id).filter { it.kind != TransitionKind.Internal }
            val incoming = ix.incoming(v.id).filter { it.kind != TransitionKind.Internal }
            when (v.type) {
                VertexType.Initial -> {
                    if (outgoing.size != 1) add(v.id, Severity.Error, "An initial pseudostate must have exactly one outgoing transition.")
                    if (incoming.isNotEmpty()) add(v.id, Severity.Error, "An initial pseudostate cannot have incoming transitions.")
                    for (t in outgoing) {
                        if (t.guard.isNotEmpty()) add(t.id, Severity.Error, "The transition leaving an initial pseudostate cannot have a guard.")
                    }
                }
                VertexType.Final -> {
                    if (outgoing.isNotEmpty()) add(v.id, Severity.Error, "A final state cannot have outgoing transitions.")
                    if (v.regions.isNotEmpty() || v.entry.isNotEmpty() || v.exit.isNotEmpty() || v.doActivity.isNotEmpty()) {
                        add(v.id, Severity.Error, "A final state cannot have regions or entry/exit/do behaviors.")
                    }
                }
                VertexType.Terminate -> {
                    if (outgoing.isNotEmpty()) add(v.id, Severity.Error, "A terminate pseudostate cannot have outgoing transitions.")
                }
                VertexType.ShallowHistory, VertexType.DeepHistory -> {
                    if (outgoing.size > 1) add(v.id, Severity.Error, "A history pseudostate can have at most one outgoing (default) transition.")
                }
                VertexType.Fork -> {
                    if (incoming.size != 1) add(v.id, Severity.Error, "A fork must have exactly one incoming transition.")
                    if (outgoing.size < 2) add(v.id, Severity.Error, "A fork must have at least two outgoing transitions.")
                    for (t in outgoing) {
                        if (t.guard.isNotEmpty() || t.triggers.isNotEmpty()) add(t.id, Severity.Error, "Transitions leaving a fork cannot have guards or triggers.")
                    }
                    val regions = outgoing.map { ix.vertex(it.target)?.parent }
                    if (regions.distinct().size != regions.size) {
                        add(v.id, Severity.Warning, "The targets of a fork should be in different orthogonal regions.")
                    }
                }
                VertexType.Join -> {
                    if (outgoing.size != 1) add(v.id, Severity.Error, "A join must have exactly one outgoing transition.")
                    if (incoming.size < 2) add(v.id, Severity.Error, "A join must have at least two incoming transitions.")
                    for (t in incoming) {
                        if (t.guard.isNotEmpty() || t.triggers.isNotEmpty()) add(t.id, Severity.Error, "Transitions entering a join cannot have guards or triggers.")
                    }
                    val regions = incoming.map { ix.vertex(it.source)?.parent }
                    if (regions.distinct().size != regions.size) {
                        add(v.id, Severity.Warning, "The sources of a join should be in different orthogonal regions.")
                    }
                }
                VertexType.Choice, VertexType.Junction -> {
                    val kind = v.type.key
                    if (incoming.isEmpty()) add(v.id, Severity.Error, "A $kind must have at least one incoming transition.")
                    if (outgoing.isEmpty()) add(v.id, Severity.Error, "A $kind must have at least one outgoing transition.")
                    if (outgoing.size > 1) {
                        if (outgoing.any { it.guard.trim().isEmpty() }) {
                            add(v.id, Severity.Warning, "Every branch of a $kind with several outgoing transitions should have a guard.")
                        }
                        if (outgoing.none { it.guard.trim() == "else" }) {
                            add(v.id, Severity.Info, "Consider an [else] branch so the $kind can always be left.")
                        }
                    }
                }
                VertexType.EntryPoint -> {
                    val owner = ix.vertex(v.parent)
                    if (owner == null && outgoing.isEmpty()) {
                        add(v.id, Severity.Warning, "Entry point ${name(v)} of the state machine has no outgoing transition.")
                    }
                    if (owner != null) {
                        for (t in outgoing) {
                            val target = ix.vertex(t.target)
                            if (target != null && !ix.isInside(target, owner.id)) {
                                add(t.id, Severity.Error, "Transitions leaving entry point ${name(v)} must target a vertex inside ${name(owner)}.")
                            }
                        }
                    }
                }
                VertexType.ExitPoint -> {
                    val owner = ix.vertex(v.parent)
                    if (owner == null) {
                        for (t in outgoing) {
                            add(t.id, Severity.Error, "Exit point ${name(v)} of the state machine cannot have outgoing transitions; the referencing submachine state continues from its connection point reference.")
                        }
                    } else {
                        for (t in outgoing) {
                            val target = ix.vertex(t.target)
                            if (target != null && (target.id == owner.id || ix.isInside(target, owner.id))) {
                                add(t.id, Severity.Error, "Transitions leaving exit point ${name(v)} must target a vertex outside ${name(owner)}.")
                            }
                        }
                    }
                }
                VertexType.ConnectionPointRef -> checkReference(v, outgoing, incoming)
                VertexType.State -> {
                    if (v.submachine.isNotEmpty()) {
                        val info = infoOf(v.submachine)
                        if (info != null && !info.found) add(v.id, Severity.Error, "The referenced state machine '${v.submachine}' was not found.")
                    }
                    if (v.submachine.isNotEmpty() && v.regions.isNotEmpty()) {
                        add(v.id, Severity.Error, "Submachine state ${name(v)} cannot also own regions.")
                    }
                    if (protocol && (v.entry.isNotEmpty() || v.exit.isNotEmpty() || v.doActivity.isNotEmpty())) {
                        add(v.id, Severity.Error, "States of a protocol state machine cannot have entry/exit/do behaviors (${name(v)}).")
                    }
                }
                VertexType.Comment -> {}
            }
        }

        // Transitions
        for (t in model.transitions) {
            val s = ix.vertex(t.source)
            val g = ix.vertex(t.target)
            if (s == null) add(t.id, Severity.Error, "Transition source '${t.source}' does not exist.")
            if (g == null) add(t.id, Severity.Error, "Transition target '${t.target}' does not exist.")
            if (s == null || g == null) continue
            if (s.type == VertexType.Comment || g.type == VertexType.Comment) {
                add(t.id, Severity.Error, "Comments cannot be connected by transitions.")
                continue
            }
            if ((s.type.isPseudostate || s.type == VertexType.ConnectionPointRef) && t.triggers.isNotEmpty()) {
                add(t.id, Severity.Error, "Transitions leaving a pseudostate cannot have triggers (from ${name(s)}).")
            }
            if (t.kind == TransitionKind.Internal && (s !== g || s.type != VertexType.State)) {
                add(t.id, Severity.Error, "An internal transition must start and end on the same state.")
            }
            if (t.kind == TransitionKind.Local) {
                val composite = s.type == VertexType.State && s.regions.isNotEmpty()
                val container = if (s.type == VertexType.EntryPoint) s.parent else s.id
                if (!(composite || s.type == VertexType.EntryPoint) || !(g === s || ix.isInside(g, container))) {
                    add(t.id, Severity.Error, "A local transition must go from a composite state to a vertex it contains.")
                }
            }
            if (protocol) {
                if (t.effect.isNotEmpty()) add(t.id, Severity.Error, "Protocol transitions cannot have effects.")
                if (t.guard.isNotEmpty()) add(t.id, Severity.Warning, "Use a precondition instead of a guard in a protocol state machine.")
            } else if (t.precondition.isNotEmpty() || t.postcondition.isNotEmpty()) {
                add(t.id, Severity.Warning, "Pre/postconditions only apply to protocol state machines and are ignored.")
            }
            if (s.type == VertexType.State && g.type == VertexType.State && t.triggers.isEmpty() && t.guard.isEmpty() && s !== g && s.regions.isEmpty()) {
                if (ix.outgoing(s.id).count { it.kind != TransitionKind.Internal && it.triggers.isEmpty() } > 1) {
                    add(t.id, Severity.Warning, "${name(s)} has several completion transitions without triggers or guards.")
                }
            }
        }

        checkText(model, ix, ::add)
        checkReachability(model, ix, ::add)
        return issues
    }

    /** `'Name'`, or the kind of the vertex when it has no name. */
    fun name(v: Vertex?): String {
        if (v == null) return "?"
        return if (v.name.isNotEmpty()) "'${v.name}'" else v.type.label
    }

    /** Behaviors and conditions are argument-less function calls; triggers are event names or after(...). */
    private fun checkText(model: FsmModel, ix: ModelIndex, add: (String, Severity, String) -> Unit) {
        fun report(id: String, what: String, r: Check<*>) {
            if (!r.ok) add(id, Severity.Error, "$what: ${r.error}")
        }
        for (v in model.vertices) {
            if (v.type != VertexType.State) continue
            val n = name(v)
            report(v.id, "entry of $n", Expressions.checkActions(v.entry))
            report(v.id, "exit of $n", Expressions.checkActions(v.exit))
            report(v.id, "do of $n", Expressions.checkActions(v.doActivity))
            report(v.id, "Invariant of $n", Expressions.checkCondition(v.invariant))
            if (v.stereotype.isNotEmpty()) report(v.id, "Stereotype of $n", Expressions.checkName(v.stereotype))
            for (d in v.deferrable) report(v.id, "Deferrable event of $n", Expressions.checkEvent(d))
        }
        for (t in model.transitions) {
            val s = ix.vertex(t.source)
            for (trig in t.triggers) report(t.id, "Trigger", Expressions.checkTrigger(trig))
            report(t.id, "Guard", Expressions.checkGuard(t.guard, Labels.allowsElse(s)))
            report(t.id, "Effect", Expressions.checkActions(t.effect))
            report(t.id, "Precondition", Expressions.checkCondition(t.precondition))
            report(t.id, "Postcondition", Expressions.checkCondition(t.postcondition))
            if (t.triggers.any { Expressions.timeTriggerMs(it) != null } && s != null && s.type != VertexType.State) {
                add(t.id, Severity.Error, "Time triggers (after) can only be used on transitions leaving a state.")
            }
        }
    }

    private fun checkReachability(model: FsmModel, ix: ModelIndex, add: (String, Severity, String) -> Unit) {
        val rootInitial = ix.initialOf(if (model.regions.isNotEmpty()) model.regions[0].id else "")
        // The machine can also be entered through its own entry points (from a referencing submachine state).
        val machineEntries = model.vertices.filter { v -> v.type == VertexType.EntryPoint && model.regions.any { it.id == v.parent } }
        if (rootInitial == null && machineEntries.isEmpty()) {
            if (model.vertices.any { it.type == VertexType.State }) {
                add("", Severity.Warning, "The top-level region has no initial pseudostate.")
            }
            return
        }
        val reached = HashSet<String>()
        val queue = ArrayDeque<Vertex>()
        fun visit(v: Vertex?) {
            if (v == null || !reached.add(v.id)) return
            queue.addLast(v)
        }
        visit(rootInitial)
        machineEntries.forEach(::visit)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            // Entering a vertex enters its enclosing states and their other orthogonal regions.
            for (a in ix.ancestors(v)) visit(a)
            if (v.type == VertexType.State) {
                for (r in v.regions) visit(ix.initialOf(r.id))
                for (cp in ix.connectionPoints(v)) {
                    if (cp.type == VertexType.ExitPoint || (cp.type == VertexType.ConnectionPointRef && cp.pointKind == PointKind.Exit)) visit(cp)
                }
            }
            for (t in ix.outgoing(v.id)) visit(ix.vertex(t.target))
        }
        for (v in model.vertices) {
            if (v.type == VertexType.State && v.id !in reached) {
                add(v.id, Severity.Warning, "State ${name(v)} can never be entered.")
            }
        }
    }
}
