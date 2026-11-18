package com.vincegosoftware.fsmeditor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * fsm-editor-common through this edition's client ([Common], [ModelJson]): its interface's cases (the requests and
 * answers its own tests check) give the same answers here, and the examples are written back as they are, through the
 * model's JSON both ways. The format, validation, the grammar, conversion, comparing and merging are tested in
 * fsm-editor-common itself. A port of the Visual Studio edition's `CommonTests.cs`.
 */
class CommonTest {
    init {
        // Sets the sidecar's path.
        TestFiles.CLI
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

    @Test
    fun `answers its interface cases`() {
        val lines = TestFiles.API_CASES.readLines().filter { it.isNotEmpty() }
        assertTrue(lines.size > 30)
        for (line in lines) {
            val c = PlainJson.parse(line) as Map<*, *>
            val request = c["request"] as Map<*, *>
            val answer = c["answer"] as Map<*, *>
            val fn = request["fn"] as String
            val args = write(request["args"] ?: emptyMap<String, Any?>())
            if (answer.containsKey("error")) {
                val e = assertThrows<CommonException> { Common.call(fn, args) }
                assertEquals(answer["error"], e.message)
            } else {
                assertEquals(answer["result"], Common.call(fn, args), fn)
            }
        }
    }

    @Test
    fun `writes the examples back as they are`() {
        for (name in listOf("MediaPlayer.fsm", "Order.fsm", "Payment.fsm")) {
            val text = TestFiles.example(name)
            val layout = TestFiles.example("$name.layout")
            val read = TestFiles.read(text, layout)
            assertFalse(read.lines.isEmpty())
            val w = Format.writeMachine(read.model!!)
            assertTrue(w.lost.isEmpty())
            assertEquals(text, w.model, name)
            assertEquals(layout, w.layout, name)
        }
    }

    @Test
    fun `checks text through the sidecar`() {
        assertEquals("a() && !b()", Expressions.checkGuard("a()&&!b()", false).value)
        assertFalse(Expressions.checkGuard("else", false).ok)
        assertEquals(listOf("go", "after(2s)"), Expressions.checkList("go, after( 2 s )", Expressions::checkTrigger).value)
    }
}
