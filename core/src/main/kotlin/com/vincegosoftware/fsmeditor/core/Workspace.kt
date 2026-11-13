package com.vincegosoftware.fsmeditor.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.InvalidPathException
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
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;,/:@&=+$-_.!~*'()"

    private val windows = System.getProperty("os.name", "").startsWith("Windows")

    /**
     * Percent-encodes the file part of an href (encodeHref of the VS Code edition): like JavaScript's encodeURI,
     * plus `#` and `?`, which would otherwise end the path (a file named `a#b.fsm` is `a%23b.fsm`).
     */
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
    private val reserved = ";/:@&=+\$,"

    /**
     * Like JavaScript's decodeURI: decodes `%XX` escapes (UTF-8), except those of reserved characters
     * (`;/:@&=+$,`; unlike decodeURI, `%23` and `%3F` give `#` and `?`, as [encodeUri] writes them), and keeps
     * malformed ones as they are.
     */
    fun decodeUri(s: String): String {
        if (s.indexOf('%') < 0) return s
        fun at(i: Int): Int =
            if (i + 2 < s.length && s[i] == '%' && s[i + 1].digitToIntOrNull(16) != null && s[i + 2].digitToIntOrNull(16) != null) {
                s.substring(i + 1, i + 3).toInt(16)
            } else {
                -1
            }
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val b = at(i)
            if (b >= 0) {
                val len = when (b) {
                    in 0x00..0x7F -> 1
                    in 0xC0..0xDF -> 2
                    in 0xE0..0xEF -> 3
                    in 0xF0..0xF7 -> 4
                    else -> 0
                }
                val run = ByteArrayOutputStream()
                run.write(b)
                var j = i + 3
                while (run.size() < len && at(j) >= 0) {
                    run.write(at(j))
                    j += 3
                }
                val text = if (run.size() == len) {
                    try {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(run.toByteArray()))
                            .toString()
                    } catch (_: CharacterCodingException) {
                        null
                    }
                } else {
                    null
                }
                if (text != null) {
                    out.append(if (len == 1 && text[0] in reserved) s.substring(i, j) else text)
                    i = j
                    continue
                }
            }
            out.append(s[i++])
        }
        return out.toString()
    }

    private fun full(p: String): Path = Paths.get(p).toAbsolutePath().normalize()

    /** Relative path with `/` separators from directory [fromDir] to [to]. */
    fun relative(fromDir: String, to: String): String = relativeOfFull(full(fromDir).toString(), full(to).toString())

    /**
     * [relative] of two full paths, computed as the VS Code edition does on the paths of their file URIs: a drive
     * is the first segment (`d:`, lower case) and a UNC server is not a segment. So a file on another drive is
     * reached by climbing to the root, `../../e:/models/M.fsm`, which [joinFull] resolves.
     */
    fun relativeOfFull(fromDir: String, to: String): String {
        val from = uriPath(fromDir)
        val a = from.segments
        val b = uriPath(to).segments
        var i = 0
        // Windows paths compare without case, as the Visual Studio edition does; others with it.
        while (i < a.size && i < b.size && a[i].equals(b[i], ignoreCase = from.windows)) i++
        return (List(a.size - i) { ".." } + b.drop(i)).joinToString("/")
    }

    /**
     * Full directory [dir] joined with the relative path [relative], as VS Code joins file URI paths: `..` stops
     * at the root, and a drive segment met there (`../../e:/M.fsm`) starts that drive.
     */
    fun joinFull(dir: String, relative: String): String {
        val (server, segments, windows) = uriPath(dir)
        for (s in relative.split(*(if (windows) charArrayOf('/', '\\') else charArrayOf('/')))) {
            if (s.isEmpty() || s == ".") continue
            if (s == "..") {
                if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            } else {
                segments.add(s)
            }
        }
        if (server != null) return "\\\\" + server + "\\" + segments.joinToString("\\")
        if (segments.isNotEmpty() && isDrive(segments[0])) return segments[0].uppercase() + "\\" + segments.drop(1).joinToString("\\")
        return if (windows) "\\" + segments.joinToString("\\") else "/" + segments.joinToString("/")
    }

    private fun isDrive(s: String): Boolean = s.length == 2 && s[1] == ':' && (s[0] in 'A'..'Z' || s[0] in 'a'..'z')

    /** A full path as a file URI path: the UNC server (null for none), and the segments, the first one a lower-case drive. */
    private data class UriPath(val server: String?, val segments: MutableList<String>, val windows: Boolean)

    private fun uriPath(full: String): UriPath {
        val unc = full.startsWith("\\\\")
        val windows = unc || full.length >= 2 && isDrive(full.substring(0, 2))
        val separators = if (windows) charArrayOf('/', '\\') else charArrayOf('/')
        val segments = (if (unc) full.substring(2) else full).split(*separators).filter { it.isNotEmpty() }.toMutableList()
        var server: String? = null
        if (unc && segments.isNotEmpty()) {
            server = segments.removeAt(0)
        } else if (windows && segments.isNotEmpty()) {
            segments[0] = segments[0].lowercase()
        }
        return UriPath(server, segments, windows)
    }

    /** href of machine [machineId] in [targetFile], as seen from [documentFile]. */
    fun forFile(documentFile: String, targetFile: String, machineId: String): String =
        "${encodeUri(relative(full(documentFile).parent.toString(), targetFile))}#$machineId"

    /** The file (full path) and element id an href points to, relative to [documentFile]. */
    fun resolve(documentFile: String, href: String): Pair<String, String> {
        val hash = href.indexOf('#')
        val file = if (hash >= 0) href.substring(0, hash) else href
        // As href.split('#') in the reference: the id ends at a second #.
        val id = if (hash >= 0) href.substring(hash + 1).substringBefore('#') else ""
        val path = full(joinFull(full(documentFile).parent.toString(), decodeUri(file)))
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
            val (file, id) = try {
                Hrefs.resolve(documentFile, href)
            } catch (_: InvalidPathException) {
                // Not a file path (characters a path can't hold): a machine that can't be found.
                info[href] = SubmachineInfo(false)
                continue
            }
            val s = summaryOf(file)
            info[href] = if (s != null && s.id == id) SubmachineInfo(true, s.name, display(file), s.points) else SubmachineInfo(false)
        }
        return info
    }
}
