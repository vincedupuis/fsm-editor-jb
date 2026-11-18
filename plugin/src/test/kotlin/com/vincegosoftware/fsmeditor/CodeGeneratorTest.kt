package com.vincegosoftware.fsmeditor

import com.intellij.openapi.util.io.FileUtil
import com.vincegosoftware.fsmeditor.codegen.CodeGenerator
import com.vincegosoftware.fsmeditor.core.Severity
import junit.framework.TestCase
import org.junit.Assume
import java.io.File

/**
 * Code generation through fsm-editor-common's sidecar (plugin/cli, copied by scripts/fetch-common.sh): skipped when it
 * isn't there.
 */
class CodeGeneratorTest : TestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")
    private val cli = File(System.getProperty("fsm.cli") ?: "cli")
    private val template = File(cli, "templates/ts.jinja")

    fun testGeneratesCodeAndReportsProblems() {
        Assume.assumeTrue("scripts/fetch-common.sh was not run", System.getProperty("fsm.common") != null && template.isFile)
        val out = FileUtil.createTempDirectory("fsm", "out")
        val ok = CodeGenerator.generate(File(examples, "Order.fsm"), template, out)
        assertEquals(ok.error ?: "", 0, ok.exitCode)
        assertTrue(ok.written.any { it.endsWith("Order.ts") })
        assertTrue(ok.written.all { File(it).isFile && it.startsWith(out.path) })

        // A submachine file that doesn't exist: the machine has errors, reported at their lines.
        val broken = File(out, "Broken.fsm")
        broken.writeText(File(examples, "Order.fsm").readText().replace("Payment.fsm", "Missing.fsm"))
        val failed = CodeGenerator.generate(broken, template, out)
        assertEquals(1, failed.exitCode)
        assertTrue(failed.issues.joinToString { it.message }, failed.issues.any { it.severity == Severity.Error && File(it.file) == broken && it.line > 0 })
        assertTrue(failed.error ?: "", failed.error!!.contains("nothing was generated"))
    }

    /** The generator refuses a Handlebars template, and its message is the one shown. */
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
