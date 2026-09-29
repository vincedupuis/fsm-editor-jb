package com.vincegosoftware.fsmeditor.core

/** The text shown on transitions and in state compartments, and parsing it back. */
object Labels {
    /** `t1, t2 [guard] / effect`, or `[pre] t / [post]` for protocol machines. */
    fun transitionLabel(model: FsmModel, t: Transition): String {
        val trig = t.triggers.joinToString(", ")
        if (model.kind == MachineKind.Protocol) {
            var s = ""
            if (t.precondition.isNotEmpty()) s += "[${t.precondition}] "
            s += trig
            if (t.postcondition.isNotEmpty()) s += " / [${t.postcondition}]"
            return s.trim()
        }
        var s = trig
        if (t.guard.isNotEmpty()) s += " [${t.guard}]"
        if (t.effect.isNotEmpty()) s += " / ${t.effect}"
        return s.trim()
    }

    /** May transitions leaving [source] use the [else] guard? */
    fun allowsElse(source: Vertex?): Boolean =
        source != null && (source.type == VertexType.Choice || source.type == VertexType.Junction)

    private val PROTOCOL_LABEL = Regex("^\\s*(?:\\[([^\\]]*)\\])?([^/]*?)\\s*(?:/\\s*\\[([^\\]]*)\\])?\\s*$")
    private val TRAILING_GUARD = Regex("\\[([^\\]]*)\\]\\s*$")

    /**
     * Parses a transition label into [t]. Returns an error message and leaves
     * [t] untouched when any part breaks the grammar.
     */
    fun applyLabel(model: FsmModel, t: Transition, text: String?, source: Vertex?): String? {
        val label = text ?: ""
        if (model.kind == MachineKind.Protocol) {
            val m = PROTOCOL_LABEL.find(label) ?: return "Write the label as [precondition] event / [postcondition]."
            val pre = Expressions.checkCondition(m.groups[1]?.value ?: "")
            if (!pre.ok) return "Precondition: ${pre.error}"
            val ptrig = Expressions.checkList(m.groups[2]?.value ?: "", Expressions::checkTrigger)
            if (!ptrig.ok) return "Trigger: ${ptrig.error}"
            val post = Expressions.checkCondition(m.groups[3]?.value ?: "")
            if (!post.ok) return "Postcondition: ${post.error}"
            t.precondition = pre.value
            t.triggers = ptrig.value.toMutableList()
            t.postcondition = post.value
            return null
        }
        val slash = indexOutsideBrackets(label, '/')
        val head = if (slash >= 0) label.substring(0, slash) else label
        val g = TRAILING_GUARD.find(head)
        val trig = Expressions.checkList(if (g != null) head.substring(0, g.range.first) else head, Expressions::checkTrigger)
        if (!trig.ok) return "Trigger: ${trig.error}"
        val guard = Expressions.checkGuard(g?.groupValues?.get(1) ?: "", allowsElse(source))
        if (!guard.ok) return "Guard: ${guard.error}"
        val effect = Expressions.checkActions(if (slash >= 0) label.substring(slash + 1) else "")
        if (!effect.ok) return "Effect: ${effect.error}"
        t.triggers = trig.value.toMutableList()
        t.guard = guard.value
        t.effect = effect.value
        return null
    }

    private fun indexOutsideBrackets(s: String, ch: Char): Int {
        var depth = 0
        for (i in s.indices) {
            when {
                s[i] == '[' -> depth++
                s[i] == ']' -> depth = if (depth > 0) depth - 1 else 0
                s[i] == ch && depth == 0 -> return i
            }
        }
        return -1
    }

    fun internalTransitions(model: FsmModel, v: Vertex): List<Transition> =
        model.transitions.filter { it.kind == TransitionKind.Internal && it.source == v.id && it.target == v.id }

    /** Lines of the internal activities compartment of a state. */
    fun activityLines(model: FsmModel, v: Vertex): List<String> {
        val output = ArrayList<String>()
        if (v.entry.isNotEmpty()) output.add("entry / ${v.entry}")
        if (v.exit.isNotEmpty()) output.add("exit / ${v.exit}")
        if (v.doActivity.isNotEmpty()) output.add("do / ${v.doActivity}")
        for (d in v.deferrable) output.add("$d / defer")
        for (t in internalTransitions(model, v)) {
            val label = transitionLabel(model, t)
            output.add(label.ifEmpty { "(internal)" })
        }
        return output
    }
}
