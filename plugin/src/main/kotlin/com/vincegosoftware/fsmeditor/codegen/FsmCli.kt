package com.vincegosoftware.fsmeditor.codegen

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import com.vincegosoftware.fsmeditor.core.Severity
import java.io.File
import java.nio.charset.StandardCharsets

/** A problem the generator reported (`file:line: severity: message` on stderr). */
class CliIssue(val file: String, /** One-based line, as printed. */ val line: Int, val severity: Severity, val message: String)

class CliResult(
    val exitCode: Int,
    val output: List<String>,
    val errors: List<String>,
    val issues: List<CliIssue>,
    /** Files the generator wrote (full paths). */
    val written: List<String>,
)

/**
 * Runs the `fsm` command-line code generator of FSM Editor (the same program
 * as in VS Code, Visual Studio and CI): `fsm <file> --template <hbs> --out <folder>`,
 * or, for Jinja templates, fsm-generator (the same generator in Rust), which takes the
 * same arguments and prints the same lines. The plugin bundles them in cli/<platform>/
 * and generator/<platform>/, each with its templates in a templates/ folder next to it.
 */
object FsmCli {
    private val ISSUE_LINE = Regex("^(.+?):(\\d+): (error|warning|info): (.*)$")

    private val exe: String get() = if (SystemInfo.isWindows) "fsm.exe" else "fsm"
    private val generatorExe: String get() = if (SystemInfo.isWindows) "fsm-generator.exe" else "fsm-generator"

    /** Whether a template is a Jinja one, run by fsm-generator rather than fsm. */
    fun isJinja(template: String): Boolean = template.endsWith(".jinja", ignoreCase = true)

    /** Platform folder names, as in the generator's release archives (`fsm-<version>-<platform>`). */
    val platform: String
        get() {
            val os = when {
                SystemInfo.isWindows -> "windows"
                SystemInfo.isMac -> "darwin"
                else -> "linux"
            }
            val arch = if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
            return "$os-$arch"
        }

    /** The executable bundled with the plugin in [folder] (`cli` for fsm, `generator` for fsm-generator), or null. */
    private fun bundled(folder: String, exe: String): File? {
        // The plugin's jar is in <plugin>/lib.
        val jar = PathManager.getJarPathForClass(FsmCli::class.java) ?: return null
        val dir = File(jar).parentFile?.parentFile?.resolve(folder) ?: return null
        return listOf(File(dir, "$platform/$exe"), File(dir, exe)).firstOrNull { it.isFile }
    }

    /** The fsm executable to run: the configured one, the bundled one, or one on the PATH. Null when none is found. */
    fun locate(configured: String): File? =
        locate(configured, bundled("cli", exe), if (SystemInfo.isWindows) listOf("fsm.exe", "fsm.cmd") else listOf("fsm"))

    /** The fsm-generator executable to run, found like [locate]. */
    fun locateGenerator(configured: String): File? = locate(configured, bundled("generator", generatorExe), listOf(generatorExe))

    private fun locate(configured: String, bundled: File?, names: List<String>): File? {
        if (configured.isNotBlank()) {
            val f = File(configured.trim().trim('"'))
            return if (f.isFile) f else null
        }
        bundled?.let { return it }
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val candidate = File(dir.trim('"'), name)
                if (candidate.isFile) return candidate
            }
        }
        return null
    }

    /**
     * Templates shipped with an executable (the .hbs files of the templates folder next to fsm, the .jinja
     * files next to fsm-generator), where `-t <name>` finds them. Empty when [cli] is null.
     */
    fun bundledTemplates(cli: File?, extension: String = "hbs"): List<File> =
        cli?.let { File(it.absoluteFile.parentFile, "templates").listFiles { f -> f.name.endsWith(".$extension") } }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun commandLine(cli: File, args: List<String>): String =
        (listOf(cli.path) + args).joinToString(" ") { if (it.isNotEmpty() && it.none { c -> c == ' ' || c == '"' || c == '\t' }) it else "\"" + it.replace("\"", "\\\"") + "\"" }

    fun run(cli: File, args: List<String>, workingDirectory: File): CliResult {
        // Unpacked plugin files may lose their executable bit.
        if (!SystemInfo.isWindows && !cli.canExecute()) cli.setExecutable(true)
        val command = GeneralCommandLine(listOf(cli.path) + args)
            .withWorkDirectory(workingDirectory)
            .withCharset(StandardCharsets.UTF_8)
        val out = CapturingProcessHandler(command).runProcess(5 * 60 * 1000)
        val output = out.stdoutLines.filter { it.isNotEmpty() }
        val errors = out.stderrLines.filter { it.isNotEmpty() }
        val written = output.filter { it.startsWith("wrote ") }.map { resolve(workingDirectory, it.substring(6)) }
        val issues = errors.mapNotNull { line ->
            val m = ISSUE_LINE.find(line) ?: return@mapNotNull null
            val severity = when (m.groupValues[3]) {
                "error" -> Severity.Error
                "warning" -> Severity.Warning
                else -> Severity.Info
            }
            CliIssue(resolve(workingDirectory, m.groupValues[1]), m.groupValues[2].toInt(), severity, m.groupValues[4])
        }
        val exitCode = if (out.isTimeout) -1 else out.exitCode
        return CliResult(exitCode, output, if (out.isTimeout) errors + "${cli.nameWithoutExtension} did not finish within 5 minutes." else errors, issues, written)
    }

    private fun resolve(dir: File, path: String): String = File(path).let { if (it.isAbsolute) it else File(dir, path) }.normalize().path
}
