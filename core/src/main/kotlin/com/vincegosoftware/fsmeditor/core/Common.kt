package com.vincegosoftware.fsmeditor.core

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.TimeUnit

/** What fsm-editor-common answered with an error (`<fn>: <message>`), or why it couldn't answer. */
class CommonException(message: String) : Exception(message)

/**
 * fsm-editor-common (github.com/vincedupuis/fsm-editor-common, Rust): the file services of the editions, written once.
 * Reading and writing a machine's two files, converting old (XMI) files, validation, the grammar's checks, comparing,
 * merging and code generation (fsm-cli's) are requests to its sidecar, `fsm-editor-common serve`, started on first use
 * and kept running: one JSON request per line on its standard input, one JSON answer per line on its standard output
 * (its `api.schema.json`). It holds no state: when it exits, the next request starts it again. Requests are answered
 * one at a time, from any thread. A port of the Visual Studio edition's `Common.cs`.
 */
object Common {
    private val lock = Any()
    private var process: Process? = null
    private var input: Writer? = null
    private var output: BufferedReader? = null
    private var next = 0

    /**
     * The sidecar's path, when set; else the system property `fsm.common` (the tests), the environment variable
     * `FSM_EDITOR_COMMON`, or the plugin's own (`cli/<platform>/fsm-editor-common`, beside its `lib/`).
     */
    @Volatile
    var executablePath: String? = null

    /** The result of [fn] with arguments [argsJson] (a JSON object), parsed ([PlainJson.parse]). */
    fun call(fn: String, argsJson: String): Any? = synchronized(lock) {
        var attempt = 0
        while (true) {
            val id = ++next
            val line = try {
                start()
                input!!.write("{\"id\":$id,\"fn\":${PlainJson.quote(fn)},\"args\":$argsJson}\n")
                input!!.flush()
                output!!.readLine() ?: throw IOException("fsm-editor-common ended.")
            } catch (e: IOException) {
                // Ended (or never started): once more with a new one.
                stop()
                if (attempt++ == 0) continue
                throw CommonException("fsm-editor-common doesn't answer: ${e.message}")
            }
            val answer = PlainJson.parse(line) as? Map<*, *> ?: throw CommonException("fsm-editor-common answered: $line")
            (answer["error"] as? String)?.let { throw CommonException(it) }
            return answer["result"]
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * Starts the sidecar now if it isn't running, so that the first request doesn't wait for it: starting a process can
     * take seconds on Windows (an antivirus scanning it, the IDE starting). Called from a background thread when a
     * project opens; a failure is left to the first request to report.
     */
    fun ensureStarted() = synchronized(lock) {
        try {
            start()
        } catch (_: IOException) {
            stop()
        }
    }

    /** Arguments as a JSON object: values are JSON texts already (see [str]). */
    fun args(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> PlainJson.quote(k) + ":" + v }

    /** A text as JSON; null gives null. */
    fun str(s: String?): String = if (s == null) "null" else PlainJson.quote(s)

    /** The platform's folder name, as in the release archives' names (`darwin-arm64`, `windows-x64`...). */
    val platform: String
        get() {
            val name = System.getProperty("os.name").lowercase()
            val os = when {
                name.startsWith("windows") -> "windows"
                name.startsWith("mac") -> "darwin"
                else -> "linux"
            }
            val arch = if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
            return "$os-$arch"
        }

    private val exe: String get() = if (platform.startsWith("windows")) "fsm-editor-common.exe" else "fsm-editor-common"

    /** The sidecar bundled with the plugin: `cli/<platform>/` beside the `lib/` folder holding this class's jar. */
    fun bundled(): String? {
        val jar = jarOf(Common::class.java) ?: return null
        return File(jar.parentFile?.parentFile, "cli/$platform/$exe").takeIf { it.isFile }?.path
    }

    /**
     * The jar holding [type], from its class file's URL (`jar:file:/…/lib/core.jar!/…`), as IntelliJ's
     * `PathManager.getJarPathForClass` finds it: the IDE's class loaders give classes no code source.
     */
    internal fun jarOf(type: Class<*>): File? {
        val url = type.getResource(type.simpleName + ".class")?.toString() ?: return null
        if (!url.startsWith("jar:file:")) return null
        val path = url.removePrefix("jar:").substringBefore("!/")
        // A URL that isn't a valid URI (spaces left as they are): its path as written, `/C:/…` as `C:/…`.
        return runCatching { File(java.net.URI(path)) }.getOrNull()
            ?: File(path.removePrefix("file:").replace(Regex("^/+(?=[A-Za-z]:)"), "").replace("%20", " "))
    }

    private fun start() {
        if (process?.isAlive == true) return
        val path = executablePath ?: System.getProperty("fsm.common") ?: System.getenv("FSM_EDITOR_COMMON") ?: bundled()
        if (path.isNullOrEmpty() || !File(path).isFile) throw IOException("the sidecar is missing (${path ?: "no path"}).")
        if (!File(path).canExecute()) File(path).setExecutable(true)
        val p = ProcessBuilder(path, "serve").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = p
        input = p.outputStream.bufferedWriter(Charsets.UTF_8)
        output = p.inputStream.bufferedReader(Charsets.UTF_8)
    }

    private fun stop() {
        process?.destroy()
        process = null
        input = null
        output = null
    }

    /** Ends the sidecar (closing its input ends it), e.g. when the IDE closes. */
    fun shutdown() = synchronized(lock) {
        val p = process ?: return
        try {
            input?.close()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        } catch (_: IOException) {
            p.destroy()
        }
        process = null
        input = null
        output = null
    }
}
