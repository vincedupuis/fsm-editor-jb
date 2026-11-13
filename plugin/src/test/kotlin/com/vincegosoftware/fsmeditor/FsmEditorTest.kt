package com.vincegosoftware.fsmeditor

import com.intellij.ide.scratch.ScratchFileCreationHelper
import com.intellij.lang.LanguageUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.PointD
import com.vincegosoftware.fsmeditor.core.Xmi
import com.vincegosoftware.fsmeditor.editor.EditorPanel
import com.vincegosoftware.fsmeditor.editor.FsmEditorProvider
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import java.io.File

/** The plugin inside a headless IDE: file type, diagram editor, document sync, undo and validation. */
class FsmEditorTest : BasePlatformTestCase() {
    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun openDiagram(name: String, text: String): FsmFileEditor {
        val file = myFixture.addFileToProject(name, text).virtualFile
        val editor = FsmEditorProvider().createEditor(project, file) as FsmFileEditor
        Disposer.register(testRootDisposable, editor)
        waitFor { (editor.component as EditorPanel).session != null }
        return editor
    }

    fun testFsmFilesAreXmlWithTheDiagramFirst() {
        val psi = myFixture.configureByText("Machine.fsm", Xmi.toXmi(FsmModel.createDefault("Machine")))
        val file = psi.virtualFile
        assertSame(FsmFileType, file.fileType)
        assertTrue(psi is XmlFile && psi.rootTag?.localName == "XMI")
        assertTrue(FsmLanguage in LanguageUtil.getFileLanguages()) // listed in New › Scratch File
        val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
        assertTrue(providers.any { it is FsmEditorProvider })
        assertTrue(providers.size >= 2) // the diagram and the XMI text
    }

    fun testNewScratchFilesStartWithAMachine() {
        val context = ScratchFileCreationHelper.Context().apply { language = FsmLanguage }
        ScratchFileCreationHelper.EXTENSION.forLanguage(FsmLanguage).prepareText(project, context, DataContext.EMPTY_CONTEXT)
        assertEquals(Xmi.toXmi(FsmModel.createDefault("StateMachine")), context.text)
        assertEquals(-1, context.caretOffset) // opens in the diagram, not at an offset in the text
    }

    fun testDiagramEditsAreOneUndoableDocumentChange() {
        val original = Xmi.toXmi(FsmModel.createDefault("Machine"))
        val editor = openDiagram("Machine.fsm", original)
        val document = FileDocumentManager.getInstance().getDocument(editor.file)!!
        val session = (editor.component as EditorPanel).session!!
        assertEquals("Machine", session.model.name)

        val state = session.createVertex("state", PointD(500.0, 300.0))!!
        assertTrue(document.text.contains("name=\"${state.name}\""))
        val edited = Xmi.toXmi(session.model)
        assertEquals(edited, document.text)

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(original, document.text)
        // The diagram follows the document.
        waitFor { session.model.vertices.none { it.id == state.id } }

        // A redo brings back the text this editor wrote, which the diagram shows again.
        assertTrue(undo.isRedoAvailable(editor))
        undo.redo(editor)
        assertEquals(edited, document.text)
        waitFor { session.model.vertices.any { it.id == state.id } }
    }

