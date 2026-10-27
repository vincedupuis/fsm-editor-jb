package com.vincegosoftware.fsmeditor.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/** View modes of a [ToolPane], named as for a JetBrains tool window. */
enum class PaneMode(val label: String) {
    DockPinned("Dock Pinned"),
    DockUnpinned("Dock Unpinned"),
    Undock("Undock"),
    Float("Float"),
    ;

    /** The pane hides when the diagram is clicked. */
    val autoHides get() = this == DockUnpinned || this == Undock
}

/**
 * The toolbox or the properties panel, shown like a JetBrains tool window: a
 * header with Options (View Mode, Hide) and Hide. [EditorPanel] places it
 * beside the diagram or over it, and its toolbar shows and hides it.
 */
class ToolPane(val title: String, val icon: Icon, content: JComponent, private val changed: (ToolPane) -> Unit) : JPanel(BorderLayout()) {
    var mode = PaneMode.DockPinned
        private set
    var shown = true
        private set

    /** Top-left corner of a floating pane over the diagram, once moved. */
    var floatAt: Point? = null

    private val header = JPanel(BorderLayout())
    private var dragFrom: Point? = null

    init {
        background = JBColor.PanelBackground
        header.isOpaque = false
        header.preferredSize = Dimension(0, JBUI.scale(32))
        header.add(JBLabel(title).apply {
            font = JBFont.label().asBold()
            border = JBUI.Borders.emptyLeft(10)
        }, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), JBUI.scale(5))).apply { isOpaque = false }
        buttons.add(PaneButton(AllIcons.Actions.More, "Options", 22, ::showOptions))
        buttons.add(PaneButton(AllIcons.General.HideToolWindow, "Hide", 22) { change(mode, false) })
        header.add(buttons, BorderLayout.EAST)
        val drag = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (mode == PaneMode.Float) dragFrom = e.point
            }

            override fun mouseDragged(e: MouseEvent) {
                val from = dragFrom ?: return
                val layer = parent ?: return
                val p = javax.swing.SwingUtilities.convertPoint(header, e.point, layer)
                val x = (p.x - from.x).coerceIn(0, maxOf(0, layer.width - width))
                val y = (p.y - from.y).coerceIn(0, maxOf(0, layer.height - JBUI.scale(40)))
                floatAt = Point(x, y)
                setLocation(x, y)
            }

            override fun mouseReleased(e: MouseEvent) {
                dragFrom = null
            }
        }
        header.addMouseListener(drag)
        header.addMouseMotionListener(drag)
        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    /** Sets the mode and visibility without telling the editor (when it loads the saved layout). */
    fun apply(mode: PaneMode, shown: Boolean) {
        this.mode = mode
        this.shown = shown
        header.cursor = if (mode == PaneMode.Float) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) else null
    }

    fun change(mode: PaneMode, shown: Boolean) {
        apply(mode, shown)
        changed(this)
    }

    private fun showOptions(anchor: PaneButton) {
        val viewMode = DefaultActionGroup("View Mode", true)
        for (m in PaneMode.entries) {
            viewMode.add(object : DumbAwareToggleAction(m.label) {
                override fun isSelected(e: AnActionEvent) = mode == m
                override fun setSelected(e: AnActionEvent, state: Boolean) = change(m, true)
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        val group = DefaultActionGroup().apply {
            add(viewMode)
            addSeparator()
            add(object : DumbAwareAction("Hide") {
                override fun actionPerformed(e: AnActionEvent) = change(mode, false)
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(anchor), JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
            .showUnderneathOf(anchor)
    }
}

/** A small icon button of a tool window header. */
class PaneButton(private val icon: Icon, tooltip: String, size: Int, private val onClick: (PaneButton) -> Unit) : JComponent() {
    private var hover = false

    init {
        toolTipText = tooltip
        preferredSize = JBUI.size(size)
        minimumSize = preferredSize
        maximumSize = preferredSize
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (contains(e.point)) onClick(this@PaneButton)
            }
        }
        addMouseListener(mouse)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (hover) {
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                val arc = JBUI.scale(6)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            }
            icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}
