package com.vincegosoftware.fsmeditor.core

enum class JsonKind { Null, Bool, Number, String, Array, Object }

/** A JSON value and the line it starts on (1-based). Objects hold their fields in order, arrays their items. */
class JsonNode(val kind: JsonKind, val value: Any?, val line: Int) {
    @Suppress("UNCHECKED_CAST")
    val fields: List<JsonField>? get() = if (kind == JsonKind.Object) value as List<JsonField> else null

    @Suppress("UNCHECKED_CAST")
    val items: List<JsonNode>? get() = if (kind == JsonKind.Array) value as List<JsonNode> else null
    val str: String? get() = if (kind == JsonKind.String) value as String else null
    val number: Double? get() = if (kind == JsonKind.Number) value as Double else null

    /** The field [key] of an object, or null. */
    fun field(key: String): JsonField? = fields?.firstOrNull { it.key == key }
}

/** A field of an object; [line] is the line of its name. */
class JsonField(val key: String, val line: Int, val value: JsonNode)

class JsonSyntaxException(val line: Int, message: String) : Exception(message)

/**
 * A value to write ([Json.write]): its fields in order, a field set again keeping its place (as JavaScript objects
 * do). Values are strings, numbers (Double), booleans, lists and [OutObject]s; a null value is left out.
 */
class OutObject {
    private val keys = ArrayList<String>()
    private val values = HashMap<String, Any?>()

    operator fun get(key: String): Any? = values[key]

    operator fun set(key: String, value: Any?) {
        if (key !in values) keys.add(key)
        values[key] = value
    }

    /** The fields with a value, in order. */
    val entries: List<Pair<String, Any>> get() = keys.mapNotNull { k -> values[k]?.let { k to it } }

    val count: Int get() = keys.count { values[it] != null }

    /** Copies the fields of [other] into this one (`Object.assign`). */
    fun assign(other: OutObject) {
        for (k in other.keys) this[k] = other.values[k]
    }
}

/**
 * A JSON reader that keeps the line of every value and of every field, for the `.fsm` and `.fsm.layout` files: their
 * problems are reported with their lines. Strict JSON (RFC 8259), plus a byte order mark at the start, which is
 * skipped; a field given twice in one object is an error. The same reader as fsm-cli's `src/json.rs` and the VS Code
 * edition's `src/json.ts`: the same values and errors, at the same lines.
 */
object Json {
    /** How a character is shown in a message: itself, or `\uXXXX` for control and invisible characters. */
    private fun show(code: Int): String {
        if (code < 0x20 || code in 0x7f..0x9f || code == 0xfeff) return "\\u" + "%04x".format(code)
        return String(Character.toChars(code))
    }

    private fun isDigit(text: String, i: Int) = i < text.length && text[i] in '0'..'9'

    /** Parses a JSON document; throws [JsonSyntaxException]. */
    fun parse(text: String): JsonNode = Parser(text).document()

    private class Parser(private val text: String) {
        private var i = if (text.isNotEmpty() && text[0] == '﻿') 1 else 0
        private var line = 1

        private fun fail(message: String, at: Int = line) = JsonSyntaxException(at, message)

        private fun unexpected(): JsonSyntaxException {
            if (i >= text.length) return fail("Unexpected end of the file.")
            val c = text[i]
            val shown = if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) show(Character.toCodePoint(c, text[i + 1]))
            else if (Character.isSurrogate(c)) c.toString()
            else show(c.code)
            return fail("Unexpected character '$shown'.")
        }

        private fun at(i: Int): Char? = if (i < text.length) text[i] else null

        private fun space() {
            while (i < text.length) {
                val c = text[i]
                if (c == '\n') line++
                else if (c != ' ' && c != '\t' && c != '\r') return
                i++
            }
        }

        private fun hex4(): Int {
            if (i + 4 > text.length) throw fail("Invalid escape in a text.")
            val h = text.substring(i, i + 4)
            if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw fail("Invalid escape in a text.")
            i += 4
            return h.toInt(16)
        }

