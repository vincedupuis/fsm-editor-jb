package com.vincegosoftware.fsmeditor.codegen

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.content.ContentFactory
import com.vincegosoftware.fsmeditor.FsmDocuments
import com.vincegosoftware.fsmeditor.core.Severity
import com.vincegosoftware.fsmeditor.services.FsmWorkspace
import com.vincegosoftware.fsmeditor.settings.FsmSettings
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Generates code for a machine (fsm-cli's generator, run by fsm-editor-common): asks for a template and a folder,
 * generates, reports the results.
 */
object CodeGeneration {
    private const val OUT_KEY = "fsmEditor.codegen.out."

    /** Templates offered for a machine: the bundled ones, then the *.jinja files of the project. */
    private fun templateChoices(project: Project): List<TemplateChoice> {
        val choices = CodeGenerator.bundledTemplates().map { TemplateChoice(it.path, it.nameWithoutExtension, "bundled template") }.toMutableList()
        val workspace = FsmWorkspace.getInstance(project)
        val found = ApplicationManager.getApplication().runReadAction(Computable {
            val output = ArrayList<VirtualFile>()
            val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return@Computable output
            VfsUtilCore.visitChildrenRecursively(base, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (output.size >= 50) return false
                    if (file.isDirectory) return file == base || (!file.name.startsWith(".") && file.name !in setOf("node_modules", "build", "out", "bin", "obj", "dist"))
                    if (file.extension == "jinja") output.add(file)
                    return true
                }
            })
            output
        })
        choices.addAll(found.sortedBy { it.path.lowercase() }.map { TemplateChoice(it.path, it.nameWithoutExtension, workspace.display(it.path)) })
        return choices
    }

    fun generate(project: Project, source: VirtualFile?) {
        if (source == null || !FsmDocuments.isFsm(source)) {
            Messages.showWarningDialog(project, "Select or open a .fsm state machine to generate code from.", "Generate Code")
            return
        }
        val settings = FsmSettings.getInstance().state
        val properties = PropertiesComponent.getInstance(project)
        val outKey = OUT_KEY + source.path
        val lastOut = properties.getValue(outKey) ?: source.parent.path
        val dialog = GenerateCodeDialog(project, source.nameWithoutExtension, templateChoices(project), settings.lastTemplate, lastOut)
        if (!dialog.showAndGet()) return
        val (template, output) = dialog.result
        settings.lastTemplate = template
        properties.setValue(outKey, output)

        if (settings.saveBeforeGenerating) {
            val documents = FileDocumentManager.getInstance()
            documents.unsavedDocuments.filter { FsmDocuments.isFsm(documents.getFile(it)) }.forEach { documents.saveDocument(it) }
        }
        // A relative output folder is relative to the machine's folder.
        val outDir = File(output).let { if (it.isAbsolute) it else File(source.parent.path, output) }.normalize()
        val workspace = FsmWorkspace.getInstance(project)
        val console = CodeGenOutput.getInstance(project)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        console.print("$time ${workspace.display(source.path)} with ${File(template).name} into ${workspace.display(outDir.path.replace('\\', '/'))}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        WindowManager.getInstance().getStatusBar(project)?.info = "Generating code for ${source.name}..."

        object : Task.Backgroundable(project, "Generating code for ${source.name}", true) {
            private var result: GenerateResult? = null
            private var failure: Exception? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = CodeGenerator.generate(File(source.path), File(template), outDir)
                } catch (e: Exception) {
                    failure = e
                }
            }

            override fun onFinished() {
                val r = result
                if (r == null) {
                    val message = failure?.message ?: "unknown error"
                    console.print("  $message\n", ConsoleViewContentType.ERROR_OUTPUT)
                    Messages.showErrorDialog(project, "Code generation failed: $message", "Code Generation Failed")
                    return
                }
                report(project, console, r, outDir.path)
            }
        }.queue()
    }

    private fun report(project: Project, console: CodeGenOutput, r: GenerateResult, output: String) {
        val workspace = FsmWorkspace.getInstance(project)
        for (issue in r.issues) {
            console.print("  ", ConsoleViewContentType.NORMAL_OUTPUT)
            console.link("${workspace.display(issue.file.replace('\\', '/'))}:${issue.line}: ${issue.severity.key}: ${issue.message}", issue)
            console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
        for (f in r.written) console.print("  wrote ${workspace.display(f.replace('\\', '/'))}\n", ConsoleViewContentType.NORMAL_OUTPUT)
        r.error?.let { console.print("  $it\n", ConsoleViewContentType.ERROR_OUTPUT) }
        // Show the new files in the project view.
        r.written.forEach { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.replace('\\', '/')) }
        LocalFileSystem.getInstance().findFileByPath(output.replace('\\', '/'))?.refresh(true, true)

        val where = FsmWorkspace.getInstance(project).display(File(output).absolutePath.replace('\\', '/'))
        if (r.exitCode == 0) {
            val n = r.written.size
            val summary = "$n file${if (n == 1) "" else "s"} written in $where"
            console.print("  $summary\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            WindowManager.getInstance().getStatusBar(project)?.info = "Generated $n file${if (n == 1) "" else "s"} in $where."
            NotificationGroupManager.getInstance().getNotificationGroup("FSM Editor")
                .createNotification("Code generated", summary + if (r.issues.isNotEmpty()) " (see FSM Code Generation for warnings)." else ".", NotificationType.INFORMATION)
                .notify(project)
            if (r.issues.isNotEmpty()) console.show()
            return
        }
        WindowManager.getInstance().getStatusBar(project)?.info = "Code generation failed."
        console.show()
        // The generator's own message says why, also for a request it refuses, such as a template.
        val reason = r.error ?: if (r.issues.any { it.severity == Severity.Error }) "The state machine has errors (see FSM Code Generation)."
        else "The generator stopped with exit code ${r.exitCode}."
        Messages.showErrorDialog(project, "$reason Details are in the FSM Code Generation tool window.", "Code Generation Failed")
    }
}

/** The FSM Code Generation tool window: the commands run and what the generator printed. */
@Service(Service.Level.PROJECT)
class CodeGenOutput(private val project: Project) : Disposable {
    private var console: ConsoleView? = null

    /** The console, made on first use; the tool window shows up from then on. */
    fun view(): ConsoleView {
        console?.let { return it }
        val view = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(this, view)
        console = view
        ToolWindowManager.getInstance(project).getToolWindow(ID)?.isAvailable = true
        return view
    }

    fun print(text: String, type: ConsoleViewContentType) = view().print(text, type)

    /** A problem line that opens the file at its line (a state machine opens in the diagram). */
    fun link(text: String, issue: GenerateIssue) {
        val file = LocalFileSystem.getInstance().findFileByPath(issue.file.replace('\\', '/'))
        val info: HyperlinkInfo? = when {
            file == null -> null
            FsmDocuments.isFsm(file) -> HyperlinkInfo { FsmDocuments.openInDiagram(it, file) }
            else -> OpenFileHyperlinkInfo(project, file, (issue.line - 1).coerceAtLeast(0))
        }
        if (info == null) print(text, ConsoleViewContentType.ERROR_OUTPUT) else view().printHyperlink(text, info)
    }

    fun show() {
        view()
        ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
    }

    override fun dispose() {}

    companion object {
        const val ID = "FSM Code Generation"

        fun getInstance(project: Project): CodeGenOutput = project.service()
    }
}

/** Shows the console of [CodeGenOutput]; the tool window stays hidden until code is first generated. */
class CodeGenToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = CodeGenOutput.getInstance(project).view()
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(console.component, "", false))
    }
}
