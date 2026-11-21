package com.vincegosoftware.fsmeditor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path

/**
 * The plugin in the IDE's own editor manager (FileEditorManagerImpl, headless): opening files in the diagram and as
 * text, the actions on them, following submachine states. The files are copies of the examples in a fresh folder on
 * disk.
 */
class IdeEditorsTest : FileEditorManagerTestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples").absoluteFile
    private lateinit var dir: File
    private val fem get() = manager!!

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, examples.parentFile.canonicalPath)
        dir = FileUtil.createTempDirectory("fsm", "scenarios")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.canonicalPath)
        examples.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(dir, it.name)) }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $what" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun file(name: String): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, name)) ?: error("no $name")

    private fun selected(file: VirtualFile): FileEditor? = fem.getSelectedEditor(file)

    /** Opens [name] as the IDE does on a double-click in the Project view, and waits for its diagram. */
    private fun openDiagram(name: String): FsmFileEditor {
        val file = file(name)
        OpenFileDescriptor(project, file).navigate(true)
        val editor = selected(file)
        assertTrue("$name opens in the diagram: $editor", editor is FsmFileEditor)
        editor as FsmFileEditor
        waitFor("the diagram of $name") { editor.pane.panel.session != null }
        return editor
    }

    private fun context(vararg data: Pair<com.intellij.openapi.actionSystem.DataKey<*>, Any>): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        @Suppress("UNCHECKED_CAST")
        for ((key, value) in data) builder.add(key as com.intellij.openapi.actionSystem.DataKey<Any>, value)
        return builder.build()
    }

    /** Runs the action [id] as its menus do. */
    private fun perform(id: String, context: DataContext) {
        val action = ActionManager.getInstance().getAction(id)
        val e = TestActionEvent.createTestEvent(action, context)
        action.update(e)
        assertTrue("$id is enabled", e.presentation.isEnabled)
        action.actionPerformed(e)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /** Alt+double-click on the element named [name] of [editor]'s diagram (the canvas's mouse listeners; headless, it isn't showing). */
    private fun altDoubleClick(editor: FsmFileEditor, name: String) {
        val canvas = editor.pane.panel.canvas
        val v = editor.pane.panel.session!!.model.vertices.first { it.name == name }
        val at = canvas.toScreen(v.x + v.w / 2, v.y + v.h / 2)
        repeat(2) {
            for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                val e = MouseEvent(
                    canvas, id, System.currentTimeMillis(), InputEvent.ALT_DOWN_MASK or (if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0),
                    at.x.toInt(), at.y.toInt(), 1, false, MouseEvent.BUTTON1,
                )
                canvas.mouseListeners.forEach { if (id == MouseEvent.MOUSE_PRESSED) it.mousePressed(e) else it.mouseReleased(e) }
            }
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    @Scenario("open-diagram")
    fun testADoubleClickOpensTheDiagram() {
        val editor = openDiagram("MediaPlayer.fsm")
        assertFalse(editor.isModified)
        // Drawn: the canvas paints the machine.
        val panel = editor.pane.panel
        assertTrue(panel.session!!.model.vertices.any { it.name == "Stopped" })
        panel.setSize(1000, 700)
        panel.doLayout()
        panel.canvas.setSize(800, 600)
        panel.canvas.fit()
        val image = java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        panel.canvas.paint(g)
        g.dispose()
        val colors = HashSet<Int>()
        for (x in 0 until 800 step 4) for (y in 0 until 600 step 4) colors.add(image.getRGB(x, y))
        assertTrue("the diagram is drawn", colors.size > 2)
        assertFalse(editor.isModified)
    }

    @Scenario("open-as-text-and-back")
    fun testOpenAsTextAndBack() {
        val diagram = openDiagram("Payment.fsm")
        val file = diagram.file
        perform("FsmEditor.OpenAsText", context(PlatformCoreDataKeys.FILE_EDITOR to diagram, CommonDataKeys.VIRTUAL_FILE to file))
        val text = selected(file)
        assertTrue("the file is in a text editor: $text", text is TextEditor)
        assertEquals(file, fem.selectedFiles.first())
        perform("FsmEditor.OpenInDiagram", context(PlatformCoreDataKeys.FILE_EDITOR to text!!, CommonDataKeys.VIRTUAL_FILE to file))
        assertSame("the diagram is back, on the same file", diagram, selected(file))
        assertEquals(file, fem.selectedFiles.first())
    }

    @Scenario("new-state-machine")
    fun testNewStateMachineOpensTheTemplateInTheDiagram() {
        val folder = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        val before = TestDialogManager.setTestInputDialog(TestInputDialog { "Machine" })
        try {
            // File › New › State Machine (the NewGroup's action), on the selected folder.
            perform("FsmEditor.NewStateMachine", context(CommonDataKeys.VIRTUAL_FILE to folder))
        } finally {
            TestDialogManager.setTestInputDialog(before)
        }
        val created = folder.findChild("Machine.fsm")
        assertNotNull(created)
        val template = MachineFiles.write(FsmModel.createDefault("Machine"))
        assertEquals(template.modelText, VfsUtilCore.loadText(created!!))
        assertEquals(template.layoutText, folder.findChild("Machine.fsm.layout")?.let { VfsUtilCore.loadText(it) })
        val editor = selected(created)
        assertTrue("it opens in the diagram: $editor", editor is FsmFileEditor)
        assertEquals(created, fem.selectedFiles.first())
        waitFor("the diagram") { (editor as FsmFileEditor).pane.panel.session != null }
    }

    /** Following a submachine state: Alt+double-click, as in the other editions. */
    @Scenario("follow-reference")
    fun testFollowingASubmachineStateOpensItsMachine() {
        val order = openDiagram("Order.fsm")
        val payment = file("Payment.fsm")
        assertFalse(fem.isFileOpen(payment))
        altDoubleClick(order, "Payment")
        waitFor("Payment.fsm open") { fem.isFileOpen(payment) }
        assertTrue("Payment.fsm opens in the diagram", selected(payment) is FsmFileEditor)
        assertEquals(payment, fem.selectedFiles.first())
    }

    @Scenario("missing-submachine")
    fun testFollowingAMissingSubmachineSaysSo() {
        File(dir, "Lost.fsm").writeText(File(dir, "Order.fsm").readText().replace("\"Payment.fsm\"", "\"Missing.fsm\""))
        val lost = openDiagram("Lost.fsm")
        altDoubleClick(lost, "Payment")
        val message = "The referenced state machine file Missing.fsm does not exist."
        waitFor("the message") { UIUtil.findComponentsOfType(lost.pane.panel, javax.swing.JLabel::class.java).any { it.text == message && it.parent.isVisible } }
        assertEquals(listOf(lost.file), fem.openFiles.toList())
        assertNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "Missing.fsm")))
    }

    /** The save dialogs of the exports, answered with [target]. */
    private var target: File? = null

    private fun answerSaveDialogs() {
        val original = FileChooserFactory.getInstance()
        val saver = object : FileSaverDialog {
            override fun save(baseDir: VirtualFile?, filename: String?) = target?.let { VirtualFileWrapper(it) }
            override fun save(baseDir: Path?, filename: String?) = target?.let { VirtualFileWrapper(it) }
        }
        val fake = object : FileChooserFactory() {
            override fun createFileChooser(descriptor: FileChooserDescriptor, project: Project?, parent: Component?) = original.createFileChooser(descriptor, project, parent)
            override fun createPathChooser(descriptor: FileChooserDescriptor, project: Project?, parent: Component?) = original.createPathChooser(descriptor, project, parent)
            override fun createSaveFileDialog(descriptor: FileSaverDescriptor, project: Project?) = saver
            override fun createSaveFileDialog(descriptor: FileSaverDescriptor, parent: Component) = saver
            override fun createFileTextField(descriptor: FileChooserDescriptor, showHidden: Boolean, parent: com.intellij.openapi.Disposable?) =
                original.createFileTextField(descriptor, showHidden, parent)
            override fun installFileCompletion(field: javax.swing.JTextField, descriptor: FileChooserDescriptor, showHidden: Boolean, parent: com.intellij.openapi.Disposable?) =
                original.installFileCompletion(field, descriptor, showHidden, parent)
        }
        ApplicationManager.getApplication().replaceService(FileChooserFactory::class.java, fake, testRootDisposable)
    }

    @Scenario("export-svg-png")
    fun testExportsTheDiagramAsSvgAndPng() {
        answerSaveDialogs()
        val editor = openDiagram("MediaPlayer.fsm")
        val data = context(PlatformCoreDataKeys.FILE_EDITOR to editor, CommonDataKeys.VIRTUAL_FILE to editor.file)
        target = File(dir, "export.svg")
        perform("FsmEditor.ExportSvg", data)
        val svg = File(dir, "export.svg").readText()
        assertTrue(svg.contains("<svg ") && svg.contains("Stopped"))
        target = File(dir, "export.png")
        perform("FsmEditor.ExportPng", data)
        val png = javax.imageio.ImageIO.read(File(dir, "export.png"))
        assertNotNull("a PNG", png)
        val (w, h) = listOf("width", "height").map { Regex("<svg [^>]*\\b$it=\"([\\d.]+)\"").find(svg)!!.groupValues[1].toDouble() }
        assertEquals("the PNG's width, twice the SVG's ($w)", w * 2, png.width.toDouble(), 1.0)
        assertEquals("the PNG's height, twice the SVG's ($h)", h * 2, png.height.toDouble(), 1.0)
    }
}
