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
 * as in VS Code, Visual Studio and CI): `fsm <file> --template <hbs> --out <folder>`.
 * The plugin bundles it in cli/<platform>/, with its templates in cli/<platform>/templates.
 */
object FsmCli {
    private val ISSUE_LINE = Regex("^(.+?):(\\d+): (error|warning|info): (.*)$")

    private val exe: String get() = if (SystemInfo.isWindows) "fsm.exe" else "fsm"

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

    /** The executable bundled with the plugin, or null. */
    fun bundled(): File? {
        // The plugin's jar is in <plugin>/lib.
        val jar = PathManager.getJarPathForClass(FsmCli::class.java) ?: return null
        val dir = File(jar).parentFile?.parentFile?.resolve("cli") ?: return null
        return listOf(File(dir, "$platform/$exe"), File(dir, exe)).firstOrNull { it.isFile }
    }

    /** The executable to run: the configured one, the bundled one, or one on the PATH. Null when none is found. */
    fun locate(configured: String): File? {
        if (configured.isNotBlank()) {
            val f = File(configured.trim().trim('"'))
            return if (f.isFile) f else null
        }
        bundled()?.let { return it }
        val names = if (SystemInfo.isWindows) listOf("fsm.exe", "fsm.cmd") else listOf("fsm")
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val candidate = File(dir.trim('"'), name)
                if (candidate.isFile) return candidate
            }
        }
        return null
    }

    /** Templates shipped with the executable (the .hbs files of the templates folder next to it), where `-t <name>` finds them. */
    fun bundledTemplates(cli: File): List<File> =
        File(cli.absoluteFile.parentFile, "templates").listFiles { f -> f.name.endsWith(".hbs") }?.sortedBy { it.name.lowercase() } ?: emptyList()

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
        return CliResult(exitCode, output, if (out.isTimeout) errors + "fsm did not finish within 5 minutes." else errors, issues, written)
    }

    private fun resolve(dir: File, path: String): String = File(path).let { if (it.isAbsolute) it else File(dir, path) }.normalize().path
}
