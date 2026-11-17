package com.vincegosoftware.fsmeditor.services

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.core.MachineTexts
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The few things the diagram diff and merge ask of Git: a file's text at a version, the common version of a file with
 * merge conflicts, marking files resolved. Runs `git` from the PATH; without it, or outside a repository, each gives
 * nothing and the views do without. A port of the Visual Studio edition's `Git.cs`.
 */
object Git {
    /** Runs git in [dir]; its output, or null when it fails. */
    private fun run(dir: File, vararg args: String): String? = try {
        val command = GeneralCommandLine("git", *args).withWorkDirectory(dir).withCharset(StandardCharsets.UTF_8)
        val output = CapturingProcessHandler(command).runProcess(15000)
        if (output.isTimeout || output.exitCode != 0) null else output.stdout
    } catch (_: ExecutionException) {
        null
    }

    /** The root of the repository holding [file], or null. */
    fun rootOf(file: File): File? {
        val dir = file.absoluteFile.parentFile ?: return null
        val root = run(dir, "rev-parse", "--show-toplevel")?.trim()
        return if (root.isNullOrEmpty()) null else File(root).absoluteFile
    }

    private fun relative(root: File, file: File): String = file.absoluteFile.canonicalFile.relativeTo(root.canonicalFile).path.replace('\\', '/')

    /** The text of [file] at [rev] (`HEAD`; `""` the index; `:1` the common version of a merge…), or null. */
    fun show(file: File, rev: String): String? {
        val root = rootOf(file) ?: return null
        return run(root, "show", "$rev:${relative(root, file)}")
    }

    /** The common version of a file with merge conflicts, and of its layout file, when Git gives it. */
    fun commonVersion(file: File): MachineTexts? {
        val model = show(file, ":1") ?: return null
        return MachineTexts(model, show(File(MachineFiles.layoutPathOf(file.path)), ":1"))
    }

    /** Marks files resolved (stages them); false when Git couldn't. */
    fun add(vararg files: File): Boolean {
        val root = rootOf(files[0]) ?: return false
        val paths = files.filter { it.isFile }.map { relative(root, it) }
        return run(root, "add", "--", *paths.toTypedArray()) != null
    }
}
