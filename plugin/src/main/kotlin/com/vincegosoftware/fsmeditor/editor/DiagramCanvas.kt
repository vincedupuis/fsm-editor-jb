package com.vincegosoftware.fsmeditor.editor

import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.JBFont
import com.vincegosoftware.fsmeditor.core.DiagramSession
import com.vincegosoftware.fsmeditor.core.Labels
import com.vincegosoftware.fsmeditor.core.Num
import com.vincegosoftware.fsmeditor.core.PointD
import com.vincegosoftware.fsmeditor.core.PointKind
import com.vincegosoftware.fsmeditor.core.RectD
import com.vincegosoftware.fsmeditor.core.RegionLayout
import com.vincegosoftware.fsmeditor.core.Severity
import com.vincegosoftware.fsmeditor.core.SvgExport
import com.vincegosoftware.fsmeditor.core.TextAnchor
import com.vincegosoftware.fsmeditor.core.Tools
import com.vincegosoftware.fsmeditor.core.Transition
import com.vincegosoftware.fsmeditor.core.Vertex
import com.vincegosoftware.fsmeditor.core.VertexType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.vincegosoftware.fsmeditor.core.Geometry as Geo

/**
 * The diagram: draws the model with a pan/zoom view and turns mouse and
 * keyboard input into editing operations of the [DiagramSession].
 */
class DiagramCanvas(private val editor: EditorPanel) : JComponent(), UiDataProvider {
    private val labels = ArrayList<Pair<Rectangle2D, String>>()
    private var drag: DragState? = null
    private var space = false
    private val viewListeners = ArrayList<() -> Unit>()
    private val toolListeners = ArrayList<() -> Unit>()

    private val s: DiagramSession? get() = editor.session

    var viewX = 40.0
        private set
    var viewY = 40.0
        private set
    var zoom = 1.0
        private set

    /** The armed toolbox tool, or null for selection. */
    var tool: String? = null
        private set
    var toolSticky = false
        private set

