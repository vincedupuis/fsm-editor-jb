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

/**
 * The UML 2.5.1 well-formedness rules that apply to state machines, names and references, the text grammar and
 * reachability: fsm-cli's rules and messages, run by fsm-editor-common ([Common]).
 */
object Validation {
    /** [submachines]: what is known about the machines referenced by submachine states, keyed by href. */
    fun validate(model: FsmModel, submachines: Map<String, SubmachineInfo> = emptyMap()): List<Issue> {
        val subs = submachines.entries.joinToString(",", "{", "}") { (href, i) ->
            PlainJson.quote(href) + ":" + Common.args(
                "found" to i.found.toString(),
                "name" to Common.str(i.name),
                "points" to i.points.joinToString(",", "[", "]") { p ->
                    Common.args("id" to Common.str(p.id), "name" to Common.str(p.name), "kind" to Common.str(if (p.kind == PointKind.Exit) "exit" else "entry"))
                },
            )
        }
        val r = Common.call("validate", Common.args("model" to ModelJson.write(model), "submachines" to subs)) as List<*>
        return r.filterIsInstance<Map<*, *>>().map {
            val severity = when (ModelJson.str(it, "severity")) {
                "error" -> Severity.Error
                "warning" -> Severity.Warning
                else -> Severity.Info
            }
            Issue(ModelJson.str(it, "id"), severity, ModelJson.str(it, "message"))
        }
    }
}
