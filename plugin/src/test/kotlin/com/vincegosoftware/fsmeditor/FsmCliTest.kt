package com.vincegosoftware.fsmeditor

import com.intellij.openapi.util.io.FileUtil
import com.vincegosoftware.fsmeditor.codegen.FsmCli
import com.vincegosoftware.fsmeditor.core.Severity
import junit.framework.TestCase
import org.junit.Assume
import java.io.File

/** Runs the fsm generator copied into plugin/cli/<platform> by scripts/fetch-cli.sh; its tests are skipped when it isn't there. */
class FsmCliTest : TestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")
    private val cli = File(System.getProperty("fsm.cli") ?: "cli", "${FsmCli.platform}/${if (File.separatorChar == '\\') "fsm.exe" else "fsm"}")

    fun testGeneratesCodeAndReportsProblems() {
        Assume.assumeTrue("scripts/fetch-cli.sh was not run", cli.isFile)
        val out = FileUtil.createTempDirectory("fsm", "out")
        assertEquals(listOf("sml.jinja", "ts.jinja"), FsmCli.bundledTemplates(cli).map { it.name })
        val template = FsmCli.bundledTemplates(cli).single { it.name == "ts.jinja" }

        val ok = FsmCli.run(cli, FsmCli.writeArgs(File(examples, "Order.fsm").path, template.path, out.path), examples)
        assertEquals(ok.errors.joinToString("\n"), 0, ok.exitCode)
        assertTrue(ok.written.any { it.endsWith("Order.ts") })
        assertTrue(ok.written.all { File(it).isFile && it.startsWith(out.path) })

        val broken = File(out, "Broken.fsm")
        broken.writeText(File(examples, "Order.fsm").readText().replace("Payment.fsm#", "Missing.fsm#"))
        val failed = FsmCli.run(cli, FsmCli.writeArgs(broken.path, template.path, out.path), out)
        assertEquals(1, failed.exitCode)
        assertTrue(failed.errors.joinToString("\n"), failed.issues.any { it.severity == Severity.Error && File(it.file) == broken && it.line > 0 })
        assertEquals("2 errors in the state machine; nothing was generated.", failed.message)
    }

    /** fsm refuses a Handlebars template, and its message is the one shown. */
    fun testReportsWhyATemplateIsRefused() {
        Assume.assumeTrue("scripts/fetch-cli.sh was not run", cli.isFile)
        val out = FileUtil.createTempDirectory("fsm", "out")
        val old = File(out, "old.hbs").apply { writeText("{{machine.name}}") }
        val refused = FsmCli.run(cli, FsmCli.writeArgs(File(examples, "Order.fsm").path, old.path, out.path), examples)
        assertEquals(2, refused.exitCode)
        assertTrue(refused.message, refused.message!!.startsWith("${old.path} is a Handlebars template: they are no longer supported."))
        assertTrue(refused.written.isEmpty())
    }

    /** Cancelling the progress indicator destroys the process and reports a cancelled run. */
    fun testCancelDestroysTheProcess() {
        val sleep = File("/bin/sleep")
        Assume.assumeTrue("no /bin/sleep", sleep.canExecute())
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator(com.intellij.openapi.application.ModalityState.nonModal())
        java.util.Timer(true).schedule(object : java.util.TimerTask() {
            override fun run() = indicator.cancel()
        }, 300)
        val started = System.currentTimeMillis()
        val r = FsmCli.run(sleep, listOf("30"), File("."), indicator)
        assertTrue(r.cancelled)
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }
}
