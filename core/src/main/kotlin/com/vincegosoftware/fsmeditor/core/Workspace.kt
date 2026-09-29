package com.vincegosoftware.fsmeditor.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.text.Collator

/** Name, id and connection points of the machine in a file. */
class MachineSummary(val id: String, val name: String, val points: List<ConnectionPointInfo>) {
    companion object {
        fun of(m: FsmModel) = MachineSummary(m.id, m.name, m.machineConnectionPoints())

        /** The summary of a file's text, or null when it isn't a readable state machine. */
        fun tryRead(text: String): MachineSummary? =
            try {
                of(Xmi.fromXmi(text))
            } catch (_: XmlParseException) {
                null
            } catch (_: XmiException) {
                null
            }
    }
}

/**
 * How state machine files reference each other: submachine states hold an
 * XMI href such as `sub/Payment.fsm#sm`, relative to the referencing file.
 */
object Hrefs {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;,/?:@&=+$-_.!~*'()#"

    private val windows = System.getProperty("os.name", "").startsWith("Windows")

    /** Percent-encodes a relative path the way URI references are written (like JavaScript's encodeURI). */
    fun encodeUri(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val u = b.toInt() and 0xff
            if (u < 0x80 && UNRESERVED.indexOf(u.toChar()) >= 0) sb.append(u.toChar())
            else sb.append('%').append(String.format("%02X", u))
        }
        return sb.toString()
    }

    /** Decodes %XX escapes as UTF-8; text that isn't a valid escape is kept as is. */
    fun decodeUri(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] != '%') {
                out.append(s[i++])
                continue
            }
            val bytes = ByteArrayOutputStream()
            var j = i
            while (j + 2 < s.length && s[j] == '%' && s.substring(j + 1, j + 3).toIntOrNull(16) != null) {
                bytes.write(s.substring(j + 1, j + 3).toInt(16))
                j += 3
            }
            if (j == i) {
                out.append(s[i++])
                continue
            }
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            i = j
        }
        return out.toString()
    }

    private fun full(p: String): Path = Paths.get(p).toAbsolutePath().normalize()

    /** Relative path with `/` separators from directory [fromDir] to [to]. */
    fun relative(fromDir: String, to: String): String {
        val a = full(fromDir).map { it.toString() }
        val b = full(to).map { it.toString() }
        var i = 0
        while (i < a.size && i < b.size && a[i].equals(b[i], ignoreCase = windows)) i++
        return (List(a.size - i) { ".." } + b.drop(i)).joinToString("/")
    }

    /** href of machine [machineId] in [targetFile], as seen from [documentFile]. */
    fun forFile(documentFile: String, targetFile: String, machineId: String): String =
        "${encodeUri(relative(full(documentFile).parent.toString(), targetFile))}#$machineId"

    /** The file (full path) and element id an href points to, relative to [documentFile]. */
    fun resolve(documentFile: String, href: String): Pair<String, String> {
        val hash = href.indexOf('#')
        val file = if (hash >= 0) href.substring(0, hash) else href
        val id = if (hash >= 0) href.substring(hash + 1) else ""
        val path = full(documentFile).parent.resolve(decodeUri(file)).normalize()
        return path.toString() to id
    }

    fun samePath(a: String, b: String): Boolean = full(a).toString().equals(full(b).toString(), ignoreCase = windows)
}

/** Works out which machines a document can use as submachines, and what its submachine states refer to. */
object SubmachineResolver {
    /**
     * [documentFile]: the document being edited; [candidates]: other *.fsm files of the project;
     * [summaryOf] reads a file's summary (null when missing or unreadable); [display] gives the path shown to the user.
     */
    fun listMachines(
        documentFile: String,
        candidates: Iterable<String>,
        summaryOf: (String) -> MachineSummary?,
        display: (String) -> String,
    ): List<MachineInfo> {
        val output = ArrayList<MachineInfo>()
        for (file in candidates) {
            if (Hrefs.samePath(file, documentFile)) continue
            val s = summaryOf(file) ?: continue
            output.add(MachineInfo(Hrefs.forFile(documentFile, file, s.id), s.name, display(file), s.points))
        }
        val collator = Collator.getInstance()
        return output.sortedWith { x, y -> collator.compare(x.name, y.name) }
    }

    /** Reads the machines referenced by submachine states, to learn their names and connection points. */
    fun resolve(
        documentFile: String,
        model: FsmModel,
        machines: List<MachineInfo>,
        summaryOf: (String) -> MachineSummary?,
        display: (String) -> String,
    ): Map<String, SubmachineInfo> {
        val info = LinkedHashMap<String, SubmachineInfo>()
        for (href in model.vertices.filter { it.type == VertexType.State && it.submachine.isNotEmpty() }.map { it.submachine }.distinct()) {
            val known = machines.firstOrNull { it.href == href }
            if (known != null) {
                info[href] = SubmachineInfo(true, known.name, known.file, known.points)
                continue
            }
            // Outside the project, or written with a different relative path.
            val (file, id) = Hrefs.resolve(documentFile, href)
            val s = summaryOf(file)
            info[href] = if (s != null && s.id == id) SubmachineInfo(true, s.name, display(file), s.points) else SubmachineInfo(false)
        }
        return info
    }
}
