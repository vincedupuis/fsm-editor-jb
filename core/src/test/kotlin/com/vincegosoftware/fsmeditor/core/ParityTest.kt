package com.vincegosoftware.fsmeditor.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the Kotlin implementation with the reference results in fixtures/,
 * produced by FSM Editor for VS Code (fixtures/README.md), so the editors read,
 * write and validate files the same way.
 */
class ParityTest {
    private fun fixture(name: String) = File(javaClass.getResource("/fixtures/$name")!!.toURI())

    private fun read(file: File) = file.readText().replace("\r\n", "\n")

    @Suppress("UNCHECKED_CAST")
    private val expected = Json.parse(read(fixture("expected.json"))) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(key: String) = (this as Map<String, Any?>)[key]

    private fun format(issues: List<Issue>) = issues.map { "${it.severity.key}|${it.id}|${it.message}" }

    private fun format(issues: Any?) = (issues as List<*>).map { "${it.obj("severity")}|${it.obj("id")}|${it.obj("message")}" }

    @ParameterizedTest
    @ValueSource(strings = ["MediaPlayer.fsm", "Order.fsm", "Payment.fsm"])
    fun examplesRoundTripUnchanged(file: String) {
        val text = read(File(EXAMPLES, file))
        assertEquals(text, Xmi.toXmi(Xmi.fromXmi(text)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["grammar", "structure", "protocol", "cycles", "colors", "loops", "regions", "groups", "edges", "regionids"])
    fun writesTheSameXmi(name: String) {
        val text = read(fixture("$name.fsm"))
        assertEquals(read(fixture("$name.roundtrip.xmi")), Xmi.toXmi(Xmi.fromXmi(text)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MediaPlayer.fsm", "Order.fsm", "Payment.fsm"])
    fun examplesValidateTheSame(file: String) {
        val path = File(EXAMPLES, file).path
        val model = Xmi.fromXmi(read(File(path)))
        val files = File(EXAMPLES).listFiles { f -> f.name.endsWith(".fsm") }!!.map { it.path }
        val summaryOf = { f: String -> if (File(f).exists()) MachineSummary.tryRead(read(File(f))) else null }
        val display = { f: String -> File(f).name }
        val machines = SubmachineResolver.listMachines(path, files, summaryOf, display)
        val submachines = SubmachineResolver.resolve(path, model, machines, summaryOf, display)
        assertEquals(format(expected.obj("examples").obj(file).obj("issues")), format(Validation.validate(model, submachines)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["grammar", "structure", "protocol", "cycles", "colors", "loops", "regions", "groups", "edges", "regionids"])
    fun casesValidateTheSame(name: String) {
        val model = Xmi.fromXmi(read(fixture("$name.fsm")))
        assertEquals(format(expected.obj("cases").obj(name).obj("issues")), format(Validation.validate(model)))
    }

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
    fun keepsATimeTriggerAndAnEventWhoseIdsWouldBeTheSameApart() {
        val model = FsmModel.createDefault("M")
        model.transitions.add(Transition("t1", "v_idle", "v_idle").apply { triggers.add("after(1s)") })
        model.transitions.add(Transition("t2", "v_idle", "v_idle").apply { triggers.add("after_1s_") })
        val xmi = Xmi.toXmi(model)
        assertEquals(listOf("", "after(1s)", "after_1s_"), Xmi.fromXmi(xmi).transitions.map { it.triggers.joinToString(",") })
        val ids = Regex("xmi:id=\"([^\"]*)\"").findAll(xmi).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun labelBoundsUseTheArithmeticOfTheOtherEditions() {
        // Halfway between (170, 130) and (43.3, 70), xmi.ts computes x = 106.64999999999999 (Math.hypot), so the
        // label 123.5 to its right, 100 wide, starts at 180.1, not 180.2.
        val model = FsmModel.createDefault("M")
        model.vertices[0].x = 33.3
        model.vertices[0].y = 60.0
        model.vertices[1].x = 100.0
        model.vertices[1].y = 100.0
        model.transitions[0].source = "v_idle"
        model.transitions[0].target = "v_init"
        model.transitions[0].labelOffset = PointD(123.5, 0.0)
        val xmi = Xmi.toXmi(model)
        assertTrue("<bounds xmi:type=\"dc:Bounds\" x=\"180.1\" y=\"92\" width=\"100\" height=\"16\"/>" in xmi)
        assertEquals(PointD(123.5, 0.0), Xmi.fromXmi(xmi).transitions[0].labelOffset)
        assertEquals(5.0, Num.hypot(3.0, -4.0))
        // Math.hypot(1.5, 2.5) in JavaScript; sqrt(1.5 * 1.5 + 2.5 * 2.5) is 2.9154759474226504.
        assertEquals(2.91547594742265, Num.hypot(1.5, 2.5))

        // Read as JavaScript reads them: a bound that is missing or not a number puts the label on the line along
        // that axis (NaN, which the other editions' JSON turns into 0), and attributes are read like +x || 0.
        val noWidth = xmi.replace("x=\"180.1\" y=\"92\" width=\"100\"", "x=\"180.1\" y=\"92\"")
        assertEquals(PointD(0.0, 0.0), Xmi.fromXmi(noWidth).transitions[0].labelOffset)
        assertEquals(16.0, Num.parse("0x10"))
        assertEquals(5.0, Num.parse(" 5 "))
        assertEquals(0.0, Num.parse("5px"))
        assertEquals(0.0, Num.parse(null))
    }

    @Test
    fun grammarMatches() {
        for (c in expected["grammar"] as List<*>) {
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

    companion object {
        val EXAMPLES: String = System.getProperty("fsm.examples") ?: "../examples"
    }
}
