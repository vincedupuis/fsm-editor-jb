package com.vincegosoftware.fsmeditor.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vincegosoftware.fsmeditor.core.Check
import com.vincegosoftware.fsmeditor.core.ConnectionPointInfo
import com.vincegosoftware.fsmeditor.core.DiagramSession
import com.vincegosoftware.fsmeditor.core.Expressions
import com.vincegosoftware.fsmeditor.core.Labels
import com.vincegosoftware.fsmeditor.core.MachineKind
import com.vincegosoftware.fsmeditor.core.PointKind
import com.vincegosoftware.fsmeditor.core.RegionLayout
import com.vincegosoftware.fsmeditor.core.Tools
import com.vincegosoftware.fsmeditor.core.Transition
import com.vincegosoftware.fsmeditor.core.TransitionKind
import com.vincegosoftware.fsmeditor.core.Vertex
import com.vincegosoftware.fsmeditor.core.VertexType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.math.roundToInt

/**
 * The properties of the selection (or of the machine when nothing is
 * selected). Text is checked against the grammar as it is committed:
 * invalid text is refused and stays in the field, marked, until fixed.
 */
class PropertiesPanel(private val editor: EditorPanel) : EditorPanel.WidthTrackingPanel() {
    private var rendering = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 12, 24, 12)
    }

    private val s: DiagramSession? get() = editor.session
    private val th: Theme get() = Theme.current

    fun hasFocusInside(): Boolean {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(owner, this)
    }

    fun focusFirstField() {
        findFirst(this)?.requestFocusInWindow()
    }

    private fun findFirst(root: Container): JComponent? {
        for (child in root.components) {
            if (child is JTextComponent && child.isEditable || child is ComboBox<*>) return child as JComponent
            if (child is Container) findFirst(child)?.let { return it }
        }
        return null
    }

    fun render() {
        // Clearing the panel commits a field that had the focus, which may ask for another render.
        if (rendering) return
        rendering = true
        try {
            build()
        } finally {
            rendering = false
        }
        revalidate()
        repaint()
    }

    private fun build() {
        removeAll()
        val s = s ?: return
        s.reindex()
        val ids = s.selection.toList()
        if (ids.size > 1) {
            put(h3("${ids.size} elements selected"))
            put(button("Delete", editor::deleteSelection))
            put(muted("Drag to move them together. Ctrl+D duplicates, Ctrl+C and Ctrl+V copy between diagrams."))
            return
        }
        val v = if (ids.size == 1) s.index.vertex(ids[0]) else null
        if (v != null) {
            vertexProps(s, v)
            return
        }
        val t = if (ids.size == 1) s.index.transition(ids[0]) else null
        if (t != null) {
            transitionProps(s, t)
            return
        }
        machineProps(s)
    }

    private fun put(vararg components: JComponent) {
        for (c in components) {
            c.alignmentX = Component.LEFT_ALIGNMENT
            super.add(c)
        }
    }

    private fun commit(props: Boolean = false) {
        s?.commit()
        if (props) render()
    }

    // ---------------------------------------------------------------- building blocks

    private fun h3(text: String) = EditorPanel.wrappingText(text).apply {
        font = JBFont.label().biggerOn(1f).asBold()
        border = JBUI.Borders.empty(4, 0, 10, 0)
    }

    private fun h4(text: String) = JBLabel(text.uppercase()).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(16, 0, 6, 0)
    }

    private fun muted(text: String) = EditorPanel.wrappingText(text, small = true).apply {
        border = JBUI.Borders.emptyBottom(8)
    }

    private fun button(text: String, onClick: () -> Unit, tooltip: String? = null): JComponent {
        val b = JButton(text)
        b.toolTipText = tooltip
        b.addActionListener { onClick() }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(2))).apply {
            isOpaque = false
            put(b)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun iconButton(tooltip: String, onClick: () -> Unit) =
        InplaceButton(tooltip, AllIcons.Actions.Close) { onClick() }.apply { border = JBUI.Borders.emptyLeft(4) }

    private fun smallButton(text: String, tooltip: String, onClick: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip
        putClientProperty("ActionToolbar.smallVariant", true)
        addActionListener { onClick() }
    }

    private fun field(label: String?, control: JComponent, error: JComponent? = null): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyBottom(8)
        if (label != null) {
            panel.add(JBLabel(label).apply {
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(2)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        control.alignmentX = Component.LEFT_ALIGNMENT
        control.maximumSize = Dimension(Int.MAX_VALUE, control.preferredSize.height)
        panel.add(control)
        if (error != null) {
            error.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(error)
        }
        return panel
    }

    private fun input(value: String, multiline: Boolean = false, rows: Int = 3, placeholder: String? = null): JTextComponent =
        if (multiline) {
            JBTextArea(value, rows, 20).apply {
                lineWrap = true
                wrapStyleWord = true
                font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.label().size))
                border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(2, 4))
                if (placeholder != null) emptyText.text = placeholder
            }
        } else {
            JBTextField(value).apply { if (placeholder != null) emptyText.text = placeholder }
        }

    private fun errorLabel() = EditorPanel.wrappingText("", small = true).apply {
        foreground = th.error
        border = JBUI.Borders.emptyTop(2)
        isVisible = false
    }

    private fun markInvalid(tb: JTextComponent, invalid: Boolean) {
        tb.putClientProperty("JComponent.outline", if (invalid) "error" else null)
        tb.repaint()
    }

    /** Applies [apply] when [tb] loses the focus, or on Enter for a single-line field. */
    private fun onCommit(tb: JTextComponent, multiline: Boolean, apply: () -> Unit) {
        tb.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = apply()
        })
        if (!multiline) {
            tb.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode != KeyEvent.VK_ENTER) return
                    apply()
                    e.consume()
                }
            })
        }
    }

    /**
     * A text field applied when it loses focus or on Enter. With [check], text that breaks
     * the grammar is refused and kept in the field so it can be fixed.
     */
    private fun <T> textField(
        label: String, value: String, onChange: (T) -> Unit, check: (String) -> Check<T>, display: (T) -> String,
        placeholder: String? = null, multiline: Boolean = false, rows: Int = 3,
    ): JComponent {
        val tb = input(value, multiline, rows, placeholder)
        val error = errorLabel()
        var committed = tb.text
        onCommit(tb, multiline) {
            if (tb.text == committed) return@onCommit
            val r = check(tb.text)
            if (!r.ok) {
                markInvalid(tb, true)
                error.text = r.error
                error.isVisible = true
                error.revalidate()
                return@onCommit
            }
            markInvalid(tb, false)
            error.isVisible = false
            committed = display(r.value)
            if (tb.text != committed) tb.text = committed
            onChange(r.value)
        }
        return field(label, tb, error)
    }

    private fun textField(
        label: String, value: String, onChange: (String) -> Unit, check: ((String) -> Check<String>)? = null,
        placeholder: String? = null, multiline: Boolean = false, rows: Int = 3,
    ): JComponent = textField(label, value, onChange, check ?: { Check.success(if (multiline) it.replace("\r\n", "\n") else it) }, { it }, placeholder, multiline, rows)

    private fun listField(label: String, value: List<String>, onChange: (List<String>) -> Unit, itemCheck: (String) -> Check<String>, placeholder: String) =
        textField(label, value.joinToString(", "), onChange, { Expressions.checkList(it, itemCheck) }, { it.joinToString(", ") }, placeholder)

    private class Option(val value: String, val label: String)

    private fun selectField(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit): JComponent {
        val items = options.map { Option(it.first, it.second) }
        val combo = ComboBox(items.toTypedArray())
        combo.renderer = object : SimpleListCellRenderer<Option>() {
            override fun customize(list: javax.swing.JList<out Option>, value: Option?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.label ?: ""
            }
        }
        combo.selectedItem = items.firstOrNull { it.value == value }
        combo.addActionListener {
            val item = combo.selectedItem as? Option ?: return@addActionListener
            if (item.value != value) onChange(item.value)
        }
        return field(label, combo)
    }

    private fun issuesFor(id: String): JComponent? {
        val list = editor.issuesFor(id)
        if (list.isEmpty()) return null
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.add(h4("Problems").apply { alignmentX = Component.LEFT_ALIGNMENT })
        for (i in list) {
            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            row.add(JBLabel(EditorPanel.severityGlyph(i.severity) + "  ").apply {
                foreground = EditorPanel.severityColor(th, i.severity)
                verticalAlignment = JBLabel.TOP
            }, BorderLayout.WEST)
            row.add(EditorPanel.wrappingText(i.message), BorderLayout.CENTER)
            panel.add(row)
        }
        return panel
    }

    private fun row(main: JComponent, vararg trailing: JComponent): JComponent {
        val row = JPanel(BorderLayout())
        row.isOpaque = false
        row.border = JBUI.Borders.emptyBottom(8)
        row.add(main, BorderLayout.CENTER)
        if (trailing.isNotEmpty()) {
            val east = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply { isOpaque = false }
            trailing.forEach { east.add(it) }
            row.add(east, BorderLayout.EAST)
        }
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    // ---------------------------------------------------------------- machine

    private fun machineProps(s: DiagramSession) {
        val m = s.model
        put(
            h3("State Machine"),
            textField("Name", m.name, { m.name = it; commit() }),
            selectField(
                "Kind", if (m.kind == MachineKind.Protocol) "protocol" else "behavioral",
                listOf("behavioral" to "Behavioral state machine", "protocol" to "Protocol state machine"),
            ) {
                m.kind = if (it == "protocol") MachineKind.Protocol else MachineKind.Behavioral
                commit(true)
            },
            textField("Context (owning classifier)", m.context, { m.context = it; commit() }, placeholder = "e.g. MediaPlayer"),
            textField("Documentation", m.documentation, { m.documentation = it; commit() }, multiline = true, rows = 4),
        )
        issuesFor("")?.let { put(it) }
        put(h4("Tips"))
        val tips = listOf(
            "Double-click" to "empty canvas to add a state; a name or label to edit it.",
            "Drag" to "the ⊕ handle of a selected element to draw a transition.",
            "Double-click" to "a transition to add a bend point, a bend point to remove it.",
            "Drop" to "an element into a region to nest it.",
            "Right-drag" to "Space+drag or middle-drag pans; the wheel zooms; F fits.",
            "Shift+click" to "a tool keeps it active.",
            "Label syntax" to if (m.kind == MachineKind.Protocol) "[isReady()] event / [isDone()]" else "event, after(2s) [isReady() && !isBusy()] / doIt(); log()",
            "Behaviors" to "are calls without arguments, e.g. start(); there are no variables.",
            "Time" to "after(500ms), after(2s), after(5m) or after(1h).",
        )
        for ((k, v) in tips) {
            put(EditorPanel.richText(Triple(k, true, null), Triple(" $v", false, UIUtil.getContextHelpForeground())).apply {
                border = JBUI.Borders.emptyBottom(4)
            })
        }
    }

    // ---------------------------------------------------------------- vertices

    private fun vertexProps(s: DiagramSession, v: Vertex) {
        val ix = s.index
        put(h3(v.type.title))
        val group = Tools.swappable.firstOrNull { v.type in it }
        if (group != null) {
            put(selectField("Kind", v.type.key, group.map { it.key to it.title }) { value ->
                VertexType.parseKey(value)?.let { s.changeType(v, it) }
                render()
            })
        }
        when (v.type) {
            VertexType.ConnectionPointRef -> refProps(s, v)
            VertexType.Comment -> {
                put(textField("Text", v.text, { v.text = it; commit() }, multiline = true, rows = 5))
                put(h4("Annotated elements"))
                val anchors = v.anchors.mapNotNull { a -> ix.vertex(a) ?: ix.transition(a) }
                if (anchors.isEmpty()) put(muted("Drag the ⊕ handle onto an element to attach this comment."))
                for (a in anchors) {
                    val id = if (a is Vertex) a.id else (a as Transition).id
                    val text = if (a is Transition) "Transition ${Labels.transitionLabel(s.model, a).ifEmpty { a.id }}" else (a as Vertex).name.ifEmpty { a.type.title }
                    put(row(JBLabel(text), iconButton("Detach") {
                        v.anchors.remove(id)
                        commit(true)
                    }))
                }
            }
            else -> put(textField("Name", v.name, { v.name = it.trim(); commit() }))
        }

        if (v.type == VertexType.State) stateProps(s, v)

        val owner = ix.ownerState(v)
        put(h4("Location"))
        val where = when {
            owner != null -> "${if (ix.isBorderVertex(v)) "On the border of" else "Inside"} ${owner.name.ifEmpty { owner.type.title }}"
            v.type.isPointType -> "Connection point of the state machine"
            else -> "Top level"
        }
        put(muted("$where · ${v.x.roundToInt()}, ${v.y.roundToInt()} · ${v.w.roundToInt()}×${v.h.roundToInt()}"))
        issuesFor(v.id)?.let { put(it) }
        put(button("Delete", editor::deleteSelection).apply { border = JBUI.Borders.emptyTop(12) })
    }

    private fun stateProps(s: DiagramSession, v: Vertex) {
        fun set(assign: (String) -> Unit): (String) -> Unit = {
            assign(it)
            commit()
        }
        put(
            textField("Stereotype", v.stereotype, set { v.stereotype = it }, Expressions::checkName, "e.g. Critical"),
            h4("Behaviors"),
            textField("entry /", v.entry, set { v.entry = it }, Expressions::checkActions, "e.g. start(); log()"),
            textField("exit /", v.exit, set { v.exit = it }, Expressions::checkActions, "e.g. stop()"),
            textField("do /", v.doActivity, set { v.doActivity = it }, Expressions::checkActions, "e.g. poll()"),
            listField("Deferrable events", v.deferrable, { v.deferrable = it.toMutableList(); commit() }, Expressions::checkEvent, "evA, evB"),
            textField("State invariant", v.invariant, set { v.invariant = it }, Expressions::checkCondition, "e.g. isRunning() && !isFaulty()"),
        )

        put(h4("Submachine"))
        val current = v.submachine
        val options = mutableListOf("" to "(none: not a submachine state)")
        options.addAll(s.machines.map { it.href to "${it.name} (${it.file})" })
        if (current.isNotEmpty() && s.machines.none { it.href == current }) options.add(current to "${s.machineLabel(current)} (not found)")
        put(selectField("Referenced state machine", current, options) {
            s.setSubmachine(v, it)
            render()
        })
        if (s.machines.isEmpty() && current.isEmpty()) put(muted("No other state machine (.fsm) was found in the project."))
        if (v.submachine.isNotEmpty()) {
            val href = v.submachine
            put(button("Open ${s.machineLabel(href)}", { editor.openSubmachine(href) }))
            submachineRefsSection(s, v)
        } else {
            put(h4(if (v.regions.size > 1) "Orthogonal regions" else "Regions"))
            for ((i, region) in v.regions.withIndex()) {
                val tb = input(region.name, placeholder = "Region ${i + 1}")
                var committed = region.name
                onCommit(tb, false) {
                    if (tb.text.trim() == committed) return@onCommit
                    region.name = tb.text.trim()
                    committed = region.name
                    commit()
                }
                put(row(tb, iconButton("Remove region and its contents") {
                    s.removeRegion(v, region.id)
                    render()
                }))
            }
            put(button("+ Add region", {
                s.addRegion(v)
                render()
            }))
            if (v.regions.size > 1) {
                put(selectField(
                    "Region layout", if (v.regionLayout == RegionLayout.Horizontal) "horizontal" else "vertical",
                    listOf("vertical" to "Stacked (top to bottom)", "horizontal" to "Side by side"),
                ) {
                    v.regionLayout = if (it == "horizontal") RegionLayout.Horizontal else RegionLayout.Vertical
                    commit()
                })
            }
        }

        put(h4("Internal transitions"))
        for (t in Labels.internalTransitions(s.model, v)) {
            val tb = input(Labels.transitionLabel(s.model, t))
            var committed = tb.text
            onCommit(tb, false) {
                if (tb.text == committed) return@onCommit
                val err = Labels.applyLabel(s.model, t.copy(), tb.text, v)
                markInvalid(tb, err != null)
                tb.toolTipText = err
                if (err != null) {
                    editor.toast(err)
                    return@onCommit
                }
                s.applyLabel(t, tb.text)
                committed = Labels.transitionLabel(s.model, t)
                tb.text = committed
            }
            put(row(tb, iconButton("Remove") {
                s.removeTransition(t)
                render()
            }))
        }
        put(button("+ Add internal transition", {
            s.addInternalTransition(v)
            render()
        }))
    }

    private fun kindText(k: PointKind) = if (k == PointKind.Exit) "exit" else "entry"

    private fun refProps(s: DiagramSession, v: Vertex) {
        val st = s.index.vertex(v.parent)
        val href = st?.submachine ?: ""
        val info = s.submachines[href]
        val machine = if (href.isNotEmpty()) s.machineLabel(href) else ""
        put(muted(if (st != null) "On ${st.name.ifEmpty { "state" }}; refers to a point of ${machine.ifEmpty { "(no submachine set)" }}." else "Not attached to a state."))
        val pts: List<ConnectionPointInfo> = if (st != null) s.submachinePoints(st) else emptyList()
        if (pts.isNotEmpty()) {
            val cur = "${kindText(v.pointKind)}:${v.ref}"
            val options = pts.map { "${kindText(it.kind)}:${it.id}" to "${it.name.ifEmpty { it.id }} (${kindText(it.kind)} point)" }.toMutableList()
            if (pts.none { "${kindText(it.kind)}:${it.id}" == cur }) options.add(0, cur to if (v.ref.isNotEmpty()) "${v.ref} (missing)" else "— choose a point —")
            put(selectField("Referenced point", cur, options) { value ->
                val i = value.indexOf(':')
                v.pointKind = if (value.substring(0, i) == "exit") PointKind.Exit else PointKind.Entry
                v.ref = value.substring(i + 1)
                commit(true)
            })
        } else {
            val why = when {
                href.isEmpty() -> ""
                info == null -> "Looking up the submachine…"
                !info.found -> "$machine was not found, so its points cannot be listed."
                else -> "$machine has no entry or exit points at its top level."
            }
            if (why.isNotEmpty()) put(muted(why))
            put(
                textField("Referenced point id", v.ref, { v.ref = it.trim(); commit() }),
                selectField("Direction", kindText(v.pointKind), listOf("entry" to "Entry point", "exit" to "Exit point")) {
                    v.pointKind = if (it == "exit") PointKind.Exit else PointKind.Entry
                    commit(true)
                },
            )
        }
        if (href.isNotEmpty()) put(button("Open $machine", { editor.openSubmachine(href) }))
    }

    private fun submachineRefsSection(s: DiagramSession, v: Vertex) {
        put(h4("Connection point references"))
        val info = s.submachines[v.submachine]
        if (info == null) {
            put(muted("Looking up the submachine…"))
            return
        }
        if (!info.found) {
            put(muted("${s.machineLabel(v.submachine)} was not found. Pick another state machine above."))
            return
        }
        put(muted("From ${info.file}"))
        if (info.points.isEmpty()) {
            put(muted("${s.machineLabel(v.submachine)} has no entry or exit points. Add them in that machine by placing entry/exit points on empty canvas."))
            return
        }
        val refs = s.refsOf(v)
        for (q in info.points) {
            val existing = refs.firstOrNull { it.ref == q.id }
            val label = EditorPanel.richText(
                Triple("${if (q.kind == PointKind.Entry) "○" else "⊗"} ${q.name.ifEmpty { q.id }}", false, null),
                Triple(" · ${kindText(q.kind)}", false, UIUtil.getContextHelpForeground()),
            )
            val action = if (existing != null) {
                smallButton("Select", "Select the reference") { editor.reveal(existing.id) }
            } else {
                smallButton("Add", "Add a reference on the border") {
                    s.addAllReferences(v, q)
                    render()
                }
            }
            put(row(label, action))
        }
        if (s.freePoints(v).size > 1) {
            put(button("Add all references", {
                s.addAllReferences(v)
                render()
            }))
        }
    }

    // ---------------------------------------------------------------- transitions

    private fun transitionProps(s: DiagramSession, t: Transition) {
        val src = s.index.vertex(t.source)
        val tgt = s.index.vertex(t.target)
        fun nm(x: Vertex?) = if (x == null) "?" else x.name.ifEmpty { x.type.title }
        put(
            h3("Transition"),
            muted("${nm(src)} → ${nm(tgt)}"),
            selectField("Kind", t.kind.key, listOf("external" to "External", "local" to "Local", "internal" to "Internal")) {
                s.setKind(t, if (it == "internal") TransitionKind.Internal else if (it == "local") TransitionKind.Local else TransitionKind.External)
                render()
            },
            listField("Triggers", t.triggers, { t.triggers = it.toMutableList(); commit() }, Expressions::checkTrigger, "event, after(500ms), after(2s)"),
        )
        if (s.model.kind == MachineKind.Protocol) {
            put(
                textField("Precondition", t.precondition, { t.precondition = it; commit() }, Expressions::checkCondition, "e.g. isOpen()"),
                textField("Postcondition", t.postcondition, { t.postcondition = it; commit() }, Expressions::checkCondition, "e.g. isClosed()"),
            )
        } else {
            val allowElse = Labels.allowsElse(src)
            put(
                textField(
                    "Guard", t.guard, { t.guard = it; commit() }, { Expressions.checkGuard(it, allowElse) },
                    if (allowElse) "e.g. hasDisc() && !isJammed(), or else" else "e.g. hasDisc() && !isJammed()",
                ),
                textField("Effect", t.effect, { t.effect = it; commit() }, Expressions::checkActions, "e.g. notify(); log()"),
            )
        }
        put(h4("Routing"))
        val routing = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        routing.add(JButton("Straighten").apply {
            addActionListener {
                s.straighten(t)
                render()
            }
        })
        routing.add(JButton("Reverse").apply {
            addActionListener {
                s.reverse(t)
                render()
            }
        })
        routing.maximumSize = Dimension(Int.MAX_VALUE, routing.preferredSize.height)
        put(routing)
        issuesFor(t.id)?.let { put(it) }
        put(button("Delete", editor::deleteSelection).apply { border = JBUI.Borders.emptyTop(12) })
    }
}