        private fun string(): String {
            i++ // the opening quote
            val output = StringBuilder()
            while (true) {
                if (i >= text.length) throw fail("Unexpected end of the file in a text.")
                val c = text[i]
                if (c == '"') {
                    i++
                    return output.toString()
                }
                if (c == '\\') {
                    val e = at(i + 1)
                    i += 2
                    when (e) {
                        '"' -> output.append('"')
                        '\\' -> output.append('\\')
                        '/' -> output.append('/')
                        'b' -> output.append('\b')
                        'f' -> output.append('\u000C')
                        'n' -> output.append('\n')
                        'r' -> output.append('\r')
                        't' -> output.append('\t')
                        'u' -> {
                            val u = hex4()
                            if (u in 0xd800 until 0xdc00) {
                                if (i + 2 > text.length || text[i] != '\\' || text[i + 1] != 'u') throw fail("Invalid escape in a text.")
                                i += 2
                                val low = hex4()
                                if (low < 0xdc00 || low >= 0xe000) throw fail("Invalid escape in a text.")
                                output.append(u.toChar()).append(low.toChar())
                            } else if (u in 0xdc00 until 0xe000) {
                                throw fail("Invalid escape in a text.")
                            } else output.append(u.toChar())
                        }
                        else -> throw fail("Invalid escape in a text.")
                    }
                    continue
                }
                if (c.code < 0x20) throw fail("Control character '${show(c.code)}' in a text: write it as an escape.")
                output.append(c)
                i++
            }
        }

        private fun number(): Double {
            val start = i
            if (at(i) == '-') i++
            if (!isDigit(text, i)) throw fail("Invalid number.")
            if (text[i] == '0') {
                i++
                if (isDigit(text, i)) throw fail("Invalid number.")
            } else {
                while (isDigit(text, i)) i++
            }
            if (at(i) == '.') {
                i++
                if (!isDigit(text, i)) throw fail("Invalid number.")
                while (isDigit(text, i)) i++
            }
            if (at(i) == 'e' || at(i) == 'E') {
                i++
                if (at(i) == '+' || at(i) == '-') i++
                if (!isDigit(text, i)) throw fail("Invalid number.")
                while (isDigit(text, i)) i++
            }
            val n = Num.parseJs(text.substring(start, i))
            if (n.isNaN() || n.isInfinite()) throw fail("Invalid number.")
            return n
        }

        private fun word(w: String, v: Any?): Any? {
            if (text.startsWith(w, i)) {
                i += w.length
                return v
            }
            throw unexpected()
        }

        private fun value(depth: Int): JsonNode {
            if (depth > 200) throw fail("The document is nested too deeply.")
            space()
            val at = line
            if (i >= text.length) throw unexpected()
            val c = text[i]
            if (c == '{') {
                i++
                val fields = ArrayList<JsonField>()
                space()
                if (at(i) == '}') i++
                else {
                    while (true) {
                        space()
                        if (at(i) != '"') throw unexpected()
                        val keyLine = line
                        val key = string()
                        if (fields.any { it.key == key }) throw fail("Field '$key' is given twice.", keyLine)
                        space()
                        if (at(i) != ':') throw unexpected()
                        i++
                        val v = value(depth + 1)
                        fields.add(JsonField(key, keyLine, v))
                        space()
                        if (at(i) == ',') i++
                        else if (at(i) == '}') {
                            i++
                            break
                        } else throw unexpected()
                    }
                }
                return JsonNode(JsonKind.Object, fields, at)
            }
            if (c == '[') {
                i++
                val items = ArrayList<JsonNode>()
                space()
                if (at(i) == ']') i++
                else {
                    while (true) {
                        items.add(value(depth + 1))
                        space()
                        if (at(i) == ',') i++
                        else if (at(i) == ']') {
                            i++
                            break
                        } else throw unexpected()
                    }
                }
                return JsonNode(JsonKind.Array, items, at)
            }
            if (c == '"') return JsonNode(JsonKind.String, string(), at)
            if (c == 't') return JsonNode(JsonKind.Bool, word("true", true), at)
            if (c == 'f') return JsonNode(JsonKind.Bool, word("false", false), at)
            if (c == 'n') return JsonNode(JsonKind.Null, word("null", null), at)
            if (c == '-' || c in '0'..'9') return JsonNode(JsonKind.Number, number(), at)
            throw unexpected()
        }

