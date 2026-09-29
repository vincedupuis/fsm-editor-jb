package com.vincegosoftware.fsmeditor.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyProvider
import com.intellij.ide.CutProvider
import com.intellij.ide.DeleteProvider
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vincegosoftware.fsmeditor.core.ClipboardJson
import com.vincegosoftware.fsmeditor.core.DiagramSession
import com.vincegosoftware.fsmeditor.core.FsmModel
import com.vincegosoftware.fsmeditor.core.Geometry
import com.vincegosoftware.fsmeditor.core.Issue
import com.vincegosoftware.fsmeditor.core.Labels
import com.vincegosoftware.fsmeditor.core.MachineInfo
import com.vincegosoftware.fsmeditor.core.MachineKind
import com.vincegosoftware.fsmeditor.core.RectD
import com.vincegosoftware.fsmeditor.core.Severity
import com.vincegosoftware.fsmeditor.core.SubmachineInfo
import com.vincegosoftware.fsmeditor.core.SvgExport
import com.vincegosoftware.fsmeditor.core.TextAnchor
import com.vincegosoftware.fsmeditor.core.Tools
import com.vincegosoftware.fsmeditor.core.VertexType
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.text.JTextComponent
import kotlin.math.max
import kotlin.math.roundToInt

/** What the diagram editor asks of the document editor hosting it. */
interface EditorHost {
    /** Writes the model to the document (as XMI). */
    fun write(model: FsmModel)
    fun undo()
    fun redo()
    fun generateCode()
    fun exportSvg()
    fun openAsText()
    fun openSubmachine(href: String)
}

/**
 * The whole diagram editor: toolbar, toolbox, canvas, properties panel and
 * status bar. It works on a [DiagramSession] and hands every change to its
 * [EditorHost], which writes the document.
 */
class EditorPanel(private val host: EditorHost) : JPanel(BorderLayout()) {
    val canvas = DiagramCanvas(this)
    private val props = PropertiesPanel(this)
    private val layers = CanvasLayers()
    private val toolbar = JPanel(BorderLayout())
    private val toolbox = WidthTrackingPanel()
    private val status = JPanel(BorderLayout())
    private val toast = Toast()
    private val toastTimer = Timer(2600) { toast.isVisible = false }.apply { isRepeats = false }
    private val parseError = JPanel()
    private val toolRows = LinkedHashMap<String, ToolRow>()
    private val title = JBLabel()
    private val zoomLabel = JBLabel("100%", SwingConstants.CENTER)
    private var issues: List<Issue> = emptyList()
    private var issuesById: Map<String, List<Issue>> = emptyMap()
    private var firstLoad = true
    private var inline: InlineEdit? = null

    var session: DiagramSession? = null
        private set

    /** Copy, cut, paste and delete for the IDE's edit actions. */
    val clipboardHandler = ClipboardHandler()

    init {
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER)
        layers.add(toast, JLayeredPane.POPUP_LAYER)
        layers.add(parseError, JLayeredPane.MODAL_LAYER)
        toast.isVisible = false
        parseError.isVisible = false

