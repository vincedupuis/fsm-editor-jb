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
        assertEquals(Xmi.toXmi(session.model), document.text)

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(original, document.text)
        // The diagram follows the document.
        waitFor { session.model.vertices.none { it.id == state.id } }
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
