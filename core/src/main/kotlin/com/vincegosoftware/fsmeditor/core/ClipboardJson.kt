package com.vincegosoftware.fsmeditor.core

/**
 * Clipboard text of copied diagram elements: `{"fsmClipboard":1,"vertices":[…],"transitions":[…]}`,
 * the same JSON as FSM Editor for VS Code and for Visual Studio, so elements can be pasted between the editors.
 */
object ClipboardJson {
    fun write(data: ClipboardFragment): String =
        "{\"fsmClipboard\":1,\"vertices\":[" + data.vertices.joinToString(",", transform = ::writeVertex) +
            "],\"transitions\":[" + data.transitions.joinToString(",", transform = ::writeTransition) + "]}"

    private fun writeVertex(v: Vertex): String {
        val o = JsonObjectWriter()
        o.str("id", v.id).str("type", v.type.key).str("name", v.name).str("parent", v.parent)
            .num("x", v.x).num("y", v.y).num("w", v.w).num("h", v.h)
        o.raw("regions", "[" + v.regions.joinToString(",") { JsonObjectWriter().str("id", it.id).str("name", it.name).toString() } + "]")
        if (v.regions.size > 1) o.str("regionLayout", if (v.regionLayout == RegionLayout.Horizontal) "horizontal" else "vertical")
        o.opt("entry", v.entry).opt("exit", v.exit).opt("doActivity", v.doActivity).opt("invariant", v.invariant)
            .opt("submachine", v.submachine).opt("stereotype", v.stereotype)
        if (v.deferrable.isNotEmpty()) o.raw("deferrable", stringArray(v.deferrable))
        if (v.type == VertexType.ConnectionPointRef) o.str("ref", v.ref).str("pointKind", if (v.pointKind == PointKind.Exit) "exit" else "entry")
        if (v.type == VertexType.Comment) o.str("text", v.text).raw("anchors", stringArray(v.anchors))
        return o.toString()
    }

    private fun writeTransition(t: Transition): String {
        val o = JsonObjectWriter()
        o.str("id", t.id).str("source", t.source).str("target", t.target).str("kind", t.kind.key)
            .raw("triggers", stringArray(t.triggers)).str("guard", t.guard).str("effect", t.effect)
            .opt("precondition", t.precondition).opt("postcondition", t.postcondition)
        if (t.points.isNotEmpty()) {
            o.raw("points", "[" + t.points.joinToString(",") { JsonObjectWriter().num("x", it.x).num("y", it.y).toString() } + "]")
        }
        t.labelOffset?.let { o.raw("labelOffset", JsonObjectWriter().num("x", it.x).num("y", it.y).toString()) }
        return o.toString()
    }

    private fun stringArray(items: List<String>) = "[" + items.joinToString(",", transform = Json::quote) + "]"

    private class JsonObjectWriter {
        private val parts = ArrayList<String>()

        fun raw(key: String, json: String): JsonObjectWriter {
            parts.add(Json.quote(key) + ":" + json)
            return this
        }

        fun str(key: String, value: String?) = raw(key, Json.quote(value ?: ""))

        fun opt(key: String, value: String?) = if (value.isNullOrEmpty()) this else str(key, value)

        fun num(key: String, value: Double) = raw(key, Num.format(value))

        override fun toString() = "{" + parts.joinToString(",") + "}"
    }

