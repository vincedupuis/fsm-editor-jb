package com.vincegosoftware.fsmeditor

import com.intellij.openapi.util.io.FileUtil
import com.vincegosoftware.fsmeditor.codegen.FsmCli
import com.vincegosoftware.fsmeditor.core.Severity
import junit.framework.TestCase
import org.junit.Assume
import java.io.File

/**
 * Runs the fsm generator copied by scripts/fetch-cli.sh, and fsm-generator unpacked into plugin/generator/<platform>;
 * each test is skipped when its program isn't there.
 */
class FsmCliTest : TestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")
    private val cli = File(System.getProperty("fsm.cli") ?: "cli", "${FsmCli.platform}/${if (File.separatorChar == '\\') "fsm.exe" else "fsm"}")
    private val generator =
        File(System.getProperty("fsm.generator") ?: "generator", "${FsmCli.platform}/${if (File.separatorChar == '\\') "fsm-generator.exe" else "fsm-generator"}")

    fun testGeneratesCodeAndReportsProblems() {
        Assume.assumeTrue("scripts/fetch-cli.sh was not run", cli.isFile)
        val out = FileUtil.createTempDirectory("fsm", "out")
        val template = FsmCli.bundledTemplates(cli).single { it.name == "ts.hbs" }

        val ok = FsmCli.run(cli, listOf(File(examples, "Order.fsm").path, "--template", template.path, "--out", out.path), examples)
        assertEquals(ok.errors.joinToString("\n"), 0, ok.exitCode)
        assertTrue(ok.written.isNotEmpty())
        assertTrue(ok.written.all { File(it).isFile && it.startsWith(out.path) })

        val broken = File(out, "Broken.fsm")
        broken.writeText(File(examples, "Order.fsm").readText().replace("Payment.fsm#", "Missing.fsm#"))
        val failed = FsmCli.run(cli, listOf(broken.path, "--template", template.path, "--out", out.path), out)
        assertEquals(1, failed.exitCode)
        assertTrue(failed.errors.joinToString("\n"), failed.issues.any { it.severity == Severity.Error && File(it.file) == broken && it.line > 0 })
    }

    fun testGeneratesCodeWithJinjaTemplates() {
        Assume.assumeTrue("fsm-generator was not unpacked into plugin/generator/${FsmCli.platform}", generator.isFile)
        val out = FileUtil.createTempDirectory("fsm-generator", "out")
        assertEquals(listOf("sml.jinja", "ts.jinja"), FsmCli.bundledTemplates(generator, "jinja").map { it.name })
        val template = FsmCli.bundledTemplates(generator, "jinja").single { it.name == "ts.jinja" }
        assertTrue(FsmCli.isJinja(template.path))

        val ok = FsmCli.run(generator, listOf(File(examples, "Order.fsm").path, "--template", template.path, "--out", out.path), examples)
        assertEquals(ok.errors.joinToString("\n"), 0, ok.exitCode)
        assertTrue(ok.written.any { it.endsWith("Order.ts") })
        assertTrue(ok.written.all { File(it).isFile && it.startsWith(out.path) })

        val broken = File(out, "Broken.fsm")
        broken.writeText(File(examples, "Order.fsm").readText().replace("Payment.fsm#", "Missing.fsm#"))
        val failed = FsmCli.run(generator, listOf(broken.path, "--template", template.path, "--out", out.path), out)
        assertEquals(1, failed.exitCode)
        assertTrue(failed.errors.joinToString("\n"), failed.issues.any { it.severity == Severity.Error && File(it.file) == broken && it.line > 0 })
    }
}