        val toolboxScroll = JBScrollPane(toolbox).apply {
            preferredSize = Dimension(JBUI.scale(176), 0)
            border = JBUI.Borders.customLineRight(JBColor.border())
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        val propsScroll = JBScrollPane(props).apply {
            preferredSize = Dimension(JBUI.scale(280), 0)
            border = JBUI.Borders.customLineLeft(JBColor.border())
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(toolbar, BorderLayout.NORTH)
        add(status, BorderLayout.SOUTH)
        add(toolboxScroll, BorderLayout.WEST)
        add(propsScroll, BorderLayout.EAST)
        add(layers, BorderLayout.CENTER)

        canvas.onViewChanged { updateZoomLabel() }
        canvas.onToolChanged {
            updateToolbox()
            renderStatus()
        }
        canvas.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (firstLoad && session != null && canvas.width > 0) {
                    firstLoad = false
                    canvas.fit()
                }
            }
        })
        buildToolbar()
        buildToolbox()
        renderStatus()
    }

    /** Rebuilds the parts that hold theme colors. */
    fun themeChanged() {
        Theme.reset()
        buildToolbox()
        renderProps()
        renderStatus()
        canvas.repaint()
    }

    // ---------------------------------------------------------------- document updates

    /**
     * New state from the document: the model when it changed (null when the
     * document still holds what this editor wrote), the validation results,
     * or the reason the file can't be read.
     */
    fun update(model: FsmModel?, newIssues: List<Issue>, error: String?, submachines: Map<String, SubmachineInfo>?, machines: List<MachineInfo>?) {
        issues = newIssues
        issuesById = newIssues.groupBy { it.id }
        if (error != null) {
            showParseError(error)
            renderStatus()
            return
        }
        parseError.isVisible = false
        if (model != null) {
            val current = session
            if (current == null) {
                val created = DiagramSession(model)
                created.onCommitted { onCommitted() }
                created.onMessage { toast(it) }
                session = created
            } else {
                current.replaceModel(model)
            }
        }
        val s = session ?: return
        if (submachines != null) s.submachines = submachines
        if (machines != null) s.machines = machines
        if (model != null) {
            if (firstLoad && canvas.width > 0) {
                firstLoad = false
                SwingUtilities.invokeLater { canvas.fit() }
            }
            renderProps()
        } else if (!props.hasFocusInside()) {
            renderProps()
        }
        canvas.repaint()
        updateTitle()
        renderStatus()
    }

    private fun onCommitted() {
        val s = session ?: return
        host.write(s.model)
        canvas.repaint()
        updateTitle()
        renderStatus()
    }

    fun issuesFor(id: String): List<Issue> = issuesById[id] ?: emptyList()

    fun renderProps() {
        if (session != null) props.render()
    }

    fun openSubmachine(href: String) = host.openSubmachine(href)

    fun reveal(id: String) {
        val s = session ?: return
        s.selection = if (id.isNotEmpty()) linkedSetOf(id) else LinkedHashSet()
        if (id.isNotEmpty()) canvas.reveal(id)
        renderProps()
        canvas.repaint()
        renderStatus()
    }

    fun exportSvg(): String? = session?.let { SvgExport.export(it) }

    // ---------------------------------------------------------------- editing commands

    val hasSelection: Boolean get() = session?.selection?.isNotEmpty() == true

    fun deleteSelection() {
        val s = session ?: return
        s.deleteSelection()
        renderProps()
    }

    fun selectAll() {
        val s = session ?: return
        s.selection = s.model.vertices.mapTo(LinkedHashSet()) { it.id }
        renderProps()
        canvas.repaint()
        renderStatus()
    }

    fun duplicate() {
        val s = session ?: return
        val data = s.collectSelection() ?: return
        s.resetPasteOffset()
        s.paste(data)
        renderProps()
    }

    fun copy(cut: Boolean) {
        val s = session ?: return
        val data = s.collectSelection() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(ClipboardJson.write(data)))
        s.resetPasteOffset()
        if (cut) deleteSelection()
    }

    /** Pastes copied elements; returns false when the clipboard holds something else. */
    fun paste(): Boolean {
        val s = session ?: return false
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        val data = ClipboardJson.tryRead(text) ?: return false
        s.paste(data)
        renderProps()
        return true
    }

    inner class ClipboardHandler : CopyProvider, CutProvider, PasteProvider, DeleteProvider {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun performCopy(dataContext: DataContext) = copy(false)
        override fun isCopyEnabled(dataContext: DataContext) = hasSelection
        override fun isCopyVisible(dataContext: DataContext) = true
        override fun performCut(dataContext: DataContext) = copy(true)
        override fun isCutEnabled(dataContext: DataContext) = hasSelection
        override fun isCutVisible(dataContext: DataContext) = true
        override fun performPaste(dataContext: DataContext) {
            paste()
        }
        override fun isPastePossible(dataContext: DataContext) = session != null
        override fun isPasteEnabled(dataContext: DataContext) = session != null
        override fun deleteElement(dataContext: DataContext) = deleteSelection()
        override fun canDeleteElement(dataContext: DataContext) = hasSelection
    }

    // ---------------------------------------------------------------- inline editing

    private class InlineEdit(val box: JTextComponent, val component: JComponent, val id: String, val multiline: Boolean) {
        var cancelled = false
    }

    fun startInlineEdit(id: String) {
        commitInlineEdit()
        val s = session ?: return
        s.reindex()
        val ix = s.index
        val v = ix.vertex(id)
        val t = ix.transition(id)
        if (v == null && t == null) return
        if (v != null && (v.type == VertexType.ConnectionPointRef ||
                (v.type.isPseudostate && v.type != VertexType.EntryPoint && v.type != VertexType.ExitPoint &&
                    v.type != VertexType.Choice && v.type != VertexType.Junction && v.type != VertexType.Fork && v.type != VertexType.Join))
        ) {
            // Unnamed markers: edit them in the properties panel instead.
            props.focusFirstField()
            return
        }
        val box: RectD
        val value: String
        var multiline = false
        if (v != null) {
            if (v.type == VertexType.Comment) {
                box = RectD(v.x, v.y, v.w, v.h)
                value = v.text
                multiline = true
            } else if (v.type == VertexType.State) {
                val atTop = v.regions.isNotEmpty() || Labels.activityLines(s.model, v).isNotEmpty()
                val y = if (atTop) v.y + (if (v.stereotype.isNotEmpty()) 14 else 2) else v.y + v.h / 2 - 12 + (if (v.stereotype.isNotEmpty()) 6 else 0)
                box = RectD(v.x + 4, y, v.w - 8, 24.0)
                value = v.name
            } else {
                box = RectD(v.x + v.w / 2 - 60, v.y + v.h + 2, 120.0, 22.0)
                value = v.name
            }
        } else {
            val pts = Geometry.route(ix, t!!) ?: return
            val lp = Geometry.labelPos(t, pts)
            val x = when (lp.anchor) {
                TextAnchor.End -> lp.x - 220
                TextAnchor.Start -> lp.x
                TextAnchor.Middle -> lp.x - 110
            }
            box = RectD(x, lp.y - 16, 220.0, 22.0)
            value = Labels.transitionLabel(s.model, t)
        }
        val th = Theme.current
        val field: JTextComponent = if (multiline) {
            JTextArea(value).apply {
                lineWrap = true
                wrapStyleWord = true
            }
        } else {
            JTextField(value).apply { horizontalAlignment = JTextField.CENTER }
        }
        field.font = JBFont.label().deriveFont((12 * canvas.zoom).toFloat().coerceAtLeast(10f))
        field.background = th.background
        field.foreground = th.foreground
        field.caretColor = th.foreground
        field.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(th.accent), JBUI.Borders.empty(1, 2))
        if (t != null) {
            field.toolTipText = if (s.model.kind == MachineKind.Protocol) "[isReady()] event / [isDone()]" else "event, after(2s) [isReady()] / doIt()"
        }
        val at = canvas.toScreen(box.x, box.y)
        field.setBounds(at.x.roundToInt(), at.y.roundToInt(), max(80.0, box.w * canvas.zoom).roundToInt(), max(22.0, box.h * canvas.zoom).roundToInt())
        layers.add(field, JLayeredPane.PALETTE_LAYER)
        val edit = InlineEdit(field, field, id, multiline)
        inline = edit
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    edit.cancelled = true
                    commitInlineEdit()
                    canvas.requestFocusInWindow()
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_ENTER && (!multiline || e.isControlDown || e.isMetaDown)) {
                    e.consume()
                    // Keep editing when the label breaks the grammar.
                    if (commitInlineEdit(true)) canvas.requestFocusInWindow()
                }
            }
        })
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (inline === edit) commitInlineEdit()
            }
        })
        layers.revalidate()
        layers.repaint()
        SwingUtilities.invokeLater {
            field.requestFocusInWindow()
            field.selectAll()
        }
    }

    /** Applies the inline editor. Returns false when it stays open because of an invalid label. */
    fun commitInlineEdit(fromKey: Boolean = false): Boolean {
        val edit = inline ?: return true
        val text = edit.box.text
        val s = session
        val ix = s?.index
        val v = ix?.vertex(edit.id)
        val t = ix?.transition(edit.id)
        if (s != null && t != null && !edit.cancelled && Labels.transitionLabel(s.model, t) != text.trim()) {
            val err = Labels.applyLabel(s.model, t.copy(), text, ix.vertex(t.source))
            if (err != null && fromKey) {
                edit.box.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.current.error), JBUI.Borders.empty(1, 2))
                edit.box.toolTipText = err
                toast(err)
                return false
            }
            closeInline()
            if (err != null) {
                toast("Label not changed. $err")
            } else {
                s.applyLabel(t, text)
                renderProps()
            }
            return true
        }
        closeInline()
        if (edit.cancelled || s == null || v == null) return true
        if (v.type == VertexType.Comment) {
            val normalized = text.replace("\r\n", "\n")
            if (v.text == normalized) return true
            v.text = normalized
        } else {
            if (v.name == text.trim()) return true
            v.name = text.trim()
        }
        s.commit()
        renderProps()
        return true
    }

    private fun closeInline() {
        val edit = inline ?: return
        inline = null
        layers.remove(edit.component)
        layers.repaint()
    }

    // ---------------------------------------------------------------- chrome

    private fun action(text: String, icon: javax.swing.Icon, run: () -> Unit, showText: Boolean = false): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                if (showText) e.presentation.putClientProperty(com.intellij.openapi.actionSystem.ex.ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }
        }

    private inner class ZoomLabelAction : AnAction(), CustomComponentAction {
        override fun actionPerformed(e: AnActionEvent) {}
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent = zoomLabel.apply {
            preferredSize = Dimension(JBUI.scale(48), JBUI.scale(22))
        }
    }

    private fun buildToolbar() {
        val mod = if (SystemInfo.isMac) "⌘" else "Ctrl+"
        val left = DefaultActionGroup().apply {
            add(action("Undo (${mod}Z)", AllIcons.Actions.Undo, host::undo))
            add(action("Redo", AllIcons.Actions.Redo, host::redo))
            addSeparator()
            add(action("Zoom Out (${mod}-)", AllIcons.General.ZoomOut, { canvas.zoomCenter(1 / 1.2) }))
            add(ZoomLabelAction())
            add(action("Zoom In (${mod}=)", AllIcons.General.ZoomIn, { canvas.zoomCenter(1.2) }))
            add(action("Fit Diagram (F)", AllIcons.General.FitContent, canvas::fit))
            addSeparator()
            add(action("Delete Selection (Del)", AllIcons.Actions.GC, ::deleteSelection))
        }
        val right = DefaultActionGroup().apply {
            add(action("Code", AllIcons.Actions.Execute, host::generateCode, true).also { it.templatePresentation.description = "Generate code from a template" })
            add(action("SVG", AllIcons.ToolbarDecorator.Export, host::exportSvg, true).also { it.templatePresentation.description = "Export as SVG" })
            addSeparator()
            add(action("XMI", AllIcons.FileTypes.Xml, host::openAsText, true).also { it.templatePresentation.description = "Open as XMI text" })
        }
        val manager = ActionManager.getInstance()
        val leftBar = manager.createActionToolbar("FsmEditor.Left", left, true).apply { targetComponent = canvas }
        val rightBar = manager.createActionToolbar("FsmEditor.Right", right, true).apply { targetComponent = canvas }
        title.font = JBFont.label().asBold()
        title.border = JBUI.Borders.empty(0, 8, 0, 4)
        val west = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(title)
            add(leftBar.component)
        }
        toolbar.removeAll()
        toolbar.border = JBUI.Borders.customLineBottom(JBColor.border())
        toolbar.add(west, BorderLayout.WEST)
        toolbar.add(rightBar.component, BorderLayout.EAST)
        updateTitle()
        updateZoomLabel()
    }

    private fun updateTitle() {
        val m = session?.model ?: return
        title.text = (if (m.kind == MachineKind.Protocol) "{protocol} " else "") + m.name.ifEmpty { "State machine" }
    }

    private fun updateZoomLabel() {
        zoomLabel.text = "${(canvas.zoom * 100).roundToInt()}%"
    }

    /** A toolbox item: icon, label and shortcut; click arms the tool, dragging drops it on the canvas. */
    private inner class ToolRow(val toolId: String, label: String, key: Char?, isMode: Boolean) : JPanel(BorderLayout()) {
        var hover = false

        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6)
            val icon = JBLabel(label, ToolIcons.get(toolId), SwingConstants.LEFT).apply { iconTextGap = JBUI.scale(8) }
            add(icon, BorderLayout.CENTER)
            if (key != null) add(JBLabel(key.uppercaseChar().toString()).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }, BorderLayout.EAST)
            toolTipText = label + (if (key != null) " (${key.uppercaseChar()})" else "") + if (isMode) "" else " — click then click the canvas, or drag onto it"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = Component.LEFT_ALIGNMENT
            val mouse = object : MouseAdapter() {
                private var dragging = false

                override fun mousePressed(e: MouseEvent) {
                    dragging = false
                }

                override fun mouseEntered(e: MouseEvent) {
                    hover = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    canvas.setTool(toolId, e.isShiftDown)
                    canvas.requestFocusInWindow()
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (isMode || dragging) return
                    dragging = true
                    transferHandler.exportAsDrag(this@ToolRow, e, TransferHandler.COPY)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            if (!isMode) {
                transferHandler = object : TransferHandler() {
                    override fun getSourceActions(c: JComponent) = COPY
                    override fun createTransferable(c: JComponent) = StringSelection(DiagramCanvas.TOOL_PREFIX + toolId)
                }
            }
        }

        override fun paintComponent(g: Graphics) {
            val active = canvas.tool == toolId
            if (active || hover) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val th = Theme.current
                g2.color = if (active) th.alpha(th.accent, 0.2) else JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                if (active) {
                    g2.color = th.accent
                    g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                }
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private fun buildToolbox() {
        toolbox.removeAll()
        toolbox.layout = BoxLayout(toolbox, BoxLayout.Y_AXIS)
        toolbox.border = JBUI.Borders.empty(4, 6, 16, 6)
        toolRows.clear()
        for (group in Tools.groups) {
            toolbox.add(JBLabel(group.title.uppercase()).apply {
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(10, 4, 4, 4)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            for (tool in group.items) {
                val row = ToolRow(tool.id, tool.label, tool.key, tool.isMode)
                toolRows[tool.id] = row
                toolbox.add(row)
            }
        }
        toolbox.add(wrappingText("Shift+click a tool to keep it active. Esc returns to selection.", small = true).apply {
            border = JBUI.Borders.empty(12, 4, 0, 4)
        })
        toolbox.revalidate()
        toolbox.repaint()
    }

    private fun updateToolbox() {
        toolRows.values.forEach { it.repaint() }
    }

    fun renderStatus() {
        val th = Theme.current
        val errors = issues.count { it.severity == Severity.Error }
        val warnings = issues.count { it.severity == Severity.Warning }
        val infos = issues.size - errors - warnings
        val problems = com.intellij.ui.SimpleColoredComponent().apply {
            append("✕ $errors", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (errors > 0) th.error else th.muted))
            append("   ")
            append("⚠ $warnings", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (warnings > 0) th.warning else th.muted))
            if (infos > 0) append("   ℹ $infos", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, th.muted))
            toolTipText = "Show problems"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
            ipad = JBUI.insetsRight(16)
        }
        problems.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showProblems(problems)
        })
        val s = session
        val counts = JBLabel(if (s == null) "" else "${s.model.vertices.count { it.type == VertexType.State }} states · ${s.model.transitions.size} transitions").apply {
            foreground = th.muted
        }
        val toolName = canvas.tool?.let { Tools.byId(it)?.label }
        val right = JBLabel(
            when {
                toolName != null -> "Tool: $toolName${if (canvas.toolSticky) " (sticky)" else ""}"
                s != null && s.selection.isNotEmpty() -> "${s.selection.size} selected"
                else -> ""
            },
        ).apply { foreground = th.muted }
        val west = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(problems)
            add(counts)
        }
        status.removeAll()
        status.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(2, 10))
        status.add(west, BorderLayout.WEST)
        status.add(right, BorderLayout.EAST)
        status.revalidate()
        status.repaint()
    }

    private fun showProblems(anchor: JComponent) {
        val th = Theme.current
        if (issues.isEmpty()) {
            JBPopupFactory.getInstance().createMessage("No problems. The state machine is well-formed.")
                .showInScreenCoordinates(anchor, abovePoint(anchor))
            return
        }
        val sorted = issues.sortedBy { it.severity.ordinal }
        JBPopupFactory.getInstance().createPopupChooserBuilder(sorted)
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<Issue>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out Issue>, value: Issue, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(severityGlyph(value.severity) + "  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, severityColor(th, value.severity)))
                    append(value.message)
                }
            })
            .setTitle("Problems")
            .setItemChosenCallback { reveal(it.id) }
            .createPopup()
            .showInScreenCoordinates(anchor, abovePoint(anchor))
    }

    private fun abovePoint(anchor: JComponent): Point {
        val p = anchor.locationOnScreen
        return Point(p.x, p.y - JBUI.scale(8) - minOf(issues.size.coerceAtLeast(1), 12) * JBUI.scale(22) - JBUI.scale(30))
    }

    fun toast(message: String) {
        toast.text = message
        toast.isVisible = true
        layers.revalidate()
        layers.doLayout()
        layers.repaint()
        toastTimer.restart()
    }

    private fun showParseError(error: String) {
        parseError.removeAll()
        parseError.layout = BoxLayout(parseError, BoxLayout.Y_AXIS)
        parseError.background = Theme.current.background
        parseError.isOpaque = true
        parseError.border = JBUI.Borders.empty(24)
        parseError.add(Box.createVerticalGlue())
        parseError.add(centered(wrappingText("This file cannot be displayed because it is not a readable UML state machine (XMI):")))
        parseError.add(Box.createVerticalStrut(JBUI.scale(10)))
        parseError.add(centered(wrappingText(error).apply {
            foreground = Theme.current.error
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        }))
        parseError.add(Box.createVerticalStrut(JBUI.scale(10)))
        parseError.add(JButton("Open as Text").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener { host.openAsText() }
        })
        parseError.add(Box.createVerticalGlue())
        parseError.isVisible = true
        layers.revalidate()
        layers.repaint()
    }

    private fun centered(c: JComponent) = c.apply {
        alignmentX = Component.CENTER_ALIGNMENT
        maximumSize = Dimension(JBUI.scale(560), Int.MAX_VALUE)
    }

    /** Holds the canvas with the inline editor, the toast and the parse error view above it. */
    private inner class CanvasLayers : JLayeredPane() {
        override fun doLayout() {
            canvas.setBounds(0, 0, width, height)
            parseError.setBounds(0, 0, width, height)
            if (toast.isVisible) {
                val size = toast.preferredSize
                val w = minOf(size.width, width - JBUI.scale(24))
                toast.setBounds((width - w) / 2, JBUI.scale(12), w, size.height)
            }
        }
    }

    /** A panel as wide as the scroll pane showing it, so its text wraps. */
    open class WidthTrackingPanel : JPanel(), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    /** A short message over the top of the canvas. */
    private class Toast : JBLabel() {
        init {
            foreground = Color.WHITE
            border = JBUI.Borders.empty(6, 12)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val th = Theme.current
            g2.color = if (th.isDark) Theme.mix(th.background, Color.WHITE, 0.15) else Color(0x33, 0x33, 0x33)
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            g2.dispose()
            super.paintComponent(g)
        }
    }

    companion object {
        fun severityGlyph(s: Severity) = when (s) {
            Severity.Error -> "✕"
            Severity.Warning -> "⚠"
            Severity.Info -> "ℹ"
        }

        fun severityColor(th: Theme, s: Severity) = when (s) {
            Severity.Error -> th.error
            Severity.Warning -> th.warning
            Severity.Info -> th.info
        }

        /** Read-only text of [runs] (text, bold, color or null) that wraps to the width of its container. */
        fun richText(vararg runs: Triple<String, Boolean, Color?>): javax.swing.JTextPane = javax.swing.JTextPane().apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBFont.label()
            alignmentX = Component.LEFT_ALIGNMENT
            for ((text, bold, color) in runs) {
                val style = javax.swing.text.SimpleAttributeSet()
                javax.swing.text.StyleConstants.setFontFamily(style, JBFont.label().family)
                javax.swing.text.StyleConstants.setFontSize(style, JBFont.label().size)
                javax.swing.text.StyleConstants.setBold(style, bold)
                javax.swing.text.StyleConstants.setForeground(style, color ?: UIUtil.getLabelForeground())
                styledDocument.insertString(styledDocument.length, text, style)
            }
        }

        /** Read-only text that wraps to the width of its container. */
        fun wrappingText(text: String, small: Boolean = false): JTextArea = JTextArea(text).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            font = if (small) JBFont.small() else JBFont.label()
            foreground = if (small) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }
}
