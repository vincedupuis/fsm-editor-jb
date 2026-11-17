package com.vincegosoftware.fsmeditor.core

import com.vincegosoftware.fsmeditor.core.TestFiles.EXAMPLES
import com.vincegosoftware.fsmeditor.core.TestFiles.fixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parity with FSM Editor for VS Code beyond the file format (whose reader and validator are checked against fsm-cli's
 * test cases, [CasesTest]): the grammar checks of fixtures/grammar.json, made from the reference by the umbrella
 * repo's parity script, and the reading of old files as JavaScript reads them. ParityTests.cs of the Visual Studio edition.
 */
class ParityTest {
    private fun read(file: File) = file.readText().replace("\r\n", "\n")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(key: String) = (this as Map<String, Any?>)[key]

    private fun <T> assertCheck(expected: Any?, actual: Check<T>, format: (T) -> String, what: String) {
        assertEquals(expected.obj("ok"), actual.ok, "$what: expected ok=${expected.obj("ok")}, got ${actual.error}")
        if (actual.ok) {
            val value = expected.obj("value")
            val text = if (value is List<*>) value.joinToString("|") else value as String
            assertEquals(text, format(actual.value), what)
        } else {
            assertEquals(expected.obj("error"), actual.error, what)
        }
    }

    @Test
    fun grammarMatches() {
        val expected = PlainJson.parse(read(fixture("grammar.json")))
        for (c in expected.obj("grammar") as List<*>) {
            val t = c.obj("text") as String
            assertCheck(c.obj("condition"), Expressions.checkCondition(t), { it }, "condition '$t'")
            assertCheck(c.obj("guard"), Expressions.checkGuard(t, true), { it }, "guard '$t'")
            assertCheck(c.obj("guardNoElse"), Expressions.checkGuard(t, false), { it }, "guard without else '$t'")
            assertCheck(c.obj("actions"), Expressions.checkActions(t), { it }, "actions '$t'")
            assertCheck(c.obj("event"), Expressions.checkEvent(t), { it }, "event '$t'")
            assertCheck(c.obj("name"), Expressions.checkName(t), { it }, "name '$t'")
            assertCheck(c.obj("trigger"), Expressions.checkTrigger(t), { it }, "trigger '$t'")
            assertCheck(c.obj("triggers"), Expressions.checkList("$t, after(1h)", Expressions::checkTrigger), { it.joinToString("|") }, "triggers '$t'")
            assertEquals(c.obj("ms") as Double?, Expressions.timeTriggerMs(t), "ms '$t'")
        }
    }

    @Test
    fun examplesValidateWithTheirSubmachines() {
        val files = File(EXAMPLES).listFiles { f -> f.name.endsWith(".fsm") }!!.map { it.path }
        val summaryOf = { f: String -> if (File(f).exists()) MachineSummary.tryRead(read(File(f))) else null }
        val display = { f: String -> File(f).name }
        for (path in files) {
            val model = Format.readModel(read(File(path))).model!!
            val machines = SubmachineResolver.listMachines(path, files, summaryOf, display)
            assertEquals(emptyList(), Validation.validate(model, SubmachineResolver.resolve(path, model, machines, summaryOf, display)), path)
        }
        val order = File(EXAMPLES, "Order.fsm").path
        val list = SubmachineResolver.listMachines(order, files, summaryOf, display)
        assertEquals(listOf("MediaPlayer.fsm", "Payment.fsm"), list.map { it.href })
        assertEquals(listOf("retry", "failed", "cancelled"), list.single { it.name == "Payment" }.points.map { it.id })
    }

    @Test
    fun subMachinePathsThatAreNotPathsAreNotFound() {
        val model = FsmModel.createDefault("M")
        model.vertices[1].submachine = "a\u0000b.fsm"
        val info = SubmachineResolver.resolve(File(EXAMPLES, "M.fsm").path, model, emptyList(), { null }, { it })
        assertFalse(info.getValue("a\u0000b.fsm").found)
        assertEquals("sm", Hrefs.resolve("/tmp/M.fsm", "a.fsm#sm#x").second)
    }

    @Test
    fun oldFilesReadNumbersAsJavaScriptDoes() {
        assertEquals(5.0, Num.hypot(3.0, -4.0))
        // Math.hypot(1.5, 2.5) in JavaScript; sqrt(1.5 * 1.5 + 2.5 * 2.5) is 2.9154759474226504.
        assertEquals(2.91547594742265, Num.hypot(1.5, 2.5))
        assertEquals(16.0, Num.parse("0x10"))
        assertEquals(5.0, Num.parse(" 5 "))
        assertEquals(0.0, Num.parse("5px"))
        assertEquals(0.0, Num.parse(null))
        assertEquals("Infinity", Num.js(Double.POSITIVE_INFINITY))
        assertEquals("1e+21", Num.format(1e21))
        // +"1e400" || 0 is Infinity in xmi.ts: the state keeps it, and converting reports it.
        val old = read(fixture("old/Payment.fsm"))
        val i = old.indexOf("width=\"")
        val infinite = old.substring(0, i) + "width=\"1e400\" data-width=" + old.substring(i + 6)
        assertTrue(Conversion.convertGroup(listOf(ConvertInput("/x/Payment.fsm", infinite)))[0].losses.any { "not a number" in it })
    }
}
