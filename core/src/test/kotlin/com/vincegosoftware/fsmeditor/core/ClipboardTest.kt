package com.vincegosoftware.fsmeditor.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Clipboard parity with the VS Code edition, the reference: `src/test/clipboard` is a copy of its
 * `test/unit/fixtures/clipboard`, each case a machine of `src/test/geometry`, a selection (every vertex, or one with
 * what it contains) and the JSON that edition copies for it. This edition must copy the same JSON, and read that JSON
 * back without losing anything (a paste from VS Code). After a change there, copy the folder here.
 */
class ClipboardTest {
    private val dir = File(System.getProperty("fsm.clipboard") ?: "src/test/clipboard")
    private val geometry = File(System.getProperty("fsm.geometry") ?: "src/test/geometry")

    private fun cases() = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()

    private fun case(name: String) = PlainJson.parse(File(dir, "$name.json").readText()) as Map<*, *>

    @Test
    fun copiesTheJsonOfTheVsCodeEdition() {
        assertTrue(cases().size >= 20, "${cases().size} cases in $dir")
        val failed = cases().flatMap { name ->
            val c = case(name)
            val machine = File(geometry, c["machine"] as String).path
            val session = DiagramSession(Load.loadModel(File("$machine.fsm").readText(), File("$machine.fsm.layout").readText()))
            val select = c["select"] as String
            session.selection = if (select == "*") session.model.vertices.map { it.id }.toMutableSet() else mutableSetOf(select)
            val copied = PlainJson.parse(ClipboardJson.write(session.collectSelection()!!))
            differences(c["clipboard"], copied).map { "$name: $it" }
        }
        assertEquals(emptyList(), failed, "copied differently from the VS Code edition")
    }

    @Test
    fun readsTheJsonOfTheVsCodeEdition() {
        val failed = cases().flatMap { name ->
            val clipboard = case(name)["clipboard"]
            val read = ClipboardJson.tryRead(write(clipboard))
            assertNotNull(read, name)
            differences(clipboard, PlainJson.parse(ClipboardJson.write(read))).map { "$name: $it" }
        }
        assertEquals(emptyList(), failed, "read differently from what the VS Code edition copied")
    }

    /** Where two JSON values differ (key order aside), at most ten places. */
    private fun differences(a: Any?, b: Any?): List<String> {
        val out = mutableListOf<String>()
        fun walk(p: String, x: Any?, y: Any?) {
            if (out.size >= 10) return
            if (x is Map<*, *> && y is Map<*, *>) {
                for (k in (x.keys + y.keys).map { it as String }.toSortedSet()) {
                    when {
                        k !in x -> out += "$p.$k: only here, ${write(y[k])}"
                        k !in y -> out += "$p.$k: missing, VS Code has ${write(x[k])}"
                        else -> walk("$p.$k", x[k], y[k])
                    }
                }
            } else if (x is List<*> && y is List<*>) {
                if (x.size != y.size) out += "$p: ${y.size} items, VS Code has ${x.size}"
                for (i in 0 until minOf(x.size, y.size)) walk("$p[$i]", x[i], y[i])
            } else if (write(x) != write(y)) out += "$p: ${write(y)}, VS Code has ${write(x)}"
        }
        walk("", a, b)
        return out
    }

    private fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> PlainJson.quote(v)
        is Boolean -> v.toString()
        is Double -> Num.format(v)
        is List<*> -> v.joinToString(",", "[", "]") { write(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> PlainJson.quote(k as String) + ":" + write(x) }
        else -> v.toString()
    }
}
