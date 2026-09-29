package com.vincegosoftware.fsmeditor.core

/** Result of checking a piece of text: the normalized value, or an error message. */
class Check<out T> private constructor(val ok: Boolean, private val result: T?, val error: String?) {
    /** The normalized value; only valid when [ok]. */
    @Suppress("UNCHECKED_CAST")
    val value: T get() = result as T

    companion object {
        fun <T> success(value: T): Check<T> = Check(true, value, null)
        fun <T> fail(error: String): Check<T> = Check(false, null, error)
    }
}

enum class ConditionOp { Call, Not, And, Or }

/** A condition tree: a call, a negation, or a conjunction/disjunction. */
class Condition(
    val op: ConditionOp,
    /** Function name, for [ConditionOp.Call]. */
    val name: String = "",
    /** Operand of [ConditionOp.Not], or operands of And/Or. */
    val args: List<Condition> = emptyList(),
) {
    /** Parentheses written around this condition (kept in the normalized text only). */
    var parens: Int = 0
}

/**
 * Grammar of the text a state machine may contain. The state machine holds
 * no variables: behaviors and conditions are calls to functions without
 * arguments; the parentheses only mark the call.
 * ```
 *   actions   := call (';' call)*                      entry, exit, do, effect
 *   condition := or                                     guard, invariant, pre/postcondition
 *   or        := and ('||' and)*
 *   and       := unary ('&&' unary)*
 *   unary     := '!' unary | call | '(' or ')'
 *   guard     := 'else' | condition                     'else' only on choice/junction branches
 *   trigger   := event | 'after(' number unit ')'       unit: ms, s, m, h
 *   event     := name                                   also used for deferrable events
 *   call      := name '()'
 *   stereotype:= name
 *   name      := [A-Za-z_][A-Za-z0-9_]*
 * ```
 */
object Expressions {
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val TIME = Regex("^after\\s*\\(\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s|m|h)\\s*\\)$")
    private val AFTER_WORD = Regex("^after\\b")
    private val WHEN_WORD = Regex("^when\\b")

    private class SyntaxProblem(message: String) : Exception(message)

    /** Kind: "name", "(", ")", "!", ";", "&&", "||". */
    private class Token(val kind: String, val value: String? = null) {
        val display: String get() = value ?: kind
    }

