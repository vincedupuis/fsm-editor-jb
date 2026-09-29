package com.vincegosoftware.fsmeditor.core

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor

/**
 * A read-only XML element whose element and attribute names use canonical
 * prefixes for known namespace URIs, so documents using other prefixes read
 * the same (`uml:State` whatever prefix the file binds to the UML namespace).
 */
class XmlElem(
    /** Qualified name using the canonical prefix when the namespace is known, e.g. `uml:Model`. */
    val name: String,
    /** Namespace URI of the element ("" when none). */
    val ns: String,
    /** Local part of the name. */
    val local: String,
    val text: String,
    val line: Int,
) {
    val attrs = LinkedHashMap<String, String>()
    val children = ArrayList<XmlElem>()

    fun attr(name: String): String? = attrs[name]
}

class XmlParseException(message: String, val line: Int) : Exception("Line $line: $message")

/** An element to write: name, attributes in order, and children (elements or text). */
class XNode(var name: String, vararg attrs: Pair<String, Any?>) {
    val attrs: MutableList<Pair<String, Any?>> = attrs.toMutableList()
    /** Child [XNode]s or strings. */
    val children = ArrayList<Any>()

    /** Adds children: nodes, strings, or collections of nodes (null is skipped). */
    fun add(vararg items: Any?): XNode {
        for (c in items) {
            when (c) {
                null -> {}
                is Iterable<*> -> c.forEach { if (it != null) children.add(it) }
                else -> children.add(c)
            }
        }
        return this
    }
}

object XmlTree {
    /**
     * Parses [text] and returns the document element. [canonical] maps namespace
     * URIs to the prefix used in the returned names. Handles elements, attributes,
     * text, CDATA, comments, processing instructions and namespaces, and accepts
     * any name the diagram editors may write.
     */
    fun parse(text: String, canonical: Map<String, String>): XmlElem =
        resolve(Reader(text).document(), emptyMap(), canonical)

    private class Raw(val line: Int) {
        var qname = ""
        val attrs = ArrayList<Pair<String, String>>()
        val children = ArrayList<Raw>()
        val text = StringBuilder()
    }

    private class Reader(private val text: String) {
        private var i = 0
        private var line = 1

        private fun at(s: String) = text.startsWith(s, i)

        private fun advance(to: Int) {
            for (k in i until to) if (text[k] == '\n') line++
            i = to
        }

        private fun expect(s: String) {
            if (!at(s)) throw XmlParseException("Expected '$s'.", line)
            advance(i + s.length)
        }

        private fun skipUntil(end: String, what: String): String {
            val j = text.indexOf(end, i)
            if (j < 0) throw XmlParseException("Unterminated $what.", line)
            val content = text.substring(i, j)
            advance(j + end.length)
            return content
        }

        private fun skipSpace() {
            var j = i
            while (j < text.length && text[j].isWhitespace()) j++
            advance(j)
        }

        private fun name(): String {
            val start = i
            if (i >= text.length || !(text[i].isAsciiLetter() || text[i] == '_')) throw XmlParseException("Expected a name.", line)
            var j = i + 1
            while (j < text.length && (text[j].isAsciiLetter() || text[j] in '0'..'9' || text[j] in "_.-:")) j++
            advance(j)
            return text.substring(start, j)
        }

        private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'

        private fun decode(s: String, line: Int): String =
            ENTITY.replace(s) { m ->
                val e = m.groupValues[1]
                if (e[0] == '#') {
                    val code = try {
                        if (e[1] == 'x') e.substring(2).toInt(16) else e.substring(1).toInt()
                    } catch (_: NumberFormatException) {
                        throw XmlParseException("Unknown entity ${m.value}.", line)
                    }
                    if (!Character.isValidCodePoint(code)) throw XmlParseException("Unknown entity ${m.value}.", line)
                    String(Character.toChars(code))
                } else {
                    when (e) {
                        "lt" -> "<"
                        "gt" -> ">"
                        "amp" -> "&"
                        "quot" -> "\""
                        "apos" -> "'"
                        else -> throw XmlParseException("Unknown entity ${m.value}.", line)
                    }
                }
            }

        private fun element(): Raw {
            val el = Raw(line)
            expect("<")
            el.qname = name()
            while (true) {
                skipSpace()
                if (at("/>")) {
                    advance(i + 2)
                    return el
                }
                if (i < text.length && text[i] == '>') {
                    advance(i + 1)
                    break
                }
                val an = name()
                skipSpace()
                expect("=")
                skipSpace()
                val q = if (i < text.length) text[i] else '\u0000'
                if (q != '"' && q != '\'') throw XmlParseException("Attribute $an must be quoted.", line)
                advance(i + 1)
                val at = line
                val raw = skipUntil(q.toString(), "attribute $an")
                if (raw.indexOf('<') >= 0) throw XmlParseException("'<' is not allowed in attribute $an.", at)
                el.attrs.add(an to decode(raw, at))
            }
            while (true) {
                if (i >= text.length) throw XmlParseException("Element <${el.qname}> is not closed.", el.line)
                if (at("</")) {
                    advance(i + 2)
                    val close = name()
                    if (close != el.qname) throw XmlParseException("Expected </${el.qname}>, found </$close>.", line)
                    skipSpace()
                    expect(">")
                    return el
                }
                if (at("<!--")) {
                    advance(i + 4)
                    skipUntil("-->", "comment")
                } else if (at("<![CDATA[")) {
                    advance(i + 9)
                    el.text.append(skipUntil("]]>", "CDATA section"))
                } else if (at("<?")) {
                    advance(i + 2)
                    skipUntil("?>", "processing instruction")
                } else if (text[i] == '<') {
                    el.children.add(element())
                } else {
                    var j = text.indexOf('<', i)
                    if (j < 0) j = text.length
                    val at = line
                    val chunk = text.substring(i, j)
                    advance(j)
                    el.text.append(decode(chunk, at))
                }
            }
        }

