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

/**
 * White space as JavaScript's `trim()` and regex `\s` see it (the reference edition's grammar):
 * U+0009–U+000D, U+0020, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000
 * and U+FEFF. Kotlin's [Char.isWhitespace] differs (U+001C–U+001F in, U+FEFF out), and Java's
 * regex `\s` is ASCII only.
 */
object JsWhitespace {
    /** The set as a regex character class, in place of `\s`. */
    const val CLASS = "[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /** Is [c] white space for JavaScript? */
    operator fun contains(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F' || c == '\u3000' || c == '\uFEFF'

    /** Like JavaScript's `String.prototype.trim()`; null gives "". */
    fun trim(text: String?): String = (text ?: "").trim { it in this }

    /** Splits on runs of white space, dropping empty items, like `split(/\s+/).filter(Boolean)`. */
    fun split(text: String?): MutableList<String> {
        val output = ArrayList<String>()
        val t = text ?: ""
        var i = 0
        while (i < t.length) {
            while (i < t.length && t[i] in this) i++
            var j = i
            while (j < t.length && t[j] !in this) j++
            if (j > i) output.add(t.substring(i, j))
            i = j
        }
        return output
    }
}

/**
 * The text grammar's checks (behaviors, conditions, triggers, events, names): fsm-cli's rules and messages, with the
 * text an editor keeps for valid input, normalized; fsm-editor-common's `checkText` ([Common]).
 */
object Expressions {
    private fun text(kind: String, text: String?, allowElse: Boolean = false): Check<String> {
        val r = Common.call(
            "checkText",
            Common.args("kind" to Common.str(kind), "text" to Common.str(text ?: ""), "allowElse" to allowElse.toString()),
        ) as Map<*, *>
        return if (r["ok"] == true) Check.success(r["value"] as? String ?: "") else Check.fail(ModelJson.str(r, "error"))
    }

    /** A condition built from calls, !, && and ||. Empty text is allowed (no condition). */
    fun checkCondition(text: String?): Check<String> = text("condition", text)

    /** A transition's guard: a condition, or `else` when [allowElse] is set. */
    fun checkGuard(text: String?, allowElse: Boolean): Check<String> = text("guard", text, allowElse)

    /** A behavior: calls separated by `;`. Empty text is allowed (no behavior). */
    fun checkActions(text: String?): Check<String> = text("actions", text)

    /** An event name (triggers and deferrable events). */
    fun checkEvent(text: String?): Check<String> = text("event", text)

    /** A plain name (letters, digits and _), e.g. a stereotype. */
    fun checkName(text: String?): Check<String> = text("name", text)

    /** One trigger: an event name, or after(<number><ms|s|m|h>). */
    fun checkTrigger(text: String?): Check<String> = text("trigger", text)

    /** A comma-separated list checked item by item; the normalized items. */
    fun checkList(text: String?, check: (String) -> Check<String>): Check<List<String>> {
        val output = mutableListOf<String>()
        for (item in (text ?: "").split(',').map { JsWhitespace.trim(it) }.filter { it.isNotEmpty() }) {
            val r = check(item)
            if (!r.ok) return Check.fail(r.error ?: "")
            output.add(r.value)
        }
        return Check.success(output)
    }
}
