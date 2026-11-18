package com.vincegosoftware.fsmeditor.codegen

import com.intellij.openapi.application.PathManager
import com.vincegosoftware.fsmeditor.core.Common
import com.vincegosoftware.fsmeditor.core.Load
import com.vincegosoftware.fsmeditor.core.ModelJson
import com.vincegosoftware.fsmeditor.core.PlainJson
import com.vincegosoftware.fsmeditor.core.Severity
import java.io.File
import java.io.IOException

/** A problem the generator reported (validation or the template), at a file's line. */
class GenerateIssue(/** Full path of the .fsm file. */ val file: String, /** One-based line. */ val line: Int, val severity: Severity, val message: String)

class GenerateResult(
    /** 0 generated, 1 errors in the machines or the template, 2 a request it refuses (such as a .hbs template). */
    val exitCode: Int,
    /** Why nothing was generated, or null. */
    val error: String?,
    val issues: List<GenerateIssue>,
    /** Files written (full paths). */
    val written: List<String>,
)

/**
 * Code generation: fsm-cli's (its Jinja templates and code model), run by fsm-editor-common's sidecar (the same as in
 * VS Code, Visual Studio and fsm-cli's `fsm write`). The plugin bundles fsm-cli's templates in cli/templates. The
 * sidecar reads no files: the machine's text and those of the machines its submachine states use go in the request,
 * with the template's text, and the files to write come back. A port of the Visual Studio edition's `CodeGenerator.cs`.
 */
object CodeGenerator {
    /** The bundled templates (cli/templates/\*.jinja, the plugin's jar being in <plugin>/lib), where `-t <name>` finds them. */
    fun bundledTemplates(): List<File> {
        val jar = PathManager.getJarPathForClass(CodeGenerator::class.java) ?: return emptyList()
        return File(File(jar).parentFile?.parentFile, "cli/templates").listFiles { f -> f.name.endsWith(".jinja") }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /** A path as the generator's paths are written: `/`-separated, absolute (`/C:/work/Order.fsm` on Windows). */
    private fun slash(file: File): String = file.absolutePath.replace('\\', '/').let { if (it.startsWith("/")) it else "/$it" }

    /** A path the generator displays (relative to [dir], or absolute) as a file. */
    private fun unslash(dir: File, path: String): File {
        val p = if (path.length >= 3 && path[0] == '/' && path[2] == ':') path.substring(1) else path
        return File(p).let { if (it.isAbsolute) it else File(dir, p) }.normalize()
    }

    /** The texts of [source] and of the machines its submachine states use, transitively; one that can't be read is left out. */
    private fun readMachines(source: File): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        val queue = ArrayDeque(listOf(source.absoluteFile.normalize()))
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            val key = slash(file)
            if (key in files) continue
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                continue
            }
            files[key] = text
            for (sub in Load.submachineFilesOf(text)) if (sub.isNotEmpty()) queue.addLast(unslash(file.parentFile, sub))
        }
        return files
    }

    /** Generates code for [source] with a template's file into [output]. */
    fun generate(source: File, template: File, output: File): GenerateResult {
        val templateText = try {
            template.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return GenerateResult(2, "Cannot read the template ${template.path}: ${e.message}", emptyList(), emptyList())
        }
        val dir = source.absoluteFile.parentFile
        val files = readMachines(source)
        val args = Common.args(
            "cwd" to Common.str(slash(dir)),
            "files" to files.entries.joinToString(",", "{", "}") { (k, v) -> PlainJson.quote(k) + ":" + Common.str(v) },
            "generate" to "[" + Common.str(slash(source)) + "]",
            "template" to Common.str(templateText),
            "templateName" to Common.str(template.name),
        )
        val r = Common.call("generate", args) as Map<*, *>
        val exitCode = ModelJson.num(r, "exitCode").toInt()
        val issues = ModelJson.objects(r, "issues").map {
            val severity = when (ModelJson.str(it, "severity")) {
                "error" -> Severity.Error
                "warning" -> Severity.Warning
                else -> Severity.Info
            }
            GenerateIssue(unslash(dir, ModelJson.str(it, "file")).path, ModelJson.num(it, "line").toInt(), severity, ModelJson.str(it, "message"))
        }
        val written = mutableListOf<String>()
        if (exitCode == 0) {
            for (f in ModelJson.objects(r, "files")) {
                val target = File(output, ModelJson.str(f, "path")).normalize()
                target.parentFile?.mkdirs()
                target.writeText(ModelJson.str(f, "content"), Charsets.UTF_8)
                written.add(target.path)
            }
        }
        return GenerateResult(exitCode, r["error"] as? String, issues, written)
    }
}
