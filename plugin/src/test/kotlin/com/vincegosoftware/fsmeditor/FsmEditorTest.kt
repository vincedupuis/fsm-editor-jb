package com.vincegosoftware.fsmeditor

import com.intellij.json.psi.JsonFile
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.MachineFiles
import com.vincegosoftware.fsmeditor.core.MachineTexts
import com.vincegosoftware.fsmeditor.core.PointD
import com.vincegosoftware.fsmeditor.editor.DiagramDiffRequest
import com.vincegosoftware.fsmeditor.editor.DiagramDiffViewer
import com.vincegosoftware.fsmeditor.editor.EditorPanel
import com.vincegosoftware.fsmeditor.editor.FsmEditorProvider
import com.vincegosoftware.fsmeditor.editor.FsmFileEditor
import java.io.File

/**
 * The plugin inside a headless IDE: file types, diagram editor, the two documents of a machine, undo, validation,
 * old files, the diagram diff and merge.
 */
class FsmEditorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // The repo's files (the examples, the plugin's cli/ in the sandbox): outside the roots a test may read by
        // default, on Windows at least.
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, File("..").canonicalPath)
    }

    private val examples = File(System.getProperty("fsm.examples") ?: "../examples")
    /** Old files (XMI) of the examples. */
    private val old = File(examples.parentFile, "plugin/src/test/resources/old")

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private val FsmFileEditor.panel: EditorPanel get() = pane.panel

    /** Adds a machine file (and its layout file when [layout] isn't null) and opens its diagram. */
    private fun openDiagram(name: String, text: String, layout: String? = null): FsmFileEditor {
        if (layout != null) myFixture.addFileToProject("$name.layout", layout)
        val file = myFixture.addFileToProject(name, text).virtualFile
        val editor = FsmEditorProvider().createEditor(project, file) as FsmFileEditor
        Disposer.register(testRootDisposable, editor)
        waitFor { editor.panel.session != null }
        return editor
    }

    private fun openExample(name: String): FsmFileEditor =
        openDiagram(name, File(examples, name).readText(), File(examples, "$name.layout").takeIf { it.isFile }?.readText())

    private fun addExample(name: String) {
        myFixture.addFileToProject(name, File(examples, name).readText())
        File(examples, "$name.layout").takeIf { it.isFile }?.let { myFixture.addFileToProject("$name.layout", it.readText()) }
    }

    private fun default(name: String) = MachineFiles.write(FsmModel.createDefault(name))

    private fun text(file: com.intellij.openapi.vfs.VirtualFile?) = file?.let { FileDocumentManager.getInstance().getDocument(it)?.text }

    /** The text on disk (the file system's, not the document's). */
    private fun disk(file: com.intellij.openapi.vfs.VirtualFile?) = file?.let { com.intellij.openapi.vfs.VfsUtilCore.loadText(it) }

    /** `Broken.fsm`: a copy of MediaPlayer.fsm whose `Stopped -play-> Playing` has the guard `count > 0`. */
    private val broken: String
        get() {
            val media = File(examples, "MediaPlayer.fsm").readText().replace("\r\n", "\n")
            assertTrue(media.contains(PLAY))
            return media.replaceFirst(PLAY, PLAY.replace("\"target\"", "\"guard\": \"count > 0\", \"target\""))
        }

    @Scenario("text-editor-json")
    fun testFsmFilesAreJsonWithTheDiagramFirst() {
        val psi = myFixture.configureByText("Machine.fsm", default("Machine").modelText)
        val file = psi.virtualFile
        assertSame(FsmFileType, file.fileType)
        assertTrue(psi is JsonFile)
        val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
        assertTrue(providers.any { it is FsmEditorProvider })
        assertTrue(providers.size >= 2) // the diagram and the text
        val layout = myFixture.addFileToProject("Machine.fsm.layout", default("Machine").layoutText!!).virtualFile
        assertEquals("JSON", layout.fileType.name)
    }

    @Scenario("old-file-opens-unchanged")
    fun testOldFilesAreXmlAndOpenUnchanged() {
        val editor = openDiagram("Old.fsm", OLD)
        assertSame(OldFsmFileType, editor.file.fileType)
        assertTrue(editor.panel.session!!.model.vertices.any { it.name == "Idle" })
        assertFalse(editor.isModified)
        assertEquals(OLD, text(editor.file))
        assertEquals(OLD, disk(editor.file))
        assertNull(editor.file.parent.findChild("Old.fsm.layout"))
        // Also the old files of the examples.
        val payment = openDiagram("Payment.fsm", File(old, "Payment.fsm").readText())
        assertSame(OldFsmFileType, payment.file.fileType)
        assertFalse(payment.isModified)
        assertNull(payment.file.parent.findChild("Payment.fsm.layout"))
    }

    /**
     * The IDE saves only changed documents, and an old file is converted at its first change: that change, then the
     * save, writes the model file in the current format and its layout file.
     */
    @Scenario("old-file-saved-converts")
    fun testSavingAnOldFileConvertsIt() {
        val editor = openDiagram("Old.fsm", OLD)
        val session = editor.panel.session!!
        session.model.vertices.first { it.name == "Idle" }.x += 40
        session.commit()
        assertTrue(editor.isModified)
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(editor.isModified)
        val layout = editor.file.parent.findChild("Old.fsm.layout")
        assertNotNull("saving writes the layout file", layout)
        val model = disk(editor.file)!!
        assertFalse(com.vincegosoftware.fsmeditor.core.Format.isOldFormat(model))
        val read = com.vincegosoftware.fsmeditor.core.Format.readMachine(model, disk(layout))
        assertEquals(",Idle,state1*", read.model!!.vertices.joinToString(",") { it.name })
        assertTrue(disk(layout)!!.contains("\"Idle\""))
    }

    fun testDiagramEditsAreOneUndoableChangeOfBothFiles() {
        val original = default("Machine")
        val editor = openDiagram("Machine.fsm", original.modelText, original.layoutText)
        val layoutFile = editor.file.parent.findChild("Machine.fsm.layout")!!
        val session = editor.panel.session!!
        assertEquals("Machine", session.model.name)

        val state = session.createVertex("state", PointD(500.0, 300.0))!!
        assertTrue(text(editor.file)!!.contains("\"${state.name}\""))
        assertTrue(text(layoutFile)!!.contains("\"${state.name}\""))
        val edited = text(editor.file)
        val editedLayout = text(layoutFile)

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(original.modelText, text(editor.file))
        assertEquals(original.layoutText, text(layoutFile))
        // The diagram follows the documents.
        waitFor { session.model.vertices.none { it.name == state.name } }

        assertTrue(undo.isRedoAvailable(editor))
        undo.redo(editor)
        assertEquals(edited, text(editor.file))
        assertEquals(editedLayout, text(layoutFile))
        waitFor { session.model.vertices.any { it.name == state.name } }
    }

    @Scenario("move-changes-layout-only")
    fun testAMoveChangesOnlyTheLayoutFile() {
        val editor = openExample("MediaPlayer.fsm")
        val layoutFile = editor.file.parent.findChild("MediaPlayer.fsm.layout")!!
        val model = disk(editor.file)
        val layout = disk(layoutFile)
        val session = editor.panel.session!!
        session.model.vertices.first { it.name == "Off" }.x += 40
        session.commit()
        FileDocumentManager.getInstance().saveAllDocuments()
        assertFalse(editor.isModified)
        assertEquals(model, disk(editor.file))
        assertFalse(layout == disk(layoutFile))
    }

    @Scenario("text-edit-reaches-diagram")
    fun testTextChangesReachTheDiagram() {
        myFixture.addFileToProject("Broken.fsm.layout", File(examples, "MediaPlayer.fsm.layout").readText())
        // As text (the fixture's editor) and in the diagram.
        val file = myFixture.configureByText("Broken.fsm", broken).virtualFile
        val editor = FsmEditorProvider().createEditor(project, file) as FsmFileEditor
        Disposer.register(testRootDisposable, editor)
        waitFor { editor.panel.session != null }
        val guards = { editor.panel.session!!.model.transitions.mapNotNull { it.guard.ifEmpty { null } } }
        waitFor { "count > 0" in guards() }
        val problems = { myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }.filter { it.startsWith("Guard:") } }
        assertEquals(1, problems().size)

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            val at = document.text.indexOf("count > 0")
            document.replaceString(at, at + "count > 0".length, "isReady()")
        }
        assertEquals(emptyList<String>(), problems())
        waitFor { "isReady()" in guards() && "count > 0" !in guards() }
        FileDocumentManager.getInstance().saveDocument(document)
        assertTrue(disk(file)!!.contains("\"guard\": \"isReady()\""))
    }

    @Scenario("validation-at-line")
    fun testValidationIsAtTheLineOfTheElement() {
        val text = broken
        myFixture.configureByText("Broken.fsm", text)
        val guard = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).filter { it.description?.startsWith("Guard:") == true }
        assertEquals(guard.map { it.description }.toString(), 1, guard.size)
        assertTrue(guard[0].description, guard[0].description.contains("no variables"))
        assertEquals(text.split("\n").indexOfFirst { it.contains("count > 0") }, myFixture.editor.document.getLineNumber(guard[0].startOffset))
    }

    @Scenario("submachine-references-resolve")
    fun testSubmachinesResolveAndValidationReachesTheText() {
        addExample("Payment.fsm")
        val order = File(examples, "Order.fsm").readText()
        val editor = openExample("Order.fsm")
        val session = editor.panel.session!!
        waitFor { session.submachines.isNotEmpty() }
        assertTrue(session.submachines.values.all { it.found })
        assertTrue(session.machines.any { it.name == "Payment" })
        // No problem about the references, in the text either.
        myFixture.configureFromExistingVirtualFile(editor.file)
        val references = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description }
            .filter { Regex("not found|no entry or exit point|referenced as").containsMatchIn(it) }
        assertEquals(emptyList<String>(), references)

        // A broken reference is reported in the text, at its line.
        myFixture.configureByText("Broken.fsm", order.replace("\"Payment.fsm\"", "\"Missing.fsm\""))
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description }
        assertTrue(errors.toString(), errors.any { it.startsWith("The referenced state machine 'Missing.fsm'") })
    }

    fun testOpeningASubmachineThatIsNoFileFails() {
        val file = myFixture.addFileToProject("Order.fsm", File(examples, "Order.fsm").readText()).virtualFile
        // A NUL character no path can hold, and an empty path (the folder).
        assertFalse(FsmDocuments.openSubmachine(project, file, "Pay\u0000ment.fsm"))
        val local = File(examples, "Order.fsm").absoluteFile
        val localFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local)!!
        assertFalse(FsmDocuments.openSubmachine(project, localFile, ""))
    }

    @Scenario("valid-machines-no-problems")
    fun testValidMachinesHaveNoErrorsInTheText() {
        val names = listOf("MediaPlayer.fsm", "Order.fsm", "Payment.fsm")
        names.forEach(::addExample)
        for (name in names) {
            myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(name))
            assertEquals(name, emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).mapNotNull { it.description })
        }
    }

    /** A file with merge conflicts opens as the merge; taking the incoming side and completing writes it without markers. */
    @Scenario("merge-conflicts")
    fun testMergeConflictsOpenAsTheDiagramMerge() {
        val payment = File(examples, "Payment.fsm").readText().replace("\r\n", "\n")
        val captured = "      \"entry\": \"capture()\",\n"
        assertTrue(payment.contains(captured))
        val conflicted = payment.replace(captured, "<<<<<<< HEAD\n      \"entry\": \"capture(); log()\",\n=======\n      \"entry\": \"settle()\",\n>>>>>>> feature\n")
        val editor = openDiagram("Payment.fsm", conflicted, File(examples, "Payment.fsm.layout").readText())
        // The result starts as the current side, the file unchanged.
        val entry = { editor.panel.session!!.model.vertices.first { it.name == "Captured" }.entry }
        assertEquals("capture(); log()", entry())
        assertEquals(conflicted, text(editor.file))
        waitFor { editor.panel.diff?.role == "result" }
        assertEquals(1, editor.panel.diff!!.count)
        assertFalse(editor.isModified)
        editor.pane.mergeChoose(null, "incoming")
        waitFor { entry() == "settle()" }
        editor.pane.mergeComplete()
        assertFalse(com.vincegosoftware.fsmeditor.core.Merge.hasConflictMarkers(text(editor.file)))
        assertNull(editor.panel.diff)
    }

    /** Compare as Diagrams: the file and another version of it side by side, the change marked on both. */
    @Scenario("compare-as-diagrams")
    fun testTheDiagramDiffMarksTheChanges() {
        val media = File(examples, "MediaPlayer.fsm").readText().replace("\r\n", "\n")
        val layout = File(examples, "MediaPlayer.fsm.layout").readText()
        assertTrue(media.contains("\"entry\": \"rewind()\""))
        val file = myFixture.addFileToProject("MediaPlayer.fsm", media.replace("\"entry\": \"rewind()\"", "\"entry\": \"rewind(); beep()\"")).virtualFile
        myFixture.addFileToProject("MediaPlayer.fsm.layout", layout)
        val viewer = DiagramDiffViewer(DiagramDiffRequest(project, file, MachineTexts(media, layout), "HEAD"))
        Disposer.register(testRootDisposable, viewer)
        val panels = com.intellij.util.ui.UIUtil.findComponentsOfType(viewer.component, EditorPanel::class.java)
        assertEquals(2, panels.size)
        waitFor { panels.all { it.diff != null && it.session != null } }
        assertEquals(listOf("old", "new"), panels.map { it.diff!!.role })
        // The change, marked on Stopped in both.
        for (p in panels) {
            assertEquals(1, p.diff!!.count)
            val stopped = p.session!!.model.vertices.first { it.name == "Stopped" }.id
            assertEquals("changed", p.diff!!.marks[stopped])
        }
        assertTrue(panels[0].readOnly)
        // Linked: moving the view of one moves the other's.
        panels[0].canvas.zoomAt(1.5, 10.0, 10.0)
        assertEquals(panels[0].canvas.zoom, panels[1].canvas.zoom, 1e-9)
        // build/screenshots/diff.png: the older version on the left, the file on the right.
        val component = viewer.component
        component.setSize(1600, 700)
        layoutAll(component)
        invalidateAll(component)
        layoutAll(component)
        panels.forEach { it.canvas.fit() }
        val image = java.awt.image.BufferedImage(1600, 700, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        component.paint(g)
        g.dispose()
        val out = File(System.getProperty("fsm.screenshots") ?: "build/screenshots").apply { mkdirs() }
        javax.imageio.ImageIO.write(image, "png", File(out, "diff.png"))
    }

    /** Paints the whole editor (diagram, toolbox, properties) into build/screenshots, for a look at the rendering. */
    fun testPaintsTheEditor() {
        val out = File(System.getProperty("fsm.screenshots") ?: "build/screenshots").apply { mkdirs() }
        addExample("Payment.fsm")
        for (name in listOf("MediaPlayer.fsm", "Order.fsm")) {
            val editor = openExample(name)
            val panel = editor.panel
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
        val editor = openExample("MediaPlayer.fsm")
        val panel = editor.panel
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
        val editor = openExample("MediaPlayer.fsm")
        val panel = editor.panel
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
        waitFor { text(editor.file.parent.findChild("MediaPlayer.fsm.layout"))!!.contains("\"group\"") }
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
    @Scenario("trace-player")
    fun testTracePlayerReplaysTheDemoLogOnTheDiagrams() {
        val payment = openExample("Payment.fsm")
        val order = openExample("Order.fsm")
        addExample("MediaPlayer.fsm")
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
        val paymentPanel = payment.panel
        assertEquals(listOf("Payment", "Order.Payment"), paymentPanel.trace!!.instances.map { it.label })

        val at = s.entries.withIndex().filter { (_, e) -> e.record.machine == "Order.Payment" && e.record.id == "Authorizing" && e.record.kind == com.vincegosoftware.fsmeditor.core.TraceKind.Enter }[1].index
        player.seek(at)
        push()
        val orderPanel = order.panel
        assertEquals(listOf("Payment"), orderPanel.trace!!.instances.single().active)
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
     * The Trace Player on MediaPlayer's diagram: demo.log's records, a log followed as it grows, and a log in a format of
     * the Trace formats setting.
     */
    @Scenario("trace-player")
    fun testTracePlayerFollowsALogAndReadsAConfiguredFormat() {
        val media = openExample("MediaPlayer.fsm")
        addExample("Order.fsm")
        addExample("Payment.fsm")
        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("fsm", "trace")
        val player = com.vincegosoftware.fsmeditor.trace.TracePlayerService.getInstance(project)
        val s = player.session
        val settings = com.vincegosoftware.fsmeditor.settings.FsmSettings.getInstance().state
        val formats = settings.traceFormats
        fun active(): List<String> {
            media.showTrace(player.viewFor(media.file.path))
            return media.panel.trace?.instances?.flatMap { it.active } ?: emptyList()
        }
        try {
            // demo.log: at its last record of MediaPlayer, the diagram shows its active states.
            val demo = File(dir, "demo.log").apply { writeText(File(examples, "demo.log").readText()) }
            player.open(listOf(demo.path))
            waitFor { s.machines.isNotEmpty() && s.entries.size == 153 }
            player.seek(s.entries.indexOfLast { it.record.machine == "MediaPlayer" })
            assertTrue(active().isNotEmpty())
            player.close(s.traces.single())

            // A log followed as it grows (live: at its last record).
            val run = File(dir, "run.log").apply { writeText("fsm MediaPlayer transition MediaPlayer.initial#1 initial -> Off\nfsm MediaPlayer enter Off Off\n") }
            player.open(listOf(run.path))
            waitFor { s.entries.size == 2 }
            waitFor { "Off" in active() }
            run.appendText("fsm MediaPlayer exit Off Off\nfsm MediaPlayer transition Off#1 powerOn -> On\nfsm MediaPlayer enter On On\n")
            waitFor { s.entries.size == 5 && s.cursor == 4 }
            waitFor { "On" in active() && "Off" !in active() }
            player.close(s.traces.single())

            // A log in a configured format.
            settings.traceFormats = """[{ "name": "plain", "machine": "MediaPlayer", "rules": [{ "kind": "enter", "match": "^ENTER (?<name>\\w+)$" }] }]"""
            player.formatsChanged()
            val plain = File(dir, "plain.log").apply { writeText("ENTER Off\nENTER On\n") }
            player.open(listOf(plain.path))
            waitFor { s.traces.singleOrNull()?.records?.size == 2 }
            assertEquals("plain", s.traces.single().format.name)
            assertEquals(emptyList<String>(), s.problems)
        } finally {
            s.traces.toList().forEach { player.close(it) }
            settings.traceFormats = formats
            player.formatsChanged()
        }
    }

    /**
     * As in the VS Code edition: an armed tool takes every click, a quick second click included, and stays armed when
     * it can't place its element.
     */
    fun testArmedToolsTakeEveryClick() {
        val editor = openDiagram("Machine.fsm", default("Machine").modelText, default("Machine").layoutText)
        val panel = editor.panel
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
        val editor = openDiagram("Machine.fsm", default("Machine").modelText, default("Machine").layoutText)
        val panel = editor.panel
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

    companion object {
        /** Stopped's transition on play in MediaPlayer.fsm (Paused has one too, later). */
        const val PLAY = "{ \"type\": \"external\", \"trigger\": \"play\", \"target\": \"Playing\" }"

        /** `Old.fsm`: a machine in the old format (XMI): an initial pseudostate, a state Idle, an unnamed state. */
        val OLD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xmi:XMI xmi:version="20131001" xmlns:xmi="http://www.omg.org/spec/XMI/20131001" xmlns:uml="http://www.omg.org/spec/UML/20161101">
              <uml:Model xmi:id="m" name="Old">
                <packagedElement xmi:type="uml:StateMachine" xmi:id="sm" name="Old">
                  <region xmi:type="uml:Region" xmi:id="r">
                    <subvertex xmi:type="uml:Pseudostate" xmi:id="i" kind="initial"/>
                    <subvertex xmi:type="uml:State" xmi:id="a" name="Idle"/>
                    <subvertex xmi:type="uml:State" xmi:id="b"/>
                    <transition xmi:type="uml:Transition" xmi:id="t0" source="i" target="a"/>
                    <transition xmi:type="uml:Transition" xmi:id="t1" source="a" target="b"><trigger xmi:id="g" name="go"/></transition>
                  </region>
                </packagedElement>
              </uml:Model>
            </xmi:XMI>
        """.trimIndent() + "\n"
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