        fun document(): JsonNode {
            val root = value(0)
            space()
            if (i < text.length) throw unexpected()
            return root
        }
    }

    // ---------------------------------------------------------------- plain values

    /** A node as a value to write (objects with their fields in order). */
    fun plain(n: JsonNode): Any? = when (n.kind) {
        JsonKind.Array -> n.items!!.map { plain(it) }
        JsonKind.Object -> OutObject().also { o -> for (f in n.fields!!) o[f.key] = plain(f.value) }
        else -> n.value
    }

    /** A value as JavaScript's `JSON.stringify` writes it, compactly (texts, numbers, booleans, lists, objects). */
    fun stringify(v: Any?): String = when (v) {
        null -> "null"
        is String -> stringifyText(v)
        is Boolean -> if (v) "true" else "false"
        is Double -> Num.format(v)
        is Int -> v.toString()
        is OutObject -> "{" + v.entries.joinToString(",") { (k, x) -> stringifyText(k) + ":" + stringify(x) } + "}"
        is List<*> -> "[" + v.joinToString(",") { stringify(it) } + "]"
        else -> throw IllegalArgumentException("Not a JSON value: ${v::class}")
    }

    private fun stringifyText(s: String): String {
        val sb = StringBuilder("\"")
        for ((i, c) in s.withIndex()) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    val lone = if (Character.isHighSurrogate(c)) !(i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
                    else Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s[i - 1]))
                    if (c.code < 0x20 || lone) sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }

    /** Reads back what [stringify] wrote of an object (texts only). */
    fun parseObject(json: String): OutObject = plain(parse(json)) as? OutObject ?: OutObject()

    // ---------------------------------------------------------------- writing

    /** A text as the files write it: minimal escapes (`\"`, `\\`, `\n`, `\t`, `\u00XX` for other control characters). */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun flat(v: Any): String = when (v) {
        is String -> quote(v)
        is Double -> Num.format(v)
        is Int -> v.toString()
        is Boolean -> if (v) "true" else "false"
        is OutObject -> v.entries.let { e -> if (e.isNotEmpty()) "{ " + e.joinToString(", ") { (k, x) -> quote(k) + ": " + flat(x) } + " }" else "{}" }
        is List<*> -> "[" + v.joinToString(", ") { flat(it!!) } + "]"
        else -> throw IllegalArgumentException("Not a JSON value: ${v::class}")
    }

    /**
     * Writes a document, 2-space indented, with a newline at the end. [inline] says whether an object goes on one line
     * (lists are always one item per line, the top-level object one field per line). [onLine] is told the line where
     * each object starts.
     */
    fun write(root: OutObject, inline: (OutObject) -> Boolean, onLine: ((OutObject, Int) -> Unit)? = null): String {
        val lines = ArrayList<String>()
        fun put(v: Any, indent: String, prefix: String, suffix: String, top: Boolean) {
            if (v is OutObject) {
                onLine?.invoke(v, lines.size + 1)
                val e = v.entries
                if (e.isEmpty() || (!top && inline(v))) {
                    lines.add(indent + prefix + flat(v) + suffix)
                    return
                }
                lines.add("$indent$prefix{")
                e.forEachIndexed { i, (k, x) -> put(x, "$indent  ", quote(k) + ": ", if (i < e.size - 1) "," else "", false) }
                lines.add("$indent}$suffix")
                return
            }
            if (v is List<*>) {
                if (v.isEmpty()) {
                    lines.add("$indent$prefix[]$suffix")
                    return
                }
                lines.add("$indent$prefix[")
                v.forEachIndexed { i, x -> put(x!!, "$indent  ", "", if (i < v.size - 1) "," else "", false) }
                lines.add("$indent]$suffix")
                return
            }
            lines.add(indent + prefix + flat(v) + suffix)
        }
        put(root, "", "", "", true)
        return lines.joinToString("\n") + "\n"
    }

    /** Whether a value to write is an object or a list (JavaScript's `typeof x === 'object'`). */
    fun isComposite(v: Any?): Boolean = v is OutObject || v is List<*>
}