    init {
        isFocusable = true
        isOpaque = true
        val mouse = Mouse()
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        addKeyListener(Keys())
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                space = false
            }
        })
        if (!GraphicsEnvironment.isHeadless()) dropTarget = DropTarget(this, DnDConstants.ACTION_COPY, ToolDrop(), true)
    }

    fun onViewChanged(listener: () -> Unit) {
        viewListeners.add(listener)
    }

    fun onToolChanged(listener: () -> Unit) {
        toolListeners.add(listener)
    }

    /** Copy, cut, paste and delete of the IDE act on the diagram while it has the focus. */
    override fun uiDataSnapshot(sink: DataSink) {
        val handler = editor.clipboardHandler
        sink[PlatformDataKeys.COPY_PROVIDER] = handler
        sink[PlatformDataKeys.CUT_PROVIDER] = handler
        sink[PlatformDataKeys.PASTE_PROVIDER] = handler
        sink[PlatformDataKeys.DELETE_ELEMENT_PROVIDER] = handler
    }

    // ---------------------------------------------------------------- view

    fun toScreen(x: Double, y: Double) = PointD(x * zoom + viewX, y * zoom + viewY)

    private fun toWorld(screen: Point) = PointD((screen.x - viewX) / zoom, (screen.y - viewY) / zoom)

    private fun setView(x: Double, y: Double, z: Double) {
        viewX = x
        viewY = y
        zoom = z
        repaint()
        viewListeners.forEach { it() }
    }

    fun zoomAt(factor: Double, sx: Double, sy: Double) {
        val z = Geo.clamp(zoom * factor, 0.15, 4.0)
        val k = z / zoom
        setView(sx - (sx - viewX) * k, sy - (sy - viewY) * k, z)
    }

    fun zoomCenter(factor: Double) = zoomAt(factor, width / 2.0, height / 2.0)

    fun fit() {
        val s = s ?: return
        if (width <= 0 || height <= 0) return
        s.reindex()
        val b = Geo.contentBounds(s.index, ::labelWidth)
        val pad = 40.0
        val z = Geo.clamp(min((width - pad * 2) / max(b.w, 1.0), (height - pad * 2) / max(b.h, 1.0)), 0.2, 1.5)
        setView((width - b.w * z) / 2 - b.x * z, (height - b.h * z) / 2 - b.y * z, z)
    }

    /** Centers the view on an element. */
    fun reveal(id: String) {
        val s = s ?: return
        s.reindex()
        var p = s.index.vertex(id)?.center
        if (p == null) {
            val t = s.index.transition(id)
            val pts = if (t != null) Geo.route(s.index, t) else null
            if (pts != null) p = Geo.polylineMid(pts).point
        }
        if (p == null) return
        setView(width / 2.0 - p.x * zoom, height / 2.0 - p.y * zoom, zoom)
    }

    // ---------------------------------------------------------------- tools

    fun setTool(id: String?, sticky: Boolean = false) {
        tool = if (id != null && tool == id && !sticky) null else id
        toolSticky = tool != null && sticky
        cursor = if (tool != null) Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) else null
        toolListeners.forEach { it() }
        repaint()
        when (tool) {
            "transition" -> editor.toast("Drag from a source to a target. Esc to cancel.")
            "region" -> editor.toast("Click a state to add a region to it.")
            "entryPoint", "exitPoint" -> editor.toast("Click a state's border, or empty canvas for a connection point of the state machine itself.")
            "connectionPointRef" -> editor.toast("Click the border of a submachine state.")
        }
    }

    private fun toolDone() {
        if (!toolSticky) setTool(null)
    }

    /** Places an element of a toolbox tool at a point of the view. */
    fun createAt(toolId: String, screen: Point) = create(toolId, toWorld(screen))

    private fun create(toolId: String, p: PointD) {
        val s = s ?: return
        val v = s.createVertex(toolId, p)
        editor.renderProps()
        toolDone()
        if (v != null && (v.type == VertexType.State || v.type == VertexType.Comment)) {
            SwingUtilities.invokeLater { editor.startInlineEdit(v.id) }
        }
    }

    // ---------------------------------------------------------------- rendering

    private val th: Theme get() = Theme.current

    private val fontFamily: String get() = JBFont.label().family

    private fun font(size: Double, bold: Boolean = false, italic: Boolean = false): Font =
        Font(fontFamily, (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0), 12).deriveFont(size.toFloat())

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = th.background
            g.fillRect(0, 0, width, height)
            drawGrid(g)
            val s = s ?: return
            s.reindex()
            labels.clear()
            g.translate(viewX, viewY)
            g.scale(zoom, zoom)
            val ix = s.index
            val sorted = Geo.drawingOrder(ix)
            for (v in sorted.filter { it.type == VertexType.Comment }) drawAnchors(g, s, v)
            for (v in sorted) drawVertex(g, s, v)
            for (t in s.model.transitions) drawTransition(g, s, t)
            drawOverlay(g, s)
        } finally {
            g.dispose()
        }
    }

    private fun drawGrid(g: Graphics2D) {
        val step = Geo.GRID * 2 * zoom
        if (step < 4) return
        g.color = th.grid
        var x = viewX % step + step / 2 - step
        while (x < width) {
            var y = viewY % step + step / 2 - step
            while (y < height) {
                g.fill(Ellipse2D.Double(x - 1.1, y - 1.1, 2.2, 2.2))
                y += step
            }
            x += step
        }
    }

    /** Draws text with its baseline at [y], like SVG text; returns its box. */
    private fun drawText(
        g: Graphics2D, x: Double, y: Double, text: String, size: Double, color: Color,
        anchor: TextAnchor = TextAnchor.Middle, bold: Boolean = false, italic: Boolean = false, halo: Color? = null,
    ): Rectangle2D? {
        if (text.isEmpty()) return null
        val f = font(size, bold, italic)
        val fm = g.getFontMetrics(f)
        val w = fm.getStringBounds(text, g).width
        val left = when (anchor) {
            TextAnchor.Middle -> x - w / 2
            TextAnchor.End -> x - w
            TextAnchor.Start -> x
        }
        if (halo != null) {
            val outline = TextLayout(text, f, g.fontRenderContext).getOutline(AffineTransform.getTranslateInstance(left, y))
            g.color = halo
            g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(outline)
            g.color = color
            g.fill(outline)
        } else {
            g.font = f
            g.color = color
            g.drawString(text, left.toFloat(), y.toFloat())
        }
        return Rectangle2D.Double(left, y - fm.ascent, w, (fm.ascent + fm.descent).toDouble())
    }

    /** Width of a transition label as drawn, for fitting the view. */
    fun labelWidth(label: String): Double = getFontMetrics(font(11.0)).stringWidth(label).toDouble()

    private enum class IssueLevel { None, Warning, Error }

    private fun issueOf(id: String): IssueLevel {
        val list = editor.issuesFor(id)
        if (list.any { it.severity == Severity.Error }) return IssueLevel.Error
        if (list.any { it.severity == Severity.Warning }) return IssueLevel.Warning
        return IssueLevel.None
    }

    private fun Graphics2D.line(pen: BasicStroke, color: Color, x1: Double, y1: Double, x2: Double, y2: Double) {
        this.stroke = pen
        this.color = color
        draw(Line2D.Double(x1, y1, x2, y2))
    }

    private fun drawVertex(g: Graphics2D, s: DiagramSession, v: Vertex) {
        val ix = s.index
        val selected = v.id in s.selection
        val issue = issueOf(v.id)
        val dropTarget = drag?.dropState == v.id

        // Outline of the element's own shape: selection, then warnings and errors, win in that order.
        var stroke = th.line
        var shapeWidth = 1.4
        var fill = th.line
        if (selected) {
            stroke = th.accent
            shapeWidth = 2.4
            fill = th.accent
        }
        if (issue == IssueLevel.Warning) stroke = th.warning
        if (issue == IssueLevel.Error) {
            stroke = th.error
            shapeWidth = 2.0
            fill = th.error
        }
        val shapePen = if (dropTarget) th.stroke(2.5, floatArrayOf(6f, 3f)) else th.stroke(shapeWidth)
        val shapeColor = if (dropTarget) th.accent else stroke
        val shapeFill = if (v.type == VertexType.Comment) th.noteFill else th.stateFill
        val textColor = th.foreground
        val dimText = th.alpha(th.foreground, 0.8)

        fun outline(shape: java.awt.Shape, filled: Boolean = true) {
            if (filled) {
                g.color = shapeFill
                g.fill(shape)
            }
            g.stroke = shapePen
            g.color = shapeColor
            g.draw(shape)
        }

        val x = v.x
        val y = v.y
        val w = v.w
        val h = v.h
        val cx = x + w / 2
        val cy = y + h / 2
        val r = min(w, h) / 2
        val sepPen = th.stroke(1.0)
        val sepColor = th.alpha(th.line, 0.8)
        when (v.type) {
            VertexType.State -> {
                outline(RoundRectangle2D.Double(x, y, w, h, 20.0, 20.0))
                val lines = Labels.activityLines(s.model, v)
                val regions = Geo.regionRects(s.model, v)
                val title = v.name + if (v.submachine.isNotEmpty()) " : " + s.machineLabel(v.submachine) else ""
                val centered = regions.isEmpty() && lines.isEmpty()
                val stereo = v.stereotype.isNotEmpty()
                var ty = y + 16
                if (stereo) {
                    drawText(g, cx, y + if (centered) h / 2 - 4 else 13.0, "«${v.stereotype}»", 10.0, dimText, italic = true)
                    ty += 12
                }
                if (centered) {
                    drawText(g, cx, y + h / 2 + if (stereo) 10 else 4, title, 12.0, textColor, bold = true)
                } else {
                    drawText(g, cx, ty, title, 12.0, textColor, bold = true)
                    var ly = y + Geo.NAME_HEIGHT + if (stereo) 12 else 0
                    if (lines.isNotEmpty()) {
                        g.line(sepPen, sepColor, x, ly, x + w, ly)
                        for (l in lines) {
                            ly += Geo.LINE_HEIGHT
                            drawText(g, x + 8, ly, l, 11.0, textColor, TextAnchor.Start)
                        }
                    }
                }
                if (regions.isNotEmpty()) {
                    val top = regions[0].rect.y
                    g.line(sepPen, sepColor, x, top, x + w, top)
                    val dashed = th.stroke(1.0, floatArrayOf(7f, 4f))
                    for ((i, region) in regions.withIndex()) {
                        val rr = region.rect
                        if (i > 0) {
                            if (v.regionLayout == RegionLayout.Horizontal) g.line(dashed, sepColor, rr.x, rr.y, rr.x, rr.y + rr.h)
                            else g.line(dashed, sepColor, rr.x, rr.y, rr.x + rr.w, rr.y)
                        }
                        if (region.name.isNotEmpty()) drawText(g, rr.x + 6, rr.y + 12, region.name, 10.0, dimText, TextAnchor.Start, italic = true)
                        if (drag?.dropRegion == region.id) {
                            g.color = th.alpha(th.accent, 0.08)
                            g.fill(Rectangle2D.Double(rr.x, rr.y, rr.w, rr.h))
                        }
                    }
                }
                if (v.submachine.isNotEmpty()) {
                    val gx = x + w - 26
                    val gy = y + h - 14
                    g.stroke = th.stroke(1.2)
                    g.color = stroke
                    g.draw(RoundRectangle2D.Double(gx, gy, 8.0, 6.0, 4.0, 4.0))
                    g.draw(RoundRectangle2D.Double(gx + 13, gy, 8.0, 6.0, 4.0, 4.0))
                    g.draw(Line2D.Double(gx + 8, gy + 3, gx + 13, gy + 3))
                }
                if (v.invariant.isNotEmpty()) drawText(g, x + 4, y + h + 14, "{${v.invariant}}", 11.0, textColor, TextAnchor.Start, italic = true)
            }
            VertexType.Final -> {
                outline(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
                val ri = max(2.0, r - 5)
                g.color = fill
                g.fill(Ellipse2D.Double(cx - ri, cy - ri, ri * 2, ri * 2))
            }
            VertexType.Initial, VertexType.Junction -> {
                g.color = fill
                g.fill(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
            }
            VertexType.ShallowHistory, VertexType.DeepHistory -> {
                outline(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
                drawText(g, cx, cy + 4, if (v.type == VertexType.DeepHistory) "H*" else "H", 11.0, textColor, bold = true)
            }
            VertexType.Choice -> {
                val diamond = Path2D.Double()
                diamond.moveTo(cx, y)
                diamond.lineTo(x + w, cy)
                diamond.lineTo(cx, y + h)
                diamond.lineTo(x, cy)
                diamond.closePath()
                outline(diamond)
            }
            VertexType.Fork, VertexType.Join -> {
                g.color = fill
                g.fill(RoundRectangle2D.Double(x, y, w, h, 2.0, 2.0))
            }
            VertexType.ConnectionPointRef, VertexType.EntryPoint, VertexType.ExitPoint -> {
                outline(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
                if (v.type == VertexType.ExitPoint || (v.type == VertexType.ConnectionPointRef && v.pointKind == PointKind.Exit)) {
                    val k = r * 0.62
                    val cross = th.stroke(1.3)
                    g.line(cross, stroke, cx - k, cy - k, cx + k, cy + k)
                    g.line(cross, stroke, cx + k, cy - k, cx - k, cy + k)
                }
            }
            VertexType.Terminate -> {
                val cross = th.stroke(2.0)
                g.line(cross, stroke, x, y, x + w, y + h)
                g.line(cross, stroke, x + w, y, x, y + h)
            }
            VertexType.Comment -> {
                val f = 12.0
                val note = Path2D.Double()
                note.moveTo(x, y)
                note.lineTo(x + w - f, y)
                note.lineTo(x + w, y + f)
                note.lineTo(x + w, y + h)
                note.lineTo(x, y + h)
                note.closePath()
                outline(note)
                val fold = th.stroke(1.0)
                g.line(fold, stroke, x + w - f, y, x + w - f, y + f)
                g.line(fold, stroke, x + w - f, y + f, x + w, y + f)
                for ((i, line) in v.text.split('\n').withIndex()) {
                    if (16 + i * 14 < h) drawText(g, x + 8, y + 17 + i * 14, line.trimEnd('\r'), 11.0, textColor, TextAnchor.Start)
                }
            }
        }
        val caption = if (v.type == VertexType.ConnectionPointRef) s.pointName(ix.vertex(v.parent), v.ref).ifEmpty { "?" } else v.name
        if (caption.isNotEmpty() && v.type != VertexType.State && v.type != VertexType.Comment) {
            val below = if (v.type == VertexType.Fork || v.type == VertexType.Join) y + h + 13 else y + h + 12
            drawText(g, cx, below, caption, 11.0, textColor)
        }
    }

    private fun drawTransition(g: Graphics2D, s: DiagramSession, t: Transition) {
        val ix = s.index
        if (!Geo.isDrawn(ix, t)) return
        val pts = Geo.route(ix, t) ?: return
        val selected = t.id in s.selection
        val color = when (issueOf(t.id)) {
            IssueLevel.Error -> th.error
            IssueLevel.Warning -> th.warning
            IssueLevel.None -> if (selected) th.accent else th.line
        }
        g.stroke = th.stroke(if (selected) 2.0 else 1.3)
        g.color = color
        val line = Path2D.Double()
        line.moveTo(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) line.lineTo(p.x, p.y)
        g.draw(line)
        val b = pts[pts.size - 1]
        val (p1, p2) = SvgExport.arrowHead(pts[pts.size - 2], b)
        val arrow = Path2D.Double()
        arrow.moveTo(p1.x, p1.y)
        arrow.lineTo(b.x, b.y)
        arrow.lineTo(p2.x, p2.y)
        g.draw(arrow)
        val label = Labels.transitionLabel(s.model, t)
        if (label.isNotEmpty()) {
            val lp = Geo.labelPos(t, pts)
            val box = drawText(g, lp.x, lp.y, label, 11.0, if (selected) th.accent else th.foreground, lp.anchor, halo = th.background)
            if (box != null) labels.add(box to t.id)
        }
        if (selected && s.selection.size == 1) {
            for (p in t.points) {
                val dot = Ellipse2D.Double(p.x - 4.5, p.y - 4.5, 9.0, 9.0)
                g.color = th.background
                g.fill(dot)
                g.color = th.accent
                g.stroke = th.stroke(1.5)
                g.draw(dot)
            }
        }
    }

    private fun drawAnchors(g: Graphics2D, s: DiagramSession, comment: Vertex) {
        val pen = th.stroke(1.0, floatArrayOf(4f, 3f))
        val color = th.alpha(th.line, 0.7)
        for ((from, to) in SvgExport.anchorLines(s.index, comment)) g.line(pen, color, from.x, from.y, to.x, to.y)
    }

    private fun drawOverlay(g: Graphics2D, s: DiagramSession) {
        val accent = th.accent
        val sel = s.selection.mapNotNull { s.index.vertex(it) }
        g.stroke = th.stroke(1.0, floatArrayOf(4f, 3f))
        g.color = accent
        for (v in sel) g.draw(RoundRectangle2D.Double(v.x - 4, v.y - 4, v.w + 8, v.h + 8, 6.0, 6.0))
        if (sel.size == 1 && s.selection.size == 1) {
            val v = sel[0]
            if (v.type in RESIZABLE) {
                val rh = resizeHandle(v)
                val shape = RoundRectangle2D.Double(rh.x, rh.y, rh.w, rh.h, 3.0, 3.0)
                g.color = accent
                g.fill(shape)
                g.stroke = th.stroke(1.0)
                g.color = th.background
                g.draw(shape)
            }
            if (v.type != VertexType.Final && v.type != VertexType.Terminate) {
                val c = connectHandle(v)
                val pen = th.stroke(1.5)
                val circle = Ellipse2D.Double(c.x - 7, c.y - 7, 14.0, 14.0)
                g.color = th.background
                g.fill(circle)
                g.color = accent
                g.stroke = pen
                g.draw(circle)
                g.line(pen, accent, c.x - 3, c.y, c.x + 3, c.y)
                g.line(pen, accent, c.x + 0.5, c.y - 2.5, c.x + 3, c.y)
                g.line(pen, accent, c.x + 3, c.y, c.x + 0.5, c.y + 2.5)
            }
        }
        val d = drag
        val cur = d?.cur
        if (d?.kind == DragKind.Connect && cur != null) {
            val target = d.hover?.let { s.index.vertex(it) }
            val from = Geo.clip(d.source!!, target?.center ?: cur)
            val to = if (target != null) Geo.clip(target, from) else cur
            g.line(th.stroke(1.5, floatArrayOf(5f, 3f)), accent, from.x, from.y, to.x, to.y)
        }
        if (d?.kind == DragKind.Band && cur != null) {
            val r = RectD.fromPoints(d.start, cur)
            val rect = Rectangle2D.Double(r.x, r.y, r.w, r.h)
            g.color = th.alpha(accent, 0.08)
            g.fill(rect)
            g.color = accent
            g.stroke = th.stroke(1.0)
            g.draw(rect)
        }
    }

    private fun resizeHandle(v: Vertex) = RectD(v.x + v.w - 1, v.y + v.h - 1, 9.0, 9.0)

    private fun connectHandle(v: Vertex) = PointD(v.x + v.w + 18, v.y + v.h / 2)

    // ---------------------------------------------------------------- hit testing

    private enum class HitKind { Empty, Handle, Waypoint, Label, Vertex, Transition }

    private class Hit(val kind: HitKind, val id: String = "", val handle: String = "", val index: Int = 0)

    private fun hitTest(s: DiagramSession, p: PointD): Hit {
        val ix = s.index
        val tolerance = 2 / zoom
        if (s.selection.size == 1) {
            val only = ix.vertex(s.selection.first())
            if (only != null) {
                val c = connectHandle(only)
                if (only.type != VertexType.Final && only.type != VertexType.Terminate && Geo.dist(p, c) <= 7 + tolerance) {
                    return Hit(HitKind.Handle, handle = "connect")
                }
                val rh = resizeHandle(only)
                if (only.type in RESIZABLE && RectD(rh.x - tolerance, rh.y - tolerance, rh.w + tolerance * 2, rh.h + tolerance * 2).contains(p)) {
                    return Hit(HitKind.Handle, handle = "resize")
                }
            }
            val selected = ix.transition(s.selection.first())
            if (selected != null) {
                for ((i, wp) in selected.points.withIndex()) {
                    if (Geo.dist(p, wp) <= 4.5 + tolerance) return Hit(HitKind.Waypoint, selected.id, index = i)
                }
            }
        }
        return itemAt(s, p, true)
    }

    /** The topmost transition (line or label) or vertex at [p]. */
    private fun itemAt(s: DiagramSession, p: PointD, withLabels: Boolean): Hit {
        val ix = s.index
        val transitions = s.model.transitions
        for (i in transitions.indices.reversed()) {
            val t = transitions[i]
            if (withLabels && labels.any { it.second == t.id && it.first.contains(p.x, p.y) }) return Hit(HitKind.Label, t.id)
            if (!Geo.isDrawn(ix, t)) continue
            val pts = Geo.route(ix, t) ?: continue
            for (k in 0 until pts.size - 1) {
                if (Geo.distToSegment(p, pts[k], pts[k + 1]) <= 6) return Hit(HitKind.Transition, t.id)
            }
        }
        val sorted = Geo.drawingOrder(ix)
        for (i in sorted.indices.reversed()) {
            if (contains(sorted[i], p)) return Hit(HitKind.Vertex, sorted[i].id)
        }
        return Hit(HitKind.Empty)
    }

    private fun contains(v: Vertex, p: PointD): Boolean {
        val x = v.x
        val y = v.y
        val w = v.w
        val h = v.h
        val cx = x + w / 2
        val cy = y + h / 2
        val bar = v.type == VertexType.Fork || v.type == VertexType.Join
        // Small elements get a bigger target.
        if (!bar && min(w, h) < 20 && abs(p.x - cx) <= 11 && abs(p.y - cy) <= 11) return true
        if (bar && min(w, h) < 14 && RectD(x - 4, y - 5, w + 8, h + 10).contains(p)) return true
        return when (v.type) {
            VertexType.State, VertexType.Comment, VertexType.Fork, VertexType.Join, VertexType.Terminate -> RectD(x, y, w, h).contains(p)
            VertexType.Choice -> abs(p.x - cx) / (w / 2) + abs(p.y - cy) / (h / 2) <= 1
            else -> Geo.dist(p, PointD(cx, cy)) <= min(w, h) / 2
        }
    }

    /** The element under the pointer to connect to: a vertex, or any element for a comment. */
    private fun connectTargetAt(s: DiagramSession, p: PointD, source: Vertex): String? {
        val hit = itemAt(s, p, true)
        if (hit.kind == HitKind.Vertex) return hit.id
        if ((hit.kind == HitKind.Transition || hit.kind == HitKind.Label) && source.type == VertexType.Comment) return hit.id
        return null
    }

    // ---------------------------------------------------------------- mouse

    private enum class DragKind { Pan, Move, Resize, Connect, Waypoint, Label, Band }

    private class Moved(val v: Vertex, val x0: Double, val y0: Double)

    private class DragState(val kind: DragKind) {
        var start = PointD(0.0, 0.0)
        var screenStart = Point()
        var viewX0 = 0.0
        var viewY0 = 0.0
        var items: List<Moved> = emptyList()
        var transitions: List<Pair<Transition, List<PointD>>> = emptyList()
        var roots: List<Vertex> = emptyList()
        var moving: Set<String> = emptySet()
        var moved = false
        var clickedId: String? = null
        var dropRegion: String? = null
        var dropState: String? = null
        var vertex: Vertex? = null
        var w0 = 0.0
        var h0 = 0.0
        var source: Vertex? = null
        var cur: PointD? = null
        var hover: String? = null
        var transition: Transition? = null
        var index = 0
        var offset0 = PointD(0.0, 0.0)
        var additive = false
        var base: Set<String> = emptySet()
    }

    private fun additive(e: InputEvent) = e.isShiftDown || e.isControlDown || e.isMetaDown

    private inner class Mouse : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            val s = s ?: return
            requestFocusInWindow()
            editor.commitInlineEdit()
            val p = toWorld(e.point)

            if (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e) || space) {
                drag = DragState(DragKind.Pan).also {
                    it.screenStart = e.point
                    it.viewX0 = viewX
                    it.viewY0 = viewY
                }
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                return
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return
            if (e.clickCount == 2) {
                drag = null
                doubleClick(s, p, e)
                return
            }
            s.reindex()
            val hit = hitTest(s, p)

            val armed = tool
            if (armed != null) {
                val def = Tools.byId(armed)
                if (armed == "transition") {
                    if (hit.kind == HitKind.Vertex) startConnect(s.index.vertex(hit.id)!!, p)
                    else editor.toast("Start the transition on a state or pseudostate.")
                } else if (armed == "region") {
                    val st = if (hit.kind == HitKind.Vertex) s.index.vertex(hit.id) else Geo.stateAt(s.index, p, emptySet())
                    if (st != null && st.type == VertexType.State) {
                        if (st.submachine.isNotEmpty()) {
                            editor.toast("A submachine state cannot own regions.")
                        } else {
                            s.addRegion(st)
                            editor.renderProps()
                        }
                        toolDone()
                    } else {
                        editor.toast("Click a state to add a region to it.")
                    }
                } else if (def?.create != null) {
                    create(armed, p)
                }
                repaint()
                return
            }

            when (hit.kind) {
                HitKind.Handle -> {
                    val v = s.index.vertex(s.selection.first())
                    if (v != null) {
                        if (hit.handle == "resize") {
                            drag = DragState(DragKind.Resize).also {
                                it.vertex = v
                                it.start = p
                                it.w0 = v.w
                                it.h0 = v.h
                            }
                        } else {
                            startConnect(v, p)
                        }
                    }
                }
                HitKind.Waypoint -> drag = DragState(DragKind.Waypoint).also {
                    it.transition = s.index.transition(hit.id)
                    it.index = hit.index
                }
                HitKind.Label -> {
                    val t = s.index.transition(hit.id)!!
                    s.selection = linkedSetOf(t.id)
                    drag = DragState(DragKind.Label).also {
                        it.transition = t
                        it.start = p
                        it.offset0 = t.labelOffset ?: PointD(0.0, 0.0)
                    }
                    editor.renderProps()
                }
                HitKind.Vertex, HitKind.Transition -> {
                    val id = hit.id
                    var clickedSelected = false
                    if (additive(e)) {
                        if (!s.selection.remove(id)) s.selection.add(id)
                    } else if (id !in s.selection) {
                        s.selection = linkedSetOf(id)
                    } else {
                        clickedSelected = true
                    }
                    editor.renderProps()
                    if (hit.kind == HitKind.Vertex && id in s.selection) startMove(s, p, if (clickedSelected) id else null)
                }
                HitKind.Empty -> {
                    if (!additive(e)) s.selection.clear()
                    drag = DragState(DragKind.Band).also {
                        it.start = p
                        it.additive = additive(e)
                        it.base = s.selection.toSet()
                    }
                    editor.renderProps()
                }
            }
            repaint()
            editor.renderStatus()
        }

        override fun mouseDragged(e: MouseEvent) {
            val s = s ?: return
            val p = toWorld(e.point)
            val d = drag ?: return
            when (d.kind) {
                DragKind.Pan -> {
                    setView(d.viewX0 + e.x - d.screenStart.x, d.viewY0 + e.y - d.screenStart.y, zoom)
                    return
                }
                DragKind.Move -> {
                    var dx = p.x - d.start.x
                    var dy = p.y - d.start.y
                    if (!d.moved && sqrt(dx * dx + dy * dy) * zoom < 3) return
                    d.moved = true
                    val ix = s.index
                    val lead = d.roots.firstOrNull()
                    if (lead != null && !e.isAltDown && !ix.isBorderVertex(lead)) {
                        val it = d.items.first { m -> m.v === lead }
                        dx = Geo.snap(it.x0 + dx) - it.x0
                        dy = Geo.snap(it.y0 + dy) - it.y0
                    }
                    for (it in d.items) {
                        it.v.x = it.x0 + dx
                        it.v.y = it.y0 + dy
                    }
                    for ((t, pts0) in d.transitions) t.points = pts0.mapTo(mutableListOf()) { PointD(it.x + dx, it.y + dy) }
                    // Connection points dragged on their own stay glued to their state's border.
                    if (d.roots.size == 1 && lead != null && ix.isBorderVertex(lead)) {
                        val st = ix.vertex(lead.parent)
                        if (st != null) Geo.snapToBorder(lead, st, p)
                    }
                    val probe = lead?.center ?: p
                    val rr = if (d.roots.all { !ix.isBorderVertex(it) }) Geo.regionAt(ix, probe, d.moving) else null
                    d.dropRegion = rr?.id
                    d.dropState = if (rr != null) ix.regionOwner[rr.id]?.id else null
                }
                DragKind.Resize -> {
                    val v = d.vertex!!
                    val (minW, minH) = s.minimumSize(v)
                    v.w = max(minW, if (e.isAltDown) d.w0 + p.x - d.start.x else Geo.snap(v.x + d.w0 + p.x - d.start.x) - v.x)
                    v.h = max(minH, if (e.isAltDown) d.h0 + p.y - d.start.y else Geo.snap(v.y + d.h0 + p.y - d.start.y) - v.y)
                    s.keepPointsOnBorder(v)
                }
                DragKind.Connect -> {
                    d.cur = p
                    d.hover = connectTargetAt(s, p, d.source!!)
                    if (d.hover != null && s.index.vertex(d.hover) == null) d.hover = null
                }
                DragKind.Waypoint -> d.transition!!.points[d.index] = if (e.isAltDown) p else PointD(Geo.snap(p.x), Geo.snap(p.y))
                DragKind.Label -> {
                    d.moved = true
                    d.transition!!.labelOffset = PointD(Num.round(d.offset0.x + p.x - d.start.x), Num.round(d.offset0.y + p.y - d.start.y))
                }
                DragKind.Band -> {
                    d.cur = p
                    val r = RectD.fromPoints(d.start, p)
                    val next = LinkedHashSet<String>(if (d.additive) d.base else emptySet())
                    for (v in s.model.vertices) {
                        if (v.x >= r.x && v.y >= r.y && v.x + v.w <= r.x + r.w && v.y + v.h <= r.y + r.h) next.add(v.id)
                    }
                    for (t in s.model.transitions) {
                        if (t.source in next && t.target in next) next.add(t.id)
                    }
                    s.selection = next
                    editor.renderStatus()
                }
            }
            repaint()
        }

        override fun mouseMoved(e: MouseEvent) {
            val s = s ?: return
            if (drag == null) updateHoverCursor(s, toWorld(e.point))
        }

        override fun mouseReleased(e: MouseEvent) {
            val s = s ?: return
            val d = drag ?: return
            drag = null
            cursor = if (tool != null) Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) else null
            when (d.kind) {
                DragKind.Pan -> repaint()
                DragKind.Move -> {
                    if (!d.moved) {
                        if (d.clickedId != null) {
                            s.selection = linkedSetOf(d.clickedId!!)
                            editor.renderProps()
                        }
                        repaint()
                    } else {
                        s.dropMoved(d.roots, d.moving)
                        editor.renderProps()
                    }
                }
                DragKind.Resize, DragKind.Waypoint -> s.commit()
                DragKind.Label -> if (d.moved) s.commit() else repaint()
                DragKind.Connect -> {
                    val target = connectTargetAt(s, toWorld(e.point), d.source!!)
                    if (target != null) {
                        val t = s.connect(d.source!!, target)
                        editor.renderProps()
                        if (t != null && d.source!!.type == VertexType.State) {
                            SwingUtilities.invokeLater { editor.startInlineEdit(t.id) }
                        }
                    }
                    repaint()
                    toolDone()
                }
                DragKind.Band -> {
                    editor.renderProps()
                    repaint()
                }
            }
            editor.renderStatus()
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            val rotation = e.preciseWheelRotation
            // The wheel zooms around the pointer; Shift+wheel (or a horizontal trackpad scroll) pans sideways.
            if (e.isShiftDown) setView(viewX - rotation * 30, viewY, zoom)
            else zoomAt(exp(-rotation * 0.216), e.x.toDouble(), e.y.toDouble())
            e.consume()
        }
    }

    private fun startConnect(source: Vertex, p: PointD) {
        drag = DragState(DragKind.Connect).also {
            it.source = source
            it.cur = p
        }
    }

    private fun startMove(s: DiagramSession, p: PointD, clickedId: String?) {
        val ix = s.index
        val moving = LinkedHashMap<String, Vertex>()
        val roots = ArrayList<Vertex>()
        for (id in s.selection) {
            val v = ix.vertex(id) ?: continue
            if (ix.ancestors(v).any { it.id in s.selection }) continue
            roots.add(v)
            moving[v.id] = v
            for (d in ix.descendants(v)) moving[d.id] = d
        }
        drag = DragState(DragKind.Move).also { d ->
            d.start = p
            d.items = moving.values.map { Moved(it, it.x, it.y) }
            d.transitions = s.model.transitions
                .filter { it.points.isNotEmpty() && it.source in moving && it.target in moving }
                .map { it to it.points.toList() }
            d.roots = roots
            d.moving = moving.keys.toSet()
            d.clickedId = clickedId
        }
    }

    private fun updateHoverCursor(s: DiagramSession, p: PointD) {
        if (tool != null) {
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            return
        }
        if (space) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            return
        }
        val hit = hitTest(s, p)
        cursor = when (hit.kind) {
            HitKind.Handle -> Cursor.getPredefinedCursor(if (hit.handle == "resize") Cursor.SE_RESIZE_CURSOR else Cursor.CROSSHAIR_CURSOR)
            HitKind.Waypoint, HitKind.Label -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            else -> null
        }
    }

    private fun doubleClick(s: DiagramSession, p: PointD, e: MouseEvent) {
        if (tool != null) return
        s.reindex()
        val hit = hitTest(s, p)
        when (hit.kind) {
            HitKind.Waypoint -> s.removeWaypoint(s.index.transition(hit.id)!!, hit.index)
            HitKind.Label -> editor.startInlineEdit(hit.id)
            HitKind.Transition -> {
                s.addWaypoint(s.index.transition(hit.id)!!, p)
                editor.renderProps()
            }
            HitKind.Vertex -> {
                val v = s.index.vertex(hit.id)!!
                if (v.type == VertexType.State && v.submachine.isNotEmpty() && e.isAltDown) editor.openSubmachine(v.submachine)
                else editor.startInlineEdit(v.id)
            }
            HitKind.Empty -> create("state", p)
            HitKind.Handle -> {}
        }
        repaint()
    }

    // ---------------------------------------------------------------- toolbox drag and drop

    private inner class ToolDrop : DropTargetAdapter() {
        private fun toolOf(text: String?) = text?.takeIf { it.startsWith(TOOL_PREFIX) }?.substring(TOOL_PREFIX.length)

        override fun dragOver(e: DropTargetDragEvent) {
            if (e.isDataFlavorSupported(DataFlavor.stringFlavor)) e.acceptDrag(DnDConstants.ACTION_COPY) else e.rejectDrag()
        }

        override fun drop(e: DropTargetDropEvent) {
            e.acceptDrop(DnDConstants.ACTION_COPY)
            val id = toolOf(runCatching { e.transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull())
            if (id == null || s == null) {
                e.dropComplete(false)
                return
            }
            e.dropComplete(true)
            requestFocusInWindow()
            createAt(id, e.location)
        }
    }

    // ---------------------------------------------------------------- keyboard

    private inner class Keys : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            val s = s ?: return
            val key = e.keyCode
            if (key == KeyEvent.VK_SPACE) {
                if (!space) {
                    space = true
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                e.consume()
                return
            }
            val command = if (SystemInfo.isMac) e.isMetaDown else e.isControlDown
            if (command) {
                when (key) {
                    KeyEvent.VK_A -> editor.selectAll()
                    KeyEvent.VK_D -> editor.duplicate()
                    KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> zoomCenter(1.2)
                    KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> zoomCenter(1 / 1.2)
                    KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> fit()
                    else -> return
                }
                e.consume()
                return
            }
            when (key) {
                KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                    editor.deleteSelection()
                    e.consume()
                    return
                }
                KeyEvent.VK_ESCAPE -> {
                    if (drag != null) {
                        drag = null
                        repaint()
                    } else if (tool != null) {
                        setTool(null)
                    } else {
                        s.selection.clear()
                        editor.renderProps()
                        repaint()
                    }
                    editor.renderStatus()
                    e.consume()
                    return
                }
                KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                    val step = if (e.isShiftDown) Geo.GRID else 1.0
                    val dx = if (key == KeyEvent.VK_LEFT) -step else if (key == KeyEvent.VK_RIGHT) step else 0.0
                    val dy = if (key == KeyEvent.VK_UP) -step else if (key == KeyEvent.VK_DOWN) step else 0.0
                    if (s.nudge(dx, dy)) e.consume()
                    return
                }
                KeyEvent.VK_ENTER, KeyEvent.VK_F2 -> {
                    if (s.selection.size == 1) {
                        editor.startInlineEdit(s.selection.first())
                        e.consume()
                    }
                    return
                }
                KeyEvent.VK_F -> {
                    if (e.isAltDown || e.isControlDown || e.isMetaDown) return
                    fit()
                    e.consume()
                    return
                }
                KeyEvent.VK_V -> {
                    if (e.isAltDown || e.isControlDown || e.isMetaDown) return
                    setTool(null)
                    e.consume()
                    return
                }
            }
            if (e.isAltDown || e.isControlDown || e.isMetaDown) return
            if (key in KeyEvent.VK_A..KeyEvent.VK_Z) {
                val t = Tools.byKey('a' + (key - KeyEvent.VK_A)) ?: return
                setTool(t.id, e.isShiftDown)
                e.consume()
            }
        }

        override fun keyReleased(e: KeyEvent) {
            if (e.keyCode == KeyEvent.VK_SPACE) {
                space = false
                cursor = if (tool != null) Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) else null
                e.consume()
            }
        }
    }

    companion object {
        /** Prefix of the text a toolbox item drags onto the canvas. */
        const val TOOL_PREFIX = "fsm-editor-tool:"

        private val RESIZABLE = setOf(VertexType.State, VertexType.Comment, VertexType.Fork, VertexType.Join)
    }
}
