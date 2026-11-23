package com.vincegosoftware.fsmeditor

import com.intellij.openapi.util.io.FileUtil
import com.vincegosoftware.fsmeditor.codegen.CodeGenerator
import com.vincegosoftware.fsmeditor.core.Common
import com.vincegosoftware.fsmeditor.core.Severity
import junit.framework.TestCase
import org.junit.Assume
import java.io.File

/**
 * Code generation through fsm-editor-common's sidecar (plugin/cli, copied by scripts/fetch-common.sh): skipped when it
 * isn't there. What Generate Code runs once its dialog has the template and the output folder ([CodeGenerator.generate]).
 */
class CodeGeneratorTest : TestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")
    private val cli = File(System.getProperty("fsm.cli") ?: "cli")
    private val template = File(cli, "templates/ts.jinja")

    /** Copies of the examples in a fresh folder. */
    private fun workspace(): File = FileUtil.createTempDirectory("fsm", "work").also { dir ->
        examples.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(dir, it.name)) }
    }

    @Scenario("codegen-bundled")
    fun testGeneratesCodeAndReportsProblems() {
        Assume.assumeTrue("scripts/fetch-common.sh was not run", System.getProperty("fsm.common") != null && template.isFile)
        val dir = workspace()
        val out = File(dir, "generated")
        val ok = CodeGenerator.generate(File(dir, "Order.fsm"), template, out)
        assertEquals(ok.error ?: "", 0, ok.exitCode)
        val names = ok.written.map { File(it).name }
        assertTrue(names.toString(), names.contains("Order.ts") && !names.contains("Payment.ts"))
        assertTrue(ok.written.all { File(it).isFile && it.startsWith(out.path) })
        assertTrue(File(out, "Order.ts").readText().contains("export class Order"))

        // A submachine file that doesn't exist: the machine has errors, reported at their lines.
        val broken = File(dir, "Broken.fsm")
        broken.writeText(File(examples, "Order.fsm").readText().replace("Payment.fsm", "Missing.fsm"))
        val failed = CodeGenerator.generate(broken, template, out)
        assertEquals(1, failed.exitCode)
        assertTrue(failed.issues.joinToString { "${it.file}:${it.line}: ${it.message}" }, failed.issues.any { it.severity == Severity.Error && File(it.file) == broken && it.line > 0 })
        assertTrue(failed.error ?: "", failed.error!!.contains("nothing was generated"))
    }

    /** The sidecar the plugin bundles is found beside its jars, in the IDE's class loaders (no `fsm.common` needed). */
    fun testFindsTheBundledSidecar() {
        Assume.assumeTrue("scripts/fetch-common.sh was not run", System.getProperty("fsm.common") != null)
        val sidecar = Common.bundled()
        assertTrue("$sidecar", sidecar != null && File(sidecar).isFile)
    }

    /** A template of the workspace: it sees the submachine. */
    @Scenario("codegen-workspace-template")
    fun testGeneratesWithATemplateOfTheWorkspace() {
        Assume.assumeTrue("scripts/fetch-common.sh was not run", System.getProperty("fsm.common") != null)
        val dir = workspace()
        val states = File(dir, "states.jinja").apply {
            writeText("{% filter file(machine.name ~ \".txt\") %}{{ machine.name }}: {{ submachines | map(attribute=\"name\") | join(\", \") }}{% endfilter %}")
        }
        val out = File(dir, "generated")
        val r = CodeGenerator.generate(File(dir, "Order.fsm"), states, out)
        assertEquals(r.error ?: "", 0, r.exitCode)
        assertEquals(listOf("Order.txt"), r.written.map { File(it).name })
        assertEquals("Order: Payment", File(out, "Order.txt").readText())
    }

    /** The generator refuses a Handlebars template, and its message is the one shown. */
    @Scenario("codegen-refused-template")
    fun testReportsWhyATemplateIsRefused() {
        Assume.assumeTrue("scripts/fetch-common.sh was not run", System.getProperty("fsm.common") != null)
        val out = FileUtil.createTempDirectory("fsm", "out")
        val old = File(out, "old.hbs").apply { writeText("{{machine.name}}") }
        val refused = CodeGenerator.generate(File(examples, "Order.fsm"), old, out)
        assertEquals(2, refused.exitCode)
        assertTrue(refused.error, refused.error!!.startsWith("old.hbs is a Handlebars template: they are no longer supported."))
        assertTrue(refused.written.isEmpty())
    }
}
