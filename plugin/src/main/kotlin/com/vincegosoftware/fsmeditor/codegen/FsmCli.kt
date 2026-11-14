package com.vincegosoftware.fsmeditor.codegen

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.vincegosoftware.fsmeditor.core.Severity
import java.io.File
import java.nio.charset.StandardCharsets

/** A problem fsm reported (`file:line: severity: message` on stderr). */
class CliIssue(val file: String, /** One-based line, as printed. */ val line: Int, val severity: Severity, val message: String)

class CliResult(
    val exitCode: Int,
    val output: List<String>,
    val errors: List<String>,
    val issues: List<CliIssue>,
    /** Files fsm wrote (full paths). */
    val written: List<String>,
    /** Whether the run was cancelled, its process destroyed. */
    val cancelled: Boolean = false,
) {
    /**
     * fsm's own message about the run, without its `fsm: ` prefix (the last such line on stderr,
     * such as "2 errors in the state machine; nothing was generated."), or null.
     */
    val message: String? get() = errors.lastOrNull { it.startsWith(FsmCli.PREFIX) }?.substring(FsmCli.PREFIX.length)
}

/**
 * Runs `fsm`, the command-line tool of FSM Editor (github.com/vincedupuis/fsm-cli, the same
 * program as in VS Code, Visual Studio and CI), to generate code: `fsm write <file> --template <jinja> --out <folder>`.
 * The plugin bundles it in cli/<platform>/, with its templates in a templates/ folder next to it.
 */
object FsmCli {
    /** The prefix of fsm's own messages on stderr. */
    const val PREFIX = "fsm: "

    private val ISSUE_LINE = Regex("^(.+?):(\\d+): (error|warning|info): (.*)$")

    private val exe: String get() = if (SystemInfo.isWindows) "fsm.exe" else "fsm"

    /** Platform folder names, as in fsm-cli's release archives (`fsm-<version>-<platform>`). */
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

    /** The executable bundled with the plugin (`cli/<platform>/fsm`, or `cli/fsm`), or null. */
    private fun bundled(): File? {
        // The plugin's jar is in <plugin>/lib.
        val jar = PathManager.getJarPathForClass(FsmCli::class.java) ?: return null
        val dir = File(jar).parentFile?.parentFile?.resolve("cli") ?: return null
        return listOf(File(dir, "$platform/$exe"), File(dir, exe)).firstOrNull { it.isFile }
    }

    /** The fsm executable to run: the configured one, the bundled one, or one on the PATH. Null when none is found. */
    fun locate(configured: String): File? {
        if (configured.isNotBlank()) {
            val f = File(configured.trim().trim('"'))
            return if (f.isFile) f else null
        }
        bundled()?.let { return it }
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir.trim('"'), exe)
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** Templates shipped with fsm (the .jinja files of the templates folder next to it), where `-t <name>` finds them. Empty when [cli] is null. */
    fun bundledTemplates(cli: File?): List<File> =
        cli?.let { File(it.absoluteFile.parentFile, "templates").listFiles { f -> f.name.endsWith(".jinja") } }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** The arguments that generate code for [source]. */
    fun writeArgs(source: String, template: String, output: String): List<String> = listOf("write", source, "--template", template, "--out", output)

    fun commandLine(cli: File, args: List<String>): String =
        (listOf(cli.path) + args).joinToString(" ") { if (it.isNotEmpty() && it.none { c -> c == ' ' || c == '"' || c == '\t' }) it else "\"" + it.replace("\"", "\\\"") + "\"" }

    /** Runs [cli]; cancelling [indicator] destroys its process. */
    fun run(cli: File, args: List<String>, workingDirectory: File, indicator: ProgressIndicator? = null): CliResult {
        // Unpacked plugin files may lose their executable bit.
        if (!SystemInfo.isWindows && !cli.canExecute()) cli.setExecutable(true)
        val command = GeneralCommandLine(listOf(cli.path) + args)
            .withWorkDirectory(workingDirectory)
            .withCharset(StandardCharsets.UTF_8)
        val handler = CapturingProcessHandler(command)
        val out = if (indicator != null) handler.runProcessWithProgressIndicator(indicator, 5 * 60 * 1000) else handler.runProcess(5 * 60 * 1000)
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
        val exitCode = if (out.isTimeout || out.isCancelled) -1 else out.exitCode
        return CliResult(exitCode, output, if (out.isTimeout) errors + "${cli.nameWithoutExtension} did not finish within 5 minutes." else errors, issues, written, out.isCancelled)
    }

    private fun resolve(dir: File, path: String): String = File(path).let { if (it.isAbsolute) it else File(dir, path) }.normalize().path
}
