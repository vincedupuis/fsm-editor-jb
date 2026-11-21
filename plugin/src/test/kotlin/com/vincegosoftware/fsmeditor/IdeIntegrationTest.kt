package com.vincegosoftware.fsmeditor

import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vincegosoftware.fsmeditor.codegen.CodeGenToolWindowFactory
import com.vincegosoftware.fsmeditor.core.Contrast
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.editor.DiagramCanvas
import com.vincegosoftware.fsmeditor.editor.FsmEditorProvider
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import com.vincegosoftware.fsmeditor.editor.Theme
import com.vincegosoftware.fsmeditor.services.FsmSchemas
import com.vincegosoftware.fsmeditor.services.Git
import com.vincegosoftware.fsmeditor.trace.TracePlayerToolWindowFactory
import java.io.File
import java.nio.file.Files

/**
 * The plugin's integration with the IDE, in a headless IDE: the Text tab's JSON Schemas and completion, the layout
 * file following its model file, the IDE's Copy, Cut, Paste and Delete on the diagram, the tool windows, the actions
 * and their menus, and the versions Compare as Diagrams gets from Git.
 */
class IdeIntegrationTest : BasePlatformTestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $what" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun default(name: String) = MachineFiles.write(FsmModel.createDefault(name))

    fun testTheTextTabHasTheSchemasOfBothFiles() {
        val model = myFixture.addFileToProject("Machine.fsm", default("Machine").modelText).virtualFile
        val layout = myFixture.addFileToProject("Machine.fsm.layout", default("Machine").layoutText!!).virtualFile
        val providers = FsmSchemas().getProviders(project)
        val forModel = providers.filter { it.isAvailable(model) }
        val forLayout = providers.filter { it.isAvailable(layout) }
        assertEquals(1, forModel.size)
        assertEquals(1, forLayout.size)
        assertEquals("fsm.schema.json", forModel[0].schemaFile?.name)
        assertEquals("fsm-layout.schema.json", forLayout[0].schemaFile?.name)
        // The Text tab completes the model file's fields from its schema.
        myFixture.configureByText("Other.fsm", "{\n  \"version\": 1,\n  \"<caret>\"\n}")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue("completion from the schema: $items", items.any { it.contains("elements") })
    }

    fun testTheLayoutFileFollowsItsModelFileWhenRenamedOrMoved() {
        val model = myFixture.addFileToProject("A.fsm", default("A").modelText).virtualFile
        myFixture.addFileToProject("A.fsm.layout", default("A").layoutText!!)
        WriteAction.runAndWait<Throwable> { model.rename(this, "B.fsm") }
        waitFor("B.fsm.layout") { model.parent.findChild("B.fsm.layout") != null }
        assertNull(model.parent.findChild("A.fsm.layout"))
        val folder = WriteAction.computeAndWait<com.intellij.openapi.vfs.VirtualFile, Throwable> { model.parent.createChildDirectory(this, "sub") }
        WriteAction.runAndWait<Throwable> { model.move(this, folder) }
        waitFor("sub/B.fsm.layout") { folder.findChild("B.fsm.layout") != null }
        assertNull(folder.parent.findChild("B.fsm.layout"))
    }

    fun testTheIdesCopyCutPasteAndDeleteActOnTheDiagram() {
        myFixture.addFileToProject("MediaPlayer.fsm.layout", File(examples, "MediaPlayer.fsm.layout").readText())
        val file = myFixture.addFileToProject("MediaPlayer.fsm", File(examples, "MediaPlayer.fsm").readText()).virtualFile
        val editor = FsmEditorProvider().createEditor(project, file) as FsmFileEditor
        Disposer.register(testRootDisposable, editor)
        waitFor("the diagram") { editor.pane.panel.session != null }
        val panel = editor.pane.panel
        val session = panel.session!!
        val states = { session.model.vertices.count { it.type.key == "state" } }
        val before = states()
        session.selection = linkedSetOf(session.model.vertices.first { it.name == "Off" }.id)
        // What the canvas hands the IDE's Copy, Cut, Paste and Delete (its uiDataSnapshot), while it has the focus.
        assertTrue(panel.canvas is UiDataProvider)
        val context = SimpleDataContext.builder().build()
        val handler = panel.clipboardHandler
        val (copy, paste, cut, delete) = listOf(handler, handler, handler, handler)
        assertTrue(copy.isCopyEnabled(context))
        copy.performCopy(context)
        paste.performPaste(context)
        waitFor("the paste") { panel.session!!.model.vertices.count { it.type.key == "state" } == before + 1 }
        val pasted = panel.session!!
        pasted.selection = linkedSetOf(pasted.model.vertices.first { it.name == "Off" }.id)
        cut.performCut(context)
        waitFor("the cut") { panel.session!!.model.vertices.none { it.name == "Off" } }
        val after = panel.session!!
        after.selection = linkedSetOf(after.model.vertices.first { it.name == "Fault" }.id)
        assertTrue(delete.canDeleteElement(context))
        delete.deleteElement(context)
        waitFor("the delete") { panel.session!!.model.vertices.none { it.name == "Fault" } }
    }

    fun testTheToolWindowsShowTheirContent() {
        for (factory in listOf(TracePlayerToolWindowFactory(), CodeGenToolWindowFactory())) {
            val window = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
            factory.createToolWindowContent(project, window)
            assertEquals(factory.javaClass.simpleName, 1, window.contentManager.contentCount)
            Disposer.register(testRootDisposable, window.contentManager)
        }
    }

    fun testTheActionsAreInTheirMenus() {
        val am = ActionManager.getInstance()
        val ids = listOf("FsmEditor.NewStateMachine", "FsmEditor.GenerateCode", "FsmEditor.ExportSvg", "FsmEditor.ExportPng", "FsmEditor.OpenAsText",
            "FsmEditor.CompareAsDiagrams", "FsmEditor.OpenInDiagram", "FsmEditor.OpenTrace", "FsmEditor.NewStateMachineInFolder")
        for (id in ids) assertNotNull(id, am.getAction(id))
        fun children(group: String) = (am.getAction(group) as DefaultActionGroup).getChildActionsOrStubs().mapNotNull { am.getId(it) }
        assertTrue(children("NewGroup").contains("FsmEditor.NewStateMachine"))
        for (menu in listOf("ProjectViewPopupMenu", "EditorTabPopupMenu", "ToolsMenu")) {
            val group = if (menu == "ToolsMenu") "FsmEditor.ToolsMenu" else "FsmEditor.FileActions"
            assertTrue("$group in $menu", children(menu).contains(group))
        }
        assertTrue(children("FsmEditor.FileActions").containsAll(listOf("FsmEditor.GenerateCode", "FsmEditor.ExportSvg", "FsmEditor.ExportPng",
            "FsmEditor.OpenAsText", "FsmEditor.OpenInDiagram", "FsmEditor.CompareAsDiagrams")))
        assertTrue(children("FsmEditor.ToolsMenu").containsAll(listOf("FsmEditor.NewStateMachineInFolder", "FsmEditor.OpenTrace")))
    }

    fun testCompareAsDiagramsGetsTheVersionsFromGit() {
        val dir = Files.createTempDirectory("fsm-git").toFile()
        try {
            fun git(vararg args: String) {
                val p = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(out, 0, p.waitFor())
            }
            git("init", "-q")
            git("config", "user.email", "test@example.com")
            git("config", "user.name", "Test")
            val file = File(dir, "M.fsm")
            file.writeText(default("M").modelText)
            git("add", "M.fsm")
            git("commit", "-q", "-m", "M")
            file.writeText(file.readText().replace("\"M\"", "\"N\""))
            assertEquals(dir.canonicalFile, Git.rootOf(file)?.canonicalFile)
            assertEquals(default("M").modelText, Git.show(file, "HEAD")?.replace("\r\n", "\n"))
            assertNull("no version of an untracked file", Git.show(File(dir, "Other.fsm"), "HEAD"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The diagram's colors in the IDE's Default (light) and Darcula color schemes, as `Theme` takes them from the scheme:
     * the text on the canvas and on a state, and every signal color through `Theme.mark` and `Theme.textOf`, as
     * `DiagramCanvas` draws them, measured against WCAG (4.5:1 for text, 3:1 for marks).
     */
    fun testTheDiagramIsReadableInTheLightAndDarkSchemes() {
        val colors = EditorColorsManager.getInstance()
        val before = colors.globalScheme
        val rgb = { c: java.awt.Color -> Triple(c.red, c.green, c.blue) }
        val backgrounds = mutableListOf<Double>()
        try {
            for (name in listOf("Default", "Darcula")) {
                val scheme = colors.getScheme(name) ?: error("no $name scheme")
                colors.setGlobalScheme(scheme)
                Theme.reset()
                val th = Theme.current
                backgrounds += Contrast.luminance(rgb(th.background))
                val low = mutableListOf<String>()
                fun check(what: String, c: java.awt.Color, min: Double) {
                    for ((on, bg) in listOf("canvas" to th.background, "state" to th.stateFill)) {
                        val r = Contrast.ratio(rgb(c), rgb(bg))
                        if (r < min) low += "$what on the $on: ${"%.2f".format(r)} < $min"
                    }
                }
                check("text", th.foreground, Contrast.TEXT)
                for (i in 0 until 6) {
                    check("trace color $i", th.mark(DiagramCanvas.traceColor(i)), Contrast.MARK)
                    check("label in trace color $i", th.textOf(DiagramCanvas.traceColor(i, false)), Contrast.TEXT)
                }
                check("warning", th.mark(th.warning), Contrast.MARK)
                check("error", th.mark(th.error), Contrast.MARK)
                assertEquals("$name: contrast too low", emptyList<String>(), low)
            }
            assertTrue("a light and a dark background: $backgrounds", backgrounds[0] > 0.5 && backgrounds[1] < 0.1)
        } finally {
            colors.setGlobalScheme(before)
            Theme.reset()
        }
    }
}
