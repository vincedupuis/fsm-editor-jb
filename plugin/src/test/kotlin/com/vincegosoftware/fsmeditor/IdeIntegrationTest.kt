package com.vincegosoftware.fsmeditor

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.TestActionEvent
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
    override fun setUp() {
        super.setUp()
        // The repo's files (the examples, the plugin's cli/ in the sandbox): outside the roots a test may read by
        // default, on Windows at least.
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, File("..").canonicalPath)
    }

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

    @Scenario("text-editor-json")
    fun testTheTextTabHasTheSchemasOfBothFiles() {
        val model = myFixture.addFileToProject("Machine.fsm", default("Machine").modelText).virtualFile
        val layout = myFixture.addFileToProject("Machine.fsm.layout", default("Machine").layoutText!!).virtualFile
        // Both JSON in the text editor.
        assertTrue(model.fileType is com.intellij.json.JsonFileType)
        assertEquals("JSON", layout.fileType.name)
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

    @Scenario("layout-follows-rename")
    fun testTheLayoutFileFollowsItsModelFileWhenRenamedOrMoved() {
        val model = myFixture.addFileToProject("Rename.fsm", File(examples, "Payment.fsm").readText()).virtualFile
        myFixture.addFileToProject("Rename.fsm.layout", File(examples, "Payment.fsm.layout").readText())
        WriteAction.runAndWait<Throwable> { model.rename(this, "Renamed.fsm") }
        waitFor("Renamed.fsm.layout") { model.parent.findChild("Renamed.fsm.layout") != null }
        assertNull(model.parent.findChild("Rename.fsm.layout"))
        val folder = WriteAction.computeAndWait<com.intellij.openapi.vfs.VirtualFile, Throwable> { model.parent.createChildDirectory(this, "sub") }
        WriteAction.runAndWait<Throwable> { model.move(this, folder) }
        waitFor("sub/Renamed.fsm.layout") { folder.findChild("Renamed.fsm.layout") != null }
        assertNull(folder.parent.findChild("Renamed.fsm.layout"))
    }

    private fun open(name: String, text: String, layout: String? = null): FsmFileEditor {
        if (layout != null) myFixture.addFileToProject("$name.layout", layout)
        val file = myFixture.addFileToProject(name, text).virtualFile
        val editor = FsmEditorProvider().createEditor(project, file) as FsmFileEditor
        Disposer.register(testRootDisposable, editor)
        waitFor("the diagram of $name: ${editor.source.state.problems?.map { it.message }}") { editor.pane.panel.session != null }
        return editor
    }

    private fun openExample(name: String) =
        open(name, File(examples, name).readText(), File(examples, "$name.layout").readText())

    /** Runs the IDE's action [id] as its keys and menus do, on [context] (the focused component's data). */
    private fun perform(id: String, context: DataContext) {
        val action = ActionManager.getInstance().getAction(id)
        val e = TestActionEvent.createTestEvent(action, context)
        action.update(e)
        assertTrue("$id is enabled", e.presentation.isEnabled)
        action.actionPerformed(e)
    }

    /** What the IDE's actions get from the diagram when it has the focus (the canvas's uiDataSnapshot). */
    private fun contextOf(editor: FsmFileEditor): DataContext = DataManager.getInstance().getDataContext(editor.pane.panel.canvas)

    private fun documentText(file: com.intellij.openapi.vfs.VirtualFile?) = file?.let { FileDocumentManager.getInstance().getDocument(it)?.text }

    private fun disk(file: com.intellij.openapi.vfs.VirtualFile?) = file?.let { com.intellij.openapi.vfs.VfsUtilCore.loadText(it) }

    /**
     * The IDE's Copy, Paste, Cut and Delete (CopyAction and the others, as Ctrl+C… and the Edit menu run them) and
     * Select All on the diagram, then Undo, Redo and saving the paste. The IDE's Select All action is the text
     * editor's: on the diagram, its key (Ctrl+A, ⌘A) is the canvas's.
     */
    @Scenario("ide-edit-commands", "undo-redo-save-both-files")
    fun testTheIdesCopyCutPasteAndDeleteActOnTheDiagram() {
        // The IDE's data manager, which asks the components (the tests' ignores them).
        com.intellij.ide.impl.HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        myFixture.addFileToProject("Payment.fsm", File(examples, "Payment.fsm").readText())
        myFixture.addFileToProject("Payment.fsm.layout", File(examples, "Payment.fsm.layout").readText())
        val order = openExample("Order.fsm")
        val empty = open("Empty.fsm", "{\n  \"version\": 1,\n  \"name\": \"Empty\",\n  \"type\": \"behavioral\"\n}\n")
        val orderText = documentText(order.file)
        val orderSession = order.pane.panel.session!!
        val names = orderSession.model.vertices.map { it.name }.filter { it.isNotEmpty() && !it.endsWith("*") }
        assertTrue(names.contains("Cart"))

        // Select All and Copy in Order.
        val canvas = order.pane.panel.canvas
        assertTrue(canvas is UiDataProvider)
        val mask = if (com.intellij.openapi.util.SystemInfo.isMac) java.awt.event.InputEvent.META_DOWN_MASK else java.awt.event.InputEvent.CTRL_DOWN_MASK
        val selectAll = java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mask, java.awt.event.KeyEvent.VK_A, 'a')
        canvas.keyListeners.forEach { it.keyPressed(selectAll) }
        assertEquals(orderSession.model.vertices.map { it.id }.toSet(), orderSession.selection)
        perform(IdeActions.ACTION_COPY, contextOf(order))

        // Paste in Empty.fsm: Order's elements, Order unchanged.
        perform(IdeActions.ACTION_PASTE, contextOf(empty))
        val emptyNames = { empty.pane.panel.session!!.model.vertices.map { it.name } }
        waitFor("the paste") { emptyNames().containsAll(names) }
        assertEquals(orderText, documentText(order.file))
        assertFalse(order.isModified)

        // Undo, Redo, and saving both files.
        assertTrue(empty.isModified)
        val layout = { empty.file.parent.findChild("Empty.fsm.layout") }
        val layoutBefore = disk(layout())
        val undoContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.FILE_EDITOR, empty).build()
        perform(IdeActions.ACTION_UNDO, undoContext)
        waitFor("the undo") { "Cart" !in emptyNames() }
        // The documents are unchanged: the texts on disk. (The IDE keeps a document marked changed after an Undo back
        // to its saved text, a text file's too, until it is saved.)
        assertEquals(emptyList<String>(), empty.source.documents().map { FileDocumentManager.getInstance().getFile(it)!! }.filter { documentText(it) != disk(it) }.map { it.name })
        perform(IdeActions.ACTION_REDO, undoContext)
        waitFor("the redo") { "Cart" in emptyNames() }
        assertTrue(empty.isModified)
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(empty.isModified)
        assertTrue(disk(empty.file)!!.contains("\"Cart\""))
        assertNotNull(layout())
        assertFalse(layoutBefore == disk(layout()))

        // In Order: Cut a selected state, Delete another, then both undone (Order back as it was).
        val session = { order.pane.panel.session!! }
        val orderNames = { session().model.vertices.map { it.name } }
        session().selection = linkedSetOf(session().model.vertices.first { it.name == "Cart" }.id)
        perform(IdeActions.ACTION_CUT, contextOf(order))
        waitFor("the cut") { "Cart" !in orderNames() }
        session().selection = linkedSetOf(session().model.vertices.first { it.name == "Shipped" }.id)
        perform(IdeActions.ACTION_DELETE, contextOf(order))
        waitFor("the delete") { "Shipped" !in orderNames() }
        assertTrue(orderNames().contains("PaymentFailed"))
        val orderUndo = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.FILE_EDITOR, order).build()
        perform(IdeActions.ACTION_UNDO, orderUndo)
        perform(IdeActions.ACTION_UNDO, orderUndo)
        assertEquals(orderText, documentText(order.file))
        waitFor("Order back") { orderNames().containsAll(listOf("Cart", "Shipped")) }
    }

    @Scenario("trace-player")
    fun testTheToolWindowsShowTheirContent() {
        // Show Trace Player is its tool window (View › Tool Windows › Trace Player): registered, and filled by its factory.
        assertTrue(com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.any { it.id == "Trace Player" && it.factoryClass == TracePlayerToolWindowFactory::class.java.name })
        for (factory in listOf(TracePlayerToolWindowFactory(), CodeGenToolWindowFactory())) {
            val window = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
            factory.createToolWindowContent(project, window)
            assertEquals(factory.javaClass.simpleName, 1, window.contentManager.contentCount)
            Disposer.register(testRootDisposable, window.contentManager)
        }
    }

    @Scenario("commands-registered")
    fun testTheActionsAreInTheirMenus() {
        // Show Trace Player: the Trace Player tool window, in View › Tool Windows.
        assertTrue(com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.any { it.id == "Trace Player" })
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

    @Scenario("compare-as-diagrams")
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