    private fun isNameStart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_'
    private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9'

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
            } else if (isNameStart(c)) {
                var j = i + 1
                while (j < text.length && isNameChar(text[j])) j++
                tokens.add(Token("name", text.substring(i, j)))
                i = j
            } else if (text.startsWith("&&", i) || text.startsWith("||", i)) {
                tokens.add(Token(text.substring(i, i + 2)))
                i += 2
            } else if (c in "()!;") {
                tokens.add(Token(c.toString()))
                i++
            } else if (c == '.') {
                throw SyntaxProblem("Dotted names are not allowed: use a single function name like doThing().")
            } else if (c in "<>=+-*/%&|" || c in '0'..'9') {
                throw SyntaxProblem(
                    "'$c' is not allowed: the state machine has no variables, so use function calls like isReady() combined with !, && and ||.",
                )
            } else {
                throw SyntaxProblem("Unexpected character '$c'.")
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var i = 0

        fun peek(): Token? = tokens.getOrNull(i)

        fun next(): Token? = tokens.getOrNull(i)?.also { i++ }

        fun peekIs(kind: String) = peek()?.kind == kind

        fun callName(): String {
            val tok = next()
            if (tok == null || tok.kind != "name") {
                throw SyntaxProblem(if (tok != null) "Expected a function call, found '${tok.kind}'." else "Expected a function call.")
            }
            val name = tok.value!!
            val open = next()
            if (open == null || open.kind != "(") throw SyntaxProblem("'$name' must be a function call: write $name().")
            val close = next()
            if (close == null || close.kind != ")") throw SyntaxProblem("Functions take no arguments: write $name().")
            return name
        }

        fun or(): Condition {
            val args = mutableListOf(and())
            while (peekIs("||")) {
                next()
                args.add(and())
            }
            return if (args.size == 1) args[0] else Condition(ConditionOp.Or, args = args)
        }

        private fun and(): Condition {
            val args = mutableListOf(unary())
            while (peekIs("&&")) {
                next()
                args.add(unary())
            }
            return if (args.size == 1) args[0] else Condition(ConditionOp.And, args = args)
        }

        private fun unary(): Condition {
            if (peekIs("!")) {
                next()
                return Condition(ConditionOp.Not, args = listOf(unary()))
            }
            if (peekIs("(")) {
                next()
                val inner = or()
                val close = next()
                if (close == null || close.kind != ")") throw SyntaxProblem("Missing closing parenthesis.")
                // Remember the grouping so the normalized text keeps its parentheses.
                inner.parens++
                return inner
            }
            return Condition(ConditionOp.Call, callName())
        }

        fun end() {
            val tok = peek()
            if (tok != null) throw SyntaxProblem("Unexpected '${tok.display}'.")
        }
    }

    private fun <T> guarded(fn: () -> T): Check<T> =
        try {
            Check.success(fn())
        } catch (e: SyntaxProblem) {
            Check.fail(e.message!!)
        }

    /** Normalized text of a condition, keeping the parentheses the author wrote. */
    fun format(c: Condition): String {
        val s = when (c.op) {
            ConditionOp.Call -> c.name + "()"
            ConditionOp.Not -> "!" + format(c.args[0])
            ConditionOp.And -> c.args.joinToString(" && ") { format(it) }
            ConditionOp.Or -> c.args.joinToString(" || ") { format(it) }
        }
        return "(".repeat(c.parens) + s + ")".repeat(c.parens)
    }

    private fun trim(text: String?) = (text ?: "").trim()

    /** Condition built from calls, !, && and ||. Empty text is allowed (no condition). */
    fun checkCondition(text: String?): Check<String> {
        val r = parseCondition(text)
        if (!r.ok) return Check.fail(r.error!!)
        return Check.success(r.value?.let { format(it) } ?: "")
    }

    /** Condition as a tree. Empty text gives null (no condition). */
    fun parseCondition(text: String?): Check<Condition?> {
        val s = trim(text)
        if (s.isEmpty()) return Check.success(null)
        return guarded {
            val p = Parser(tokenize(s))
            val value = p.or()
            p.end()
            value
        }
    }

    /** Transition guard: a condition, or 'else' when [allowElse] is set. */
    fun checkGuard(text: String?, allowElse: Boolean): Check<String> {
        val s = trim(text)
        if (s == "else") {
            return if (allowElse) Check.success("else")
            else Check.fail("[else] is only allowed on transitions leaving a choice or junction.")
        }
        return checkCondition(s)
    }

    /** Behavior: calls separated by ';'. Empty text is allowed (no behavior). */
    fun checkActions(text: String?): Check<String> {
        val r = parseActions(text)
        return if (r.ok) Check.success(r.value.joinToString("; ") { "$it()" }) else Check.fail(r.error!!)
    }

    private val TRAILING_SEMICOLON = Regex(";\\s*$")

    /** Names of the functions a behavior calls, in order. */
    fun parseActions(text: String?): Check<List<String>> {
        val s = TRAILING_SEMICOLON.replace(trim(text), "")
        if (s.isEmpty()) return Check.success(emptyList())
        return guarded {
            val p = Parser(tokenize(s))
            val calls = mutableListOf(p.callName())
            while (p.peekIs(";")) {
                p.next()
                calls.add(p.callName())
            }
            val tok = p.peek()
            if (tok != null) {
                val k = tok.kind
                throw SyntaxProblem(
                    if (k == "&&" || k == "||" || k == "!") "Actions cannot use !, && or ||: separate several calls with ;"
                    else "Unexpected '${tok.display}': separate several calls with ;",
                )
            }
            calls
        }
    }

    /** Event name (used for triggers and deferrable events). */
    fun checkEvent(text: String?): Check<String> {
        val s = trim(text)
        if (s.isEmpty()) return Check.fail("Empty event name.")
        if (AFTER_WORD.containsMatchIn(s)) return Check.fail("A time event cannot be deferred; use a named event.")
        if (s.contains("(")) return Check.fail("Events are plain names without (): write ${stripCall(s)}.")
        if (!NAME.matches(s)) return Check.fail("'$s' is not a valid event name (letters, digits and _ only).")
        return Check.success(s)
    }

    /** A plain name (letters, digits and _), e.g. a stereotype. Empty text is allowed. */
    fun checkName(text: String?): Check<String> {
        val s = trim(text)
        if (s.isEmpty()) return Check.success("")
        if (!NAME.matches(s)) return Check.fail("'$s' is not a valid name: use letters, digits and _ only, not starting with a digit.")
        return Check.success(s)
    }

    /** One trigger: an event name, or after(<number><ms|s|m|h>). */
    fun checkTrigger(text: String?): Check<String> {
        val s = trim(text)
        if (AFTER_WORD.containsMatchIn(s)) {
            val m = TIME.find(s)
                ?: return Check.fail("Time triggers are written after(<number><unit>) with unit ms, s, m or h, e.g. after(500ms) or after(2s).")
            if (m.groupValues[1].toDouble() <= 0) {
                return Check.fail("The time of an after() trigger must be greater than zero.")
            }
            return Check.success("after(${m.groupValues[1]}${m.groupValues[2]})")
        }
        if (WHEN_WORD.containsMatchIn(s)) return Check.fail("Change events (when) are not supported: use a named event or after(...).")
        if (s.isEmpty()) return Check.fail("Empty trigger.")
        if (s.contains("(")) return Check.fail("Events are plain names without (): write ${stripCall(s)}. The only exception is after(...).")
        if (!NAME.matches(s)) return Check.fail("'$s' is not a valid event name (letters, digits and _ only).")
        return Check.success(s)
    }

    /** Comma-separated list checked item by item; returns the normalized items. */
    fun checkList(text: String?, check: (String) -> Check<String>): Check<List<String>> {
        val output = ArrayList<String>()
        for (raw in (text ?: "").split(',')) {
            val item = raw.trim()
            if (item.isEmpty()) continue
            val r = check(item)
            if (!r.ok) return Check.fail(r.error!!)
            output.add(r.value)
        }
        return Check.success(output)
    }

    /** Parses an after() trigger into milliseconds, or returns null. */
    fun timeTriggerMs(trigger: String?): Double? {
        val m = TIME.find(trim(trigger)) ?: return null
        val factor = when (m.groupValues[2]) {
            "ms" -> 1.0
            "s" -> 1000.0
            "m" -> 60000.0
            else -> 3600000.0
        }
        return m.groupValues[1].toDouble() * factor
    }

    private val CALL_TAIL = Regex("\\s*\\(.*$")

    private fun stripCall(s: String) = CALL_TAIL.replace(s, "")
}