        fun document(): Raw {
            // Prolog
            while (true) {
                skipSpace()
                if (at("<?")) {
                    advance(i + 2)
                    skipUntil("?>", "processing instruction")
                } else if (at("<!--")) {
                    advance(i + 4)
                    skipUntil("-->", "comment")
                } else if (at("<!DOCTYPE")) {
                    skipUntil(">", "DOCTYPE")
                } else {
                    break
                }
            }
            if (i >= text.length || text[i] != '<') throw XmlParseException("The document does not start with an element.", line)
            val root = element()
            skipSpace()
            while (at("<!--")) {
                advance(i + 4)
                skipUntil("-->", "comment")
                skipSpace()
            }
            if (i < text.length) throw XmlParseException("Content after the document element.", line)
            return root
        }

        companion object {
            val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
        }
    }

    // Namespace resolution
    private fun resolve(raw: Raw, scope: Map<String, String>, canonical: Map<String, String>): XmlElem {
        val s = HashMap(scope)
        for ((k, v) in raw.attrs) {
            if (k == "xmlns") s[""] = v
            else if (k.startsWith("xmlns:")) s[k.substring(6)] = v
        }

        fun qualify(qname: String, isAttr: Boolean): Triple<String, String, String> {
            val c = qname.indexOf(':')
            val prefix = if (c < 0) "" else qname.substring(0, c)
            val local = if (c < 0) qname else qname.substring(c + 1)
            if (isAttr && c < 0) return Triple(local, "", local)
            val ns = s[prefix] ?: ""
            val p = canonical[ns] ?: prefix
            return Triple(if (p.isNotEmpty()) "$p:$local" else local, ns, local)
        }

        val q = qualify(raw.qname, false)
        val el = XmlElem(q.first, q.second, q.third, raw.text.toString(), raw.line)
        for ((k, v) in raw.attrs) {
            if (k == "xmlns" || k.startsWith("xmlns:")) continue
            val name = qualify(k, true).first
            // Qualified values (xmi:type="uml:State") follow the same prefix mapping.
            el.attrs[name] = if (name.endsWith(":type")) qualify(v, false).first else v
        }
        for (c in raw.children) el.children.add(resolve(c, s, canonical))
        return el
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\r' -> sb.append("&#13;")
                '\n' -> sb.append("&#10;")
                '\t' -> sb.append("&#9;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Writes the document with two-space indentation and LF line ends. */
    fun write(root: XNode): String {
        val output = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writeNode(root, "", output)
        return output.toString()
    }

    private fun writeNode(node: XNode, indent: String, output: StringBuilder) {
        output.append(indent).append('<').append(node.name)
        for ((key, value) in node.attrs) {
            val s = formatValue(value)
            if (s.isNullOrEmpty()) continue
            output.append(' ').append(key).append("=\"").append(escape(s)).append('"')
        }
        val only = node.children.singleOrNull()
        if (node.children.isEmpty()) {
            output.append("/>\n")
        } else if (only is String) {
            output.append('>').append(escape(only).replace("&#10;", "\n")).append("</").append(node.name).append(">\n")
        } else {
            output.append(">\n")
            for (c in node.children) {
                if (c is XNode) writeNode(c, "$indent  ", output)
                else output.append(indent).append("  ").append(escape(c.toString())).append('\n')
            }
            output.append(indent).append("</").append(node.name).append(">\n")
        }
    }

    private fun formatValue(value: Any?): String? = when (value) {
        null -> null
        is Double -> Num.format(value)
        is String -> value
        else -> value.toString()
    }
}

/** Number helpers matching how the diagram and the files have always printed coordinates. */
object Num {
    /** Rounds half up (towards +∞), like the rounding used for saved coordinates. */
    fun round(n: Double): Double = floor(n + 0.5)

    /** Rounds to 0.1. */
    fun r1(n: Double): Double = floor(n * 10 + 0.5) / 10

    /** Shortest text of a number, like JavaScript prints it: `60`, `12.5`, never `-0` or an exponent. */
    fun format(n: Double): String {
        if (n.isNaN() || n.isInfinite()) return "0"
        if (n == 0.0) return "0"
        if (n == floor(n) && abs(n) < 1e15) return n.toLong().toString()
        // Double.toString gives the shortest digits that read back the same number.
        return BigDecimal(n.toString()).stripTrailingZeros().toPlainString()
    }

    private val NUMBER = Regex("^[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")

    /** Parses a number of an XMI attribute (invalid gives 0). */
    fun parse(s: String?): Double {
        val t = (s ?: "").trim()
        return if (NUMBER.matches(t)) t.toDouble() else 0.0
    }
}