    fun testTextChangesReachTheDiagram() {
        val editor = openDiagram("Machine.fsm", Xmi.toXmi(FsmModel.createDefault("Machine")))
        val document = FileDocumentManager.getInstance().getDocument(editor.file)!!
        val panel = editor.component as EditorPanel
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("name=\"Idle\"", "name=\"Waiting\""))
        }
        waitFor { panel.session!!.model.vertices.any { it.name == "Waiting" } }
    }

    fun testSubmachinesResolveAndValidationReachesTheText() {
        myFixture.addFileToProject("Payment.fsm", File(examples, "Payment.fsm").readText())
        val order = File(examples, "Order.fsm").readText()
        val editor = openDiagram("Order.fsm", order)
        val session = (editor.component as EditorPanel).session!!
        waitFor { session.submachines.isNotEmpty() }
        assertTrue(session.submachines.values.all { it.found })
        assertTrue(session.machines.any { it.name == "Payment" })

        // A broken reference is reported in the XMI text.
        myFixture.configureByText("Broken.fsm", order.replace("Payment.fsm#", "Missing.fsm#"))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description }
        assertTrue(errors.toString(), errors.any { it.startsWith("The referenced state machine 'Missing.fsm#") })
    }

    fun testOpeningAnHrefThatIsNoFileFails() {
        val file = myFixture.addFileToProject("Order.fsm", File(examples, "Order.fsm").readText()).virtualFile
        // A NUL character no path can hold, and an href without a file part (the folder).
        assertFalse(FsmDocuments.openHref(project, file, "Pay%00ment.fsm#sm"))
        val local = File(examples, "Order.fsm").absoluteFile
        val localFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local)!!
        assertFalse(FsmDocuments.openHref(project, localFile, "#sm"))
    }

    fun testValidMachinesHaveNoErrorsInTheText() {
        myFixture.configureByText("MediaPlayer.fsm", File(examples, "MediaPlayer.fsm").readText())
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description })
    }

    /** Paints the whole editor (diagram, toolbox, properties) into build/screenshots, for a look at the rendering. */
    fun testPaintsTheEditor() {
        val out = File(System.getProperty("fsm.screenshots") ?: "build/screenshots").apply { mkdirs() }
        myFixture.addFileToProject("Payment.fsm", File(examples, "Payment.fsm").readText())
        for (name in listOf("MediaPlayer.fsm", "Order.fsm")) {
            val editor = openDiagram(name, File(examples, name).readText())
            val panel = editor.component as EditorPanel
            waitFor { panel.session!!.machines.isNotEmpty() || name == "MediaPlayer.fsm" }
            val session = panel.session!!
            val selected = session.model.vertices.first { it.type == com.vincegosoftware.fsmeditor.core.VertexType.State && (it.regions.isNotEmpty() || it.submachine.isNotEmpty()) }
            for ((suffix, selection) in listOf("" to emptySet(), "-selected" to setOf(selected.id))) {
                session.selection = LinkedHashSet(selection)
                panel.renderProps()
                panel.setSize(1400, 900)
                // Twice, as Swing does: wrapping text knows its height once its width is set.
                layoutAll(panel)
                invalidateAll(panel)
                layoutAll(panel)
                panel.canvas.fit()
                val image = java.awt.image.BufferedImage(1400, 900, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                panel.paint(g)
                g.dispose()
                javax.imageio.ImageIO.write(image, "png", File(out, name.removeSuffix(".fsm") + suffix + ".png"))
            }
        }
    }

    /** The PNG export: the diagram cropped to its content with a 20 px margin, at twice its size, also written to build/screenshots. */
    fun testExportsThePngAtTwiceTheSize() {
        val editor = openDiagram("MediaPlayer.fsm", File(examples, "MediaPlayer.fsm").readText())
        val panel = editor.component as EditorPanel
        val session = panel.session!!
        session.selection = linkedSetOf(session.model.vertices.first().id)
        val image = panel.exportPng()!!
        val b = com.vincegosoftware.fsmeditor.core.Geometry.contentBounds(session.index, panel.canvas::labelWidth)
        assertEquals(kotlin.math.ceil((b.w + 40) * 2).toInt(), image.width)
        assertEquals(kotlin.math.ceil((b.h + 40) * 2).toInt(), image.height)
        assertEquals(java.awt.Color.WHITE.rgb, image.getRGB(0, 0))
        val out = File(System.getProperty("fsm.screenshots") ?: "build/screenshots").apply { mkdirs() }
        javax.imageio.ImageIO.write(image, "png", File(out, "MediaPlayer-export.png"))
    }

    /** G on the canvas groups the selection; a selected member shows the group's dotted rectangle (build/screenshots/MediaPlayer-group.png). */
    fun testGGroupsTheSelection() {
        val editor = openDiagram("MediaPlayer.fsm", File(examples, "MediaPlayer.fsm").readText())
        val panel = editor.component as EditorPanel
        val session = panel.session!!
        val stopped = session.model.vertices.first { it.name == "Stopped" }
        val paused = session.model.vertices.first { it.name == "Paused" }
        session.selection = linkedSetOf(stopped.id, paused.id)
        val canvas = panel.canvas
        // Headless, key events reach no focus owner: hand G to the canvas's key listeners.
        val press = java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_G, 'g')
        canvas.keyListeners.forEach { it.keyPressed(press) }
        assertTrue(stopped.group.isNotEmpty())
        assertEquals(stopped.group, paused.group)
        waitFor { editor.file.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)!!.text.contains("<group id=\"${stopped.group}\"") } }
        session.selection = linkedSetOf(stopped.id)
        panel.setSize(1400, 900)
        layoutAll(panel)
        invalidateAll(panel)
        layoutAll(panel)
        canvas.fit()
        val image = java.awt.image.BufferedImage(1400, 900, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        panel.paint(g)
        g.dispose()
        val out = File(System.getProperty("fsm.screenshots") ?: "build/screenshots").apply { mkdirs() }
        javax.imageio.ImageIO.write(image, "png", File(out, "MediaPlayer-group.png"))
    }

    /**
     * The Trace Player replays examples/demo.log on the open diagrams: Payment.fsm shows its two instances (alone and
     * inside Order) in their colors. Paints Order and Payment at the second checkout, and the tool window, into
     * build/screenshots/trace-*.png.
     */
    fun testTracePlayerReplaysTheDemoLogOnTheDiagrams() {
        val payment = openDiagram("Payment.fsm", File(examples, "Payment.fsm").readText())
        val order = openDiagram("Order.fsm", File(examples, "Order.fsm").readText())
        myFixture.addFileToProject("MediaPlayer.fsm", File(examples, "MediaPlayer.fsm").readText())
        val log = File.createTempFile("demo", ".log").apply {
            writeText(File(examples, "demo.log").readText())
            deleteOnExit()
        }
        val player = com.vincegosoftware.fsmeditor.trace.TracePlayerService.getInstance(project)
        // The editors aren't in FileEditorManager here: hand them their highlights as the service does.
        fun push() {
            for (e in listOf(payment, order)) e.showTrace(player.viewFor(e.file.path))
        }
        player.open(listOf(log.path))
        val s = player.session
        // The machines and the log are read in the background.
        waitFor { s.machines.isNotEmpty() && s.problems.isEmpty() && s.entries.isNotEmpty() }
        assertEquals(153, s.entries.size)
        assertEquals(emptyList<String>(), s.problems)
        push()
        val paymentPanel = payment.component as EditorPanel
        assertEquals(listOf("Payment", "Order.Payment"), paymentPanel.trace!!.instances.map { it.label })

        val at = s.entries.withIndex().filter { (_, e) -> e.record.machine == "Order.Payment" && e.record.id == "p_auth" && e.record.kind == com.vincegosoftware.fsmeditor.core.TraceKind.Enter }[1].index
        player.seek(at)
        push()
        val orderPanel = order.component as EditorPanel
        assertEquals(listOf("o_pay"), orderPanel.trace!!.instances.single().active)
        assertEquals("▶ Trace · event checkout", orderPanel.trace!!.statusText)

        val out = File(System.getProperty("fsm.screenshots") ?: "build/screenshots").apply { mkdirs() }
        for ((name, panel) in listOf("trace-Order" to orderPanel, "trace-Payment" to paymentPanel)) {
            panel.renderStatus()
            panel.setSize(1400, 900)
            layoutAll(panel)
            invalidateAll(panel)
            layoutAll(panel)
            panel.canvas.fit()
            val image = java.awt.image.BufferedImage(1400, 900, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            panel.paint(g)
            g.dispose()
            javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
        }
        val window = com.vincegosoftware.fsmeditor.trace.TracePlayerPanel(player)
        Disposer.register(testRootDisposable, window)
        window.setSize(1200, 360)
        layoutAll(window)
        invalidateAll(window)
        layoutAll(window)
        layoutAll(window)
        val image = java.awt.image.BufferedImage(1200, 360, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        window.paint(g)
        g.dispose()
        javax.imageio.ImageIO.write(image, "png", File(out, "trace-window.png"))
        player.close(s.traces.single())
        push()
        assertNull(orderPanel.trace)
    }

    /**
     * As in the VS Code edition: an armed tool takes every click, a quick second click included, and stays armed when
     * it can't place its element.
     */
    fun testArmedToolsTakeEveryClick() {
        val editor = openDiagram("Machine.fsm", Xmi.toXmi(FsmModel.createDefault("Machine")))
        val panel = editor.component as EditorPanel
        val session = panel.session!!
        val canvas = panel.canvas
        // Headless, the canvas isn't showing: hand the presses to its mouse listeners.
        fun press(x: Int, y: Int, clicks: Int) {
            val e = java.awt.event.MouseEvent(
                canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                x, y, clicks, false, java.awt.event.MouseEvent.BUTTON1,
            )
            canvas.mouseListeners.forEach { it.mousePressed(e) }
            val r = java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x, y, clicks, false, java.awt.event.MouseEvent.BUTTON1)
            canvas.mouseListeners.forEach { it.mouseReleased(r) }
        }
        val states = { session.model.vertices.count { it.type == com.vincegosoftware.fsmeditor.core.VertexType.State } }
        val before = states()
        canvas.setTool("state", true)
        press(500, 400, 1)
        press(700, 400, 2)
        assertEquals(before + 2, states())
        assertEquals("state", canvas.tool)

        // A connection point reference needs a submachine state: refused on empty canvas, the tool stays armed.
        canvas.setTool("connectionPointRef")
        press(900, 600, 1)
        assertEquals("connectionPointRef", canvas.tool)
    }

    /**
     * Double-clicks as in the VS Code edition: two plain presses close in time and place, whatever the click count; a
     * press with a tool armed resets the tracking, so a click right after placing an element is a plain press.
     */
    fun testDoubleClicksFollowTheReference() {
        val editor = openDiagram("Machine.fsm", Xmi.toXmi(FsmModel.createDefault("Machine")))
        val panel = editor.component as EditorPanel
        val session = panel.session!!
        val canvas = panel.canvas
        fun send(id: Int, x: Int, y: Int, clicks: Int) {
            val e = java.awt.event.MouseEvent(
                canvas, id, System.currentTimeMillis(), if (id == java.awt.event.MouseEvent.MOUSE_PRESSED) java.awt.event.InputEvent.BUTTON1_DOWN_MASK else 0,
                x, y, clicks, false, java.awt.event.MouseEvent.BUTTON1,
            )
            canvas.mouseListeners.forEach { if (id == java.awt.event.MouseEvent.MOUSE_PRESSED) it.mousePressed(e) else it.mouseReleased(e) }
        }
        fun click(x: Int, y: Int, clicks: Int) {
            send(java.awt.event.MouseEvent.MOUSE_PRESSED, x, y, clicks)
            send(java.awt.event.MouseEvent.MOUSE_RELEASED, x, y, clicks)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        fun inlineEditing() = com.intellij.util.ui.UIUtil.findComponentsOfType(panel, javax.swing.text.JTextComponent::class.java)
            .any { it.parent is javax.swing.JLayeredPane }
        val states = { session.model.vertices.count { it.type == com.vincegosoftware.fsmeditor.core.VertexType.State } }
        val before = states()

        // Placed with a one-shot tool, a state opens its name for editing; the next quick click there is a plain press.
        canvas.setTool("state")
        click(500, 400, 1)
        assertEquals(before + 1, states())
        assertTrue(inlineEditing())
        click(500, 400, 2)
        assertFalse(inlineEditing())
        assertEquals(before + 1, states())

        // Two plain clicks on empty canvas: a double-click, which adds a state.
        click(1100, 700, 1)
        click(1100, 700, 1)
        assertEquals(before + 2, states())
    }

    private fun invalidateAll(c: java.awt.Component) {
        c.invalidate()
        if (c is java.awt.Container) c.components.forEach { invalidateAll(it) }
    }

    private fun layoutAll(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.doLayout()
            c.components.forEach { layoutAll(it) }
        }
    }
}
