package com.vincegosoftware.fsmeditor.trace

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vincegosoftware.fsmeditor.core.TraceKind
import com.vincegosoftware.fsmeditor.core.TraceSession
import com.vincegosoftware.fsmeditor.editor.DiagramCanvas
import com.vincegosoftware.fsmeditor.editor.ToolIcons
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** The Trace Player tool window (bottom). */
class TracePlayerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TracePlayerPanel(TracePlayerService.getInstance(project))
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * The tool window's content, as media/tracePlayer.js of the VS Code edition: controls, timeline, open logs, problems,
 * and the records around the cursor. The slider is kept across renders, so dragging it isn't interrupted.
 */
class TracePlayerPanel(private val player: TracePlayerService) : JPanel(CardLayout()), com.intellij.openapi.Disposable {
    private val slider = JSlider(-1, 0, -1)
    private val bar = JPanel(WrapLayout())
    private val traces = JPanel(WrapLayout())
    private val problems = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val rows = JPanel(GridBagLayout())
    private val records = ScrollPaneFactory.createScrollPane(rows, true)
    private val emptyCard = JPanel(BorderLayout())
    private var updating = false

    init {
        slider.addChangeListener { if (!updating) player.seek(slider.value) }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for (c in listOf(bar, slider, traces, problems)) {
                (c as JComponent).alignmentX = Component.LEFT_ALIGNMENT
                add(c)
            }
        }
        records.border = JBUI.Borders.customLineTop(JBColor.border())
        val main = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(top, BorderLayout.NORTH)
            add(records, BorderLayout.CENTER)
        }
        emptyCard.border = JBUI.Borders.empty(8)
        val intro = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("<html>Replay a log of your state machines on their diagrams: the active states, the transitions taken and the branches chosen at choices and junctions.</html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(javax.swing.Box.createVerticalStrut(10))
            add(button("Open Log…", "Open one or more log files") { player.open() }.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                putClientProperty("JButton.buttonType", null)
                isDefaultCapable = true
            })
        }
        emptyCard.add(intro, BorderLayout.NORTH)
        add(emptyCard, EMPTY)
        add(main, MAIN)
        player.onChanged(this) { render() }
        render()
    }

    override fun dispose() {}

    private fun button(label: String, tip: String, enabled: Boolean = true, action: () -> Unit) = JButton(label).apply {
        toolTipText = tip
        isEnabled = enabled
        margin = JBUI.insets(1, 6)
        isFocusable = false
        addActionListener { action() }
    }

    /** A button showing the editor's icon [icon]. */
    private fun iconButton(icon: String, tip: String, enabled: Boolean = true, action: () -> Unit) = button("", tip, enabled, action).apply {
        this.icon = ToolIcons.get(icon, 16)
        margin = JBUI.insets(1, 4)
    }

    private fun render() {
        val s = player.session
        val cards = layout as CardLayout
        if (s.traces.isEmpty()) {
            cards.show(this, EMPTY)
            return
        }
        cards.show(this, MAIN)
        val none = s.entries.isEmpty()
        val atEnd = s.cursor >= s.entries.size - 1
        bar.removeAll()
        bar.add(button("Open Log…", "Open more log files") { player.open() })
        bar.add(javax.swing.Box.createHorizontalStrut(8))
        bar.add(iconButton("first", "First record", !none) { player.first() })
        bar.add(iconButton("stepBack", "Previous record", s.cursor >= 0) { player.step(-1) })
        bar.add(
            if (s.playing) button("⏸", "Pause") { player.pause() }
            else button("▶", "Play from here (from the start at the end)", !none) { player.play() },
        )
        bar.add(iconButton("stepOver", "Next record", !atEnd) { player.step(1) })
        bar.add(button("● Live", "Follow the last record as the files grow") { player.goLive() }.apply {
            if (s.live) {
                foreground = Color(0xe5, 0x14, 0x00)
                font = font.deriveFont(Font.BOLD)
            }
        })
        val speed = ComboBox(SPEEDS.map { "${Num.short(it)}×" }.toTypedArray()).apply {
            toolTipText = "Playing speed"
            selectedIndex = SPEEDS.indexOf(s.speed).coerceAtLeast(0)
            addActionListener { player.setSpeed(SPEEDS[selectedIndex.coerceAtLeast(0)]) }
        }
        bar.add(speed)
        bar.add(JBLabel(if (none) "No records yet" else "${s.cursor + 1} / ${s.entries.size}${if (s.cursorTime.isNotEmpty()) " · ${s.cursorTime}" else ""}").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(8)
        })

        updating = true
        slider.maximum = maxOf(s.entries.size - 1, 0)
        if (!slider.valueIsAdjusting) slider.value = s.cursor
        slider.isEnabled = !none
        updating = false

        traces.removeAll()
        s.traces.forEachIndexed { i, t ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                toolTipText = t.path
                border = JBUI.Borders.emptyRight(12)
            }
            row.add(Dot(swatch(i)))
            row.add(JBLabel(t.name).apply { font = JBFont.label().asBold() })
            row.add(ComboBox(s.formatList.map { it.name }.toTypedArray()).apply {
                toolTipText = "Format of the lines"
                selectedItem = t.format.name
                addActionListener { (selectedItem as? String)?.let { if (it != t.format.name) player.setFormat(t, it) } }
            })
            if (t.needsMachine) {
                row.add(button(t.machineName.ifEmpty { "Choose machine…" }, "State machine of the lines that name none: click to choose it") { player.askMachine(t) }.apply {
                    if (t.machineName.isEmpty()) font = font.deriveFont(Font.BOLD)
                })
            }
            row.add(JBLabel(t.error ?: "${t.records.size} record${if (t.records.size == 1) "" else "s"}").apply {
                foreground = if (t.error != null) JBColor.RED else UIUtil.getContextHelpForeground()
            })
            row.add(button("×", "Close this log") { player.close(t) }.apply {
                isBorderPainted = false
                isContentAreaFilled = false
            })
            traces.add(row)
        }

        problems.removeAll()
        for (p in s.problems) {
            problems.add(JBLabel("⚠ $p").apply {
                foreground = Color(0xbf, 0x88, 0x03)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        renderRows(s)
        revalidate()
        repaint()
    }

    private fun renderRows(s: TraceSession) {
        rows.removeAll()
        val mono = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, 12))
        var current: JComponent? = null
        var y = 0
        for (r in s.rows()) {
            val isCurrent = r.index == s.cursor
            val future = r.index > s.cursor
            fun label(text: String, color: Color? = null, bold: Boolean = false) = JBLabel(text).apply {
                font = if (bold) mono.deriveFont(Font.BOLD) else mono
                if (color != null) foreground = color
                if (future) foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(1, 0, 1, 8)
            }
            val what = label(r.name).apply {
                if (!r.resolved) {
                    font = font.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
                    foreground = UIUtil.getContextHelpForeground()
                }
            }
            val machine = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                if (r.color >= 0) add(label("● ", DiagramCanvas.traceColor(r.color)))
                add(label(r.machine))
            }
            val row = listOf(
                label(r.time, UIUtil.getContextHelpForeground()),
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                    isOpaque = false
                    add(Dot(swatch(r.trace)))
                    border = JBUI.Borders.emptyRight(8)
                },
                machine,
                label(r.kind.text, KIND_COLORS[r.kind] ?: UIUtil.getContextHelpForeground(), bold = true),
                what,
            )
            val index = r.index
            row.forEachIndexed { col, c ->
                c.isOpaque = isCurrent
                if (isCurrent) c.background = UIUtil.getListSelectionBackground(false)
                c.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                c.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = player.seek(index)
                })
                rows.add(c, GridBagConstraints().apply {
                    gridx = col
                    gridy = y
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.BOTH
                    weightx = if (col == 4) 1.0 else 0.0
                })
            }
            if (isCurrent) current = row[0]
            y++
        }
        rows.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridy = y
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })
        rows.revalidate()
        val cur = current
        if (cur != null && !slider.valueIsAdjusting) {
            SwingUtilities.invokeLater {
                val b = cur.bounds
                rows.scrollRectToVisible(java.awt.Rectangle(0, b.y, 1, b.height))
            }
        }
    }

    /** A small colored dot. */
    private class Dot(private val color: Color) : JComponent() {
        init {
            preferredSize = Dimension(JBUI.scale(8), JBUI.scale(8))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(0, (height - width) / 2, width, width)
        }
    }

    companion object {
        private const val EMPTY = "empty"
        private const val MAIN = "main"
        private val SPEEDS = listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0)

        private val KIND_COLORS = mapOf(
            TraceKind.Event to Color(0x1a, 0x85, 0xff),
            TraceKind.Enter to Color(0x38, 0x8a, 0x34),
            TraceKind.Transition to Color(0xd1, 0x86, 0x16),
        )

        /** One color per log, the same in the list of logs and in the records (as the VS Code edition's). */
        fun swatch(i: Int): Color = Color.getHSBColor(((i * 67 + 200) % 360) / 360f, 0.6f, 0.75f)
    }
}

