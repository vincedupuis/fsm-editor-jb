package com.vincegosoftware.fsmeditor.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
    @ValueSource(strings = ["grammar", "structure", "protocol"])
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
    @ValueSource(strings = ["grammar", "structure", "protocol"])
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