    /** Reads clipboard text; null when it isn't copied diagram elements. */
    fun tryRead(text: String?): ClipboardFragment? {
        val root = try {
            Json.parse(text ?: "")
        } catch (_: Json.FormatException) {
            return null
        }
        if (root !is Map<*, *> || !root.containsKey("fsmClipboard")) return null
        val data = ClipboardFragment()
        for (item in list(root, "vertices").filterIsInstance<Map<*, *>>()) {
            val type = VertexType.parseKey(str(item, "type")) ?: continue
            val v = Vertex(str(item, "id"), type, str(item, "name"), str(item, "parent"), dbl(item, "x"), dbl(item, "y"), dbl(item, "w", 40.0), dbl(item, "h", 40.0))
            v.regions = list(item, "regions").filterIsInstance<Map<*, *>>().mapTo(mutableListOf()) { Region(str(it, "id"), str(it, "name")) }
            v.regionLayout = if (str(item, "regionLayout") == "horizontal") RegionLayout.Horizontal else RegionLayout.Vertical
            v.entry = str(item, "entry")
            v.exit = str(item, "exit")
            v.doActivity = str(item, "doActivity")
            v.deferrable = list(item, "deferrable").filterIsInstance<String>().toMutableList()
            v.invariant = str(item, "invariant")
            v.submachine = str(item, "submachine")
            v.stereotype = str(item, "stereotype")
            v.ref = str(item, "ref")
            v.pointKind = if (str(item, "pointKind") == "exit") PointKind.Exit else PointKind.Entry
            v.text = str(item, "text")
            v.anchors = list(item, "anchors").filterIsInstance<String>().toMutableList()
            data.vertices.add(v)
        }
        for (item in list(root, "transitions").filterIsInstance<Map<*, *>>()) {
            val kind = when (str(item, "kind")) {
                "internal" -> TransitionKind.Internal
                "local" -> TransitionKind.Local
                else -> TransitionKind.External
            }
            val t = Transition(str(item, "id"), str(item, "source"), str(item, "target"), kind)
            t.triggers = list(item, "triggers").filterIsInstance<String>().toMutableList()
            t.guard = str(item, "guard")
            t.effect = str(item, "effect")
            t.precondition = str(item, "precondition")
            t.postcondition = str(item, "postcondition")
            t.points = list(item, "points").filterIsInstance<Map<*, *>>().mapTo(mutableListOf()) { PointD(dbl(it, "x"), dbl(it, "y")) }
            val off = item["labelOffset"]
            if (off is Map<*, *>) t.labelOffset = PointD(dbl(off, "x"), dbl(off, "y"))
            data.transitions.add(t)
        }
        return data
    }

    private fun str(o: Map<*, *>, key: String): String = o[key] as? String ?: ""

    /** A number, or [fallback] when missing or zero (like `Number(x) || fallback`). */
    private fun dbl(o: Map<*, *>, key: String, fallback: Double = 0.0): Double {
        val d = o[key] as? Double
        return if (d != null && d != 0.0) d else fallback
    }

    private fun list(o: Map<*, *>, key: String): List<*> = o[key] as? List<*> ?: emptyList<Any>()
}

/** Minimal JSON: objects become maps, arrays lists, numbers doubles. */
object Json {
    class FormatException(message: String) : Exception(message)

    fun parse(text: String): Any? = Reader(text).readDocument()

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun readDocument(): Any? {
            val v = value()
            space()
            if (i != s.length) throw FormatException("Trailing content.")
            return v
        }

        private fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char {
            space()
            if (i >= s.length) throw FormatException("Unexpected end.")
            return s[i]
        }

        private fun expect(c: Char) {
            if (peek() != c) throw FormatException("Expected '$c'.")
            i++
        }

        private fun value(): Any? {
            val c = peek()
            if (c == '{') return obj()
            if (c == '[') return array()
            if (c == '"') return string()
            if (word("true")) return true
            if (word("false")) return false
            if (word("null")) return null
            return number()
        }

        private fun word(w: String): Boolean {
            if (!s.startsWith(w, i)) return false
            i += w.length
            return true
        }

        private fun obj(): Map<String, Any?> {
            val o = LinkedHashMap<String, Any?>()
            expect('{')
            if (peek() == '}') {
                i++
                return o
            }
            while (true) {
                peek()
                val key = string()
                expect(':')
                o[key] = value()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect('}')
                return o
            }
        }

        private fun array(): List<Any?> {
            val a = ArrayList<Any?>()
            expect('[')
            if (peek() == ']') {
                i++
                return a
            }
            while (true) {
                a.add(value())
                if (peek() == ',') {
                    i++
                    continue
                }
                expect(']')
                return a
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) break
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 4 > s.length) throw FormatException("Bad escape.")
                        val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw FormatException("Bad escape.")
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
            throw FormatException("Unterminated string.")
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            return s.substring(start, i).takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: throw FormatException("Bad number.")
        }
    }
}