/** Number formatting for the speeds: `0.25`, `1`. */
private object Num {
    fun short(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

/** A FlowLayout that wraps its components onto more lines, giving its container the height they need. */
private class WrapLayout : FlowLayout(LEFT, 4, 2) {
    override fun preferredLayoutSize(target: java.awt.Container): Dimension = size(target, true)

    override fun minimumLayoutSize(target: java.awt.Container): Dimension = size(target, false).apply { width -= hgap + 1 }

    private fun size(target: java.awt.Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var width = target.width
            if (width == 0) width = Int.MAX_VALUE
            val insets = target.insets
            val max = width - (insets.left + insets.right + hgap * 2)
            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (c in target.components) {
                if (!c.isVisible) continue
                val d = if (preferred) c.preferredSize else c.minimumSize
                if (rowWidth + d.width > max && rowWidth > 0) {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            dim.width = maxOf(dim.width, rowWidth)
            dim.height += rowHeight + insets.top + insets.bottom + vgap * 2
            dim.width += insets.left + insets.right + hgap * 2
            return dim
        }
    }
}

/** Asks which machine the lines of a log naming none are about. */
class MachinePicker(
    project: Project,
    title: String,
    private val prompt: String,
    private val machines: List<Triple<String, String, Boolean>>,
    private val selected: Int,
) : DialogWrapper(project) {
    private val list = JBList(machines.indices.toList())

    init {
        this.title = title
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<Int>() {
            override fun customizeCellRenderer(list: JList<out Int>, value: Int, index: Int, selected: Boolean, hasFocus: Boolean) {
                val (name, where, open) = machines[value]
                append(name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  $where", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (open) append("  (open)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.selectedIndex = if (selected >= 0) selected else 0
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedIndex >= 0) doOKAction()
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        add(JBLabel(prompt), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(440), JBUI.scale(240)) }, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    /** The index of the chosen machine, or -1 when cancelled. */
    fun choose(): Int = if (showAndGet()) list.selectedIndex else -1
}
