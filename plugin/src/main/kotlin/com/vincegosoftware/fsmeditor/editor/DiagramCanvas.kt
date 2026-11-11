package com.vincegosoftware.fsmeditor.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.JBFont
import com.vincegosoftware.fsmeditor.core.DiagramSession
import com.vincegosoftware.fsmeditor.core.ElementColors
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
import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.SwingUtilities
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
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
        Geo.textMeasure = ::labelWidth
        isFocusable = true
        isOpaque = true
        val mouse = Mouse()
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        if (SystemInfo.isMac && !GraphicsEnvironment.isHeadless()) installPinchZoom()
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

    /**
     * macOS delivers a trackpad pinch as a magnification gesture, through Apple's
     * `com.apple.eawt.event` API (opened to plugins by the IDE launcher). It is called by
     * reflection, since the classes only exist in the macOS JDK. Consuming the event keeps it
     * from the IDE frame's own gesture listener.
     */
    private fun installPinchZoom() {
        try {
            val pkg = "com.apple.eawt.event"
            val listenerClass = Class.forName("$pkg.MagnificationListener")
            val eventClass = Class.forName("$pkg.MagnificationEvent")
            val magnification = eventClass.getMethod("getMagnification")
            val consume = eventClass.getMethod("consume")
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "magnify" -> {
                        val event = args[0]
                        consume.invoke(event)
                        val p = mousePosition ?: Point(width / 2, height / 2)
                        zoomAt(exp(magnification.invoke(event) as Double), p.x.toDouble(), p.y.toDouble())
                        null
                    }
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "FSM diagram pinch zoom"
                    else -> null
                }
            }
            Class.forName("$pkg.GestureUtilities")
                .getMethod("addGestureListenerTo", JComponent::class.java, Class.forName("$pkg.GestureListener"))
                .invoke(null, this, listener)
        } catch (e: Exception) {
            // Missing, or not opened to plugins (InaccessibleObjectException): Ctrl+wheel still zooms.
        }
    }

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

    /** While true, painting draws the diagram for an image: export colors, no selection, problems or drag feedback. */
    private var exporting = false

    private val th: Theme get() = if (exporting) Theme.export else Theme.current

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
            editor.refreshLayoutIssues()
            labels.clear()
            g.translate(viewX, viewY)
            g.scale(zoom, zoom)
            drawDiagram(g, s)
            drawOverlay(g, s)
        } finally {
            g.dispose()
        }
    }

    private fun drawDiagram(g: Graphics2D, s: DiagramSession) {
        val sorted = Geo.drawingOrder(s.index)
        for (v in sorted) drawVertex(g, s, v)
        for (v in sorted.filter { it.type == VertexType.Comment }) drawAnchors(g, s, v)
        for (t in s.model.transitions) drawTransition(g, s, t)
    }

    /**
     * The diagram as an image, cropped to its content with a margin like the SVG export and in its colors,
     * at [scale] times its size (less when a side would exceed [MAX_IMAGE_SIDE] pixels); null without a diagram.
     */
    fun exportImage(scale: Double): BufferedImage? {
        val s = s ?: return null
        s.reindex()
        val pad = 20.0
        val b = Geo.contentBounds(s.index, ::labelWidth)
        val w = b.w + pad * 2
        val h = b.h + pad * 2
        val k = min(scale, MAX_IMAGE_SIDE / max(w, h))
        val image = BufferedImage(max(1, ceil(w * k).toInt()), max(1, ceil(h * k).toInt()), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        exporting = true
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = th.background
            g.fillRect(0, 0, image.width, image.height)
            g.scale(k, k)
            g.translate(pad - b.x, pad - b.y)
            drawDiagram(g, s)
        } finally {
            exporting = false
            g.dispose()
        }
        return image
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
        if (exporting) return IssueLevel.None
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
        val selected = !exporting && v.id in s.selection
        val issue = issueOf(v.id)
        val dropTarget = !exporting && drag?.dropState == v.id

        // Outline of the element's own shape: its color, then selection, warnings and errors, win in that order.
        val own = Theme.colorOf(v.color)
        var stroke = own ?: th.line
        var shapeWidth = 1.4
        var fill = own ?: th.line
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
        val shapeFill = if (own != null) th.tint(own, th.stateFill) else if (v.type == VertexType.Comment) th.noteFill else th.stateFill
        val textColor = own ?: th.foreground
        val dimText = th.alpha(own ?: th.foreground, 0.8)
        fun itemColor(color: String?) = Theme.colorOf(color) ?: textColor

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
        val sepColor = th.alpha(own ?: th.line, 0.8)
        when (v.type) {
            VertexType.State -> {
                val lines = Geo.activityRowItems(s.model, v)
                val regions = Geo.regionRects(s.model, v)
                val box = RoundRectangle2D.Double(x, y, w, h, 20.0, 20.0)
                if (regions.isNotEmpty()) {
                    // Composite state: its color tints the header, its background color the regions.
                    val head = if (own != null) th.tint(own, th.stateFill) else th.stateFill
                    val bg = Theme.colorOf(ElementColors.background(v.itemColors))
                    val body = if (bg != null) th.tint(bg, th.stateFill) else th.stateFill
                    g.color = head
                    g.fill(box)
                    if (body != head) {
                        val top = regions[0].rect.y
                        val g2 = g.create() as Graphics2D
                        try {
                            g2.clip(Rectangle2D.Double(x, top, w, max(0.0, y + h - top)))
                            g2.color = body
                            g2.fill(box)
                        } finally {
                            g2.dispose()
                        }
                    }
                    outline(box, filled = false)
                } else {
                    outline(box)
                }
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
                        for ((l, lc) in lines) {
                            ly += Geo.LINE_HEIGHT
                            drawText(g, x + 8, ly, l, 11.0, itemColor(lc), TextAnchor.Start)
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
                        if (!exporting && drag?.dropRegion == region.id) {
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
                if (v.invariant.isNotEmpty()) {
                    drawText(g, x + 4, y + h + 14, "{${v.invariant}}", 11.0, itemColor(ElementColors.item(v.color, v.itemColors, "invariant")), TextAnchor.Start, italic = true)
                }
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
                val fm = g.getFontMetrics(font(11.0))
                val lines = Geo.commentLines(v.text, w - 16) { fm.getStringBounds(it, g).width }
                for ((i, line) in lines.withIndex()) {
                    if (16 + i * 14 < h) drawText(g, x + 8, y + 17 + i * 14, line, 11.0, textColor, TextAnchor.Start)
                }
            }
        }
        val caption = if (v.type == VertexType.ConnectionPointRef) s.pointName(ix.vertex(v.parent), v.ref).ifEmpty { "?" } else v.name
        if (caption.isNotEmpty() && v.type != VertexType.State && v.type != VertexType.Comment) {
            val below = if (v.type == VertexType.Fork || v.type == VertexType.Join) y + h + 13 else y + h + 12
            drawText(g, cx, below, caption, 11.0, textColor)
        }
    }

    /** The bend points to drag: the corners of a state's self-transition loop, or the transition's bend points. */
    private fun waypointHandles(s: DiagramSession, t: Transition): List<PointD> =
        if (Geo.isLoop(s.index, t)) Geo.loop(t, s.index.vertex(t.source)!!).corners() else t.points

    private fun drawTransition(g: Graphics2D, s: DiagramSession, t: Transition) {
        val ix = s.index
        if (!Geo.isDrawn(ix, t)) return
        val pts = Geo.route(ix, t) ?: return
        val selected = !exporting && t.id in s.selection
        val color = when (issueOf(t.id)) {
            IssueLevel.Error -> th.error
            IssueLevel.Warning -> th.warning
            IssueLevel.None -> if (selected) th.accent else Theme.colorOf(t.color) ?: th.line
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
        val lines = Labels.transitionLabelItems(s.model, t)
        if (lines.isNotEmpty()) {
            val lp = Geo.labelPos(t, pts)
            var box: Rectangle2D? = null
            for ((i, line) in lines.withIndex()) {
                val text = Theme.colorOf(ElementColors.item(t.color, t.itemColors, line.first)) ?: if (selected) th.accent else th.foreground
                val b = drawText(g, lp.x, Geo.labelLineY(lp, i, lines.size), line.second, 11.0, text, lp.anchor, halo = th.background) ?: continue
                box = box?.createUnion(b) ?: b
            }
            if (box != null && !exporting) labels.add(box to t.id)
        }
        if (selected && s.selection.size == 1) {
            for (p in waypointHandles(s, t)) {
                val dot = Ellipse2D.Double(p.x - 4.5, p.y - 4.5, 9.0, 9.0)
                g.color = th.background
                g.fill(dot)
                g.color = th.accent
                g.stroke = th.stroke(1.5)
                g.draw(dot)
            }
            // Its ends: dragged onto another element, they reconnect it.
            if (!(drag?.kind == DragKind.Reconnect && drag?.transition === t)) {
                for (p in listOf(pts.first(), pts.last())) {
                    val dot = Ellipse2D.Double(p.x - 4.5, p.y - 4.5, 9.0, 9.0)
                    g.color = th.accent
                    g.fill(dot)
                    g.color = th.background
                    g.stroke = th.stroke(1.0)
                    g.draw(dot)
                }
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
        // The groups of the selected elements: a dotted rectangle around their members.
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(0f, 4f), 0f)
        for (id in sel.map { it.group }.filter { it.isNotEmpty() }.distinct()) {
            val b = DiagramSession.unionBox(s.groupMembers(id)) ?: continue
            g.draw(RoundRectangle2D.Double(b.x - 10, b.y - 10, b.w + 20, b.h + 20, 8.0, 8.0))
        }
        if (sel.size == 1 && s.selection.size == 1) {
            val v = sel[0]
            if (v.type in RESIZABLE) {
                for (corner in CORNERS) {
                    val rh = resizeHandle(v, corner)
                    val shape = RoundRectangle2D.Double(rh.x, rh.y, rh.w, rh.h, 3.0, 3.0)
                    g.color = accent
                    g.fill(shape)
                    g.stroke = th.stroke(1.0)
                    g.color = th.background
                    g.draw(shape)
                }
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
        if (d?.kind == DragKind.Reconnect && cur != null) {
            // From the bend point next to the dragged end, or from the element at the other end.
            val t = d.transition!!
            val pts = if (Geo.isLoop(s.index, t)) emptyList() else t.points
            val target = d.hover?.let { s.index.vertex(it) }
            val toward = target?.center ?: cur
            val near = if (d.atSource) pts.firstOrNull() else pts.lastOrNull()
            val fixed = s.index.vertex(if (d.atSource) t.target else t.source)
            val from = near ?: fixed?.let { Geo.clip(it, toward) } ?: toward
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

    private fun resizeHandle(v: Vertex, corner: String) =
        RectD(if ('w' in corner) v.x - 8 else v.x + v.w - 1, if ('n' in corner) v.y - 8 else v.y + v.h - 1, 9.0, 9.0)

    private fun connectHandle(v: Vertex) = PointD(v.x + v.w + 18, v.y + v.h / 2)

    // ---------------------------------------------------------------- hit testing

    private enum class HitKind { Empty, Handle, Waypoint, End, Label, Vertex, Transition }

    /** [atSource]: for an end, its source end rather than its target end. */
    private class Hit(val kind: HitKind, val id: String = "", val handle: String = "", val index: Int = 0, val atSource: Boolean = false)

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
                if (only.type in RESIZABLE) {
                    for (corner in CORNERS) {
                        val rh = resizeHandle(only, corner)
                        if (RectD(rh.x - tolerance, rh.y - tolerance, rh.w + tolerance * 2, rh.h + tolerance * 2).contains(p)) {
                            return Hit(HitKind.Handle, handle = "resize-$corner")
                        }
                    }
                }
            }
            val selected = ix.transition(s.selection.first())
            val route = if (selected != null && Geo.isDrawn(ix, selected)) Geo.route(ix, selected) else null
            if (selected != null && route != null) {
                if (Geo.dist(p, route.last()) <= 4.5 + tolerance) return Hit(HitKind.End, selected.id)
                if (Geo.dist(p, route.first()) <= 4.5 + tolerance) return Hit(HitKind.End, selected.id, atSource = true)
            }
            if (selected != null) {
                for ((i, wp) in waypointHandles(s, selected).withIndex()) {
                    if (Geo.dist(p, wp) <= 4.5 + tolerance) return Hit(HitKind.Waypoint, selected.id, index = i)
                }
            }
        }
        // The lines between regions: dragged, they resize the region before them.
        for (st in s.model.vertices.asReversed()) {
            val rects = Geo.regionRects(s.model, st)
            for (i in 0 until rects.size - 1) {
                val r = rects[i].rect
                val near = if (st.regionLayout == RegionLayout.Horizontal) {
                    abs(p.x - r.right) <= 3 + tolerance && p.y >= r.y && p.y <= r.bottom
                } else {
                    abs(p.y - r.bottom) <= 3 + tolerance && p.x >= r.x && p.x <= r.right
                }
                if (near) return Hit(HitKind.Handle, st.id, handle = "sep", index = i)
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

    /** The topmost state or pseudostate at [p], through transitions and comments. */
    private fun reconnectTargetAt(s: DiagramSession, p: PointD): String? =
        Geo.drawingOrder(s.index).lastOrNull { it.type != VertexType.Comment && contains(it, p) }?.id

    // ---------------------------------------------------------------- mouse

    /**
     * The menu of a right-click on an element or on empty canvas. A right-click
     * on an element selects it unless it is already selected.
     */
    private fun showContextMenu(s: DiagramSession, p: PointD, at: Point) {
        if (tool != null) return
        s.reindex()
        val hit = hitTest(s, p)
        val id = when (hit.kind) {
            HitKind.Vertex, HitKind.Transition, HitKind.Label, HitKind.Waypoint, HitKind.End -> hit.id
            HitKind.Handle -> if (s.selection.size == 1) s.selection.first() else null
            HitKind.Empty -> null
        }
        if (id != null && id !in s.selection) {
            s.selection = linkedSetOf(id)
            editor.renderProps()
            editor.renderStatus()
            repaint()
        }
        fun item(label: String, enabled: Boolean = true, run: () -> Unit) = object : DumbAwareAction(label) {
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled
            }
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val group = DefaultActionGroup()
        val state = id?.let { s.index.vertex(it) }
        if (state != null && state.type == VertexType.State) {
            val canAlign = s.selectionRoots().count { it.type == VertexType.State } >= 2
            for ((label, horizontally) in listOf("Align Horizontally" to true, "Align Vertically" to false)) {
                group.add(item(label, canAlign) {
                    if (s.align(state.id, horizontally)) editor.renderProps()
                    repaint()
                })
            }
            group.addSeparator()
        }
        if (state != null && !s.index.isBorderVertex(state)) {
            group.add(item("Group", s.canGroup) {
                s.group()
                editor.renderProps()
                repaint()
            })
            group.add(item("Ungroup", s.canUngroup) {
                s.ungroup()
                editor.renderProps()
                repaint()
            })
            group.addSeparator()
        }
        if (s.canLeaveParent) {
            group.add(item("Leave Parent") {
                s.leaveParent()
                editor.renderProps()
                repaint()
            })
            group.addSeparator()
        }
        val copyable = s.collectSelection() != null
        group.add(item("Cut", copyable) { editor.copy(true) })
        group.add(item("Copy", copyable) { editor.copy(false) })
        group.add(item("Paste", editor.clipboardHoldsElements) {
            editor.paste(p)
            repaint()
        })
        group.addSeparator()
        if (id != null) {
            group.add(item("Delete") {
                editor.deleteSelection()
                repaint()
            })
            group.addSeparator()
        }
        group.add(item("Properties…") { editor.openPropertiesWindow(id, at) })
        ActionManager.getInstance().createActionPopupMenu("FsmDiagram", group).component.show(this, at.x, at.y)
    }

    private enum class DragKind { Pan, Move, Resize, Region, Connect, Waypoint, Reconnect, Label, Band }

    private class DragState(val kind: DragKind) {
        var start = PointD(0.0, 0.0)
        var screenStart = Point()
        var viewX0 = 0.0
        var viewY0 = 0.0
        var items: List<DiagramSession.MovedItem> = emptyList()
        var transitions: List<DiagramSession.MovedPoints> = emptyList()
        var roots: List<Vertex> = emptyList()
        var moving: Set<String> = emptySet()
        var moved = false
        var clickedId: String? = null
        var dropRegion: String? = null
        var dropState: String? = null
        var vertex: Vertex? = null
        var corner = "se"
        var x0 = 0.0
        var y0 = 0.0
        var w0 = 0.0
        var h0 = 0.0
        var source: Vertex? = null
        var cur: PointD? = null
        var hover: String? = null
        var transition: Transition? = null
        var index = 0
        var atSource = false
        var offset0 = PointD(0.0, 0.0)
        var additive = false
        var base: Set<String> = emptySet()
        /** A right-button pan, which opens the context menu when the pointer doesn't move. */
        var rightButton = false
        /** Move, resize and region drags: the layout at the start, restored at each step. */
        var snapshot: DiagramSession.LayoutSnapshot? = null
        /** Move: the boxes of the roots at the start. */
        var boxes: Map<String, RectD> = emptyMap()
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
                    it.start = p
                    it.rightButton = SwingUtilities.isRightMouseButton(e) && !space
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
            var hit = hitTest(s, p)

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

            // A composite state moves by its title bar: its regions are empty canvas, for a selection band.
            if (hit.kind == HitKind.Vertex && Geo.regionRects(s.model, s.index.vertex(hit.id)!!).any { it.rect.contains(p) }) hit = Hit(HitKind.Empty)

            when (hit.kind) {
                HitKind.Handle -> {
                    val v = if (hit.handle == "sep") null else s.index.vertex(s.selection.first())
                    if (hit.handle == "sep") {
                        val st = s.index.vertex(hit.id)!!
                        drag = DragState(DragKind.Region).also {
                            it.vertex = st
                            it.index = hit.index
                            it.start = p
                            it.snapshot = s.beginLayoutDrag(st)
                        }
                    } else if (v != null) {
                        if (hit.handle.startsWith("resize-")) {
                            drag = DragState(DragKind.Resize).also {
                                it.corner = hit.handle.removePrefix("resize-")
                                it.vertex = v
                                it.start = p
                                it.x0 = v.x
                                it.y0 = v.y
                                it.w0 = v.w
                                it.h0 = v.h
                                it.snapshot = s.beginLayoutDrag(v)
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
                HitKind.End -> drag = DragState(DragKind.Reconnect).also {
                    it.transition = s.index.transition(hit.id)
                    it.atSource = hit.atSource
                    it.cur = p
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
                    if (!d.moved && e.point.distance(d.screenStart) < 3) return
                    d.moved = true
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
                    // From the layout at the start: the regions grown for the previous position shrink back.
                    s.moveFrom(d.snapshot!!, d.items, d.transitions, d.roots, d.boxes, dx, dy, p, e.isAltDown)
                    val rr = if (lead != null && d.roots.all { !ix.isBorderVertex(it) }) s.dropHighlight(lead, d.moving) else null
                    d.dropRegion = rr?.id
                    d.dropState = if (rr != null) ix.regionOwner[rr.id]?.id else null
                }
                DragKind.Resize -> s.resizeFrom(d.snapshot!!, d.vertex!!, d.corner, d.x0, d.y0, d.w0, d.h0, p.x - d.start.x, p.y - d.start.y, e.isAltDown)
                DragKind.Region -> {
                    // The line after region `index`: the region resizes, its state with it, and the later regions move.
                    val st = d.vertex!!
                    s.resizeRegionFrom(d.snapshot!!, st, d.index, if (st.regionLayout == RegionLayout.Horizontal) p.x - d.start.x else p.y - d.start.y, e.isAltDown)
                }
                DragKind.Connect -> {
                    d.cur = p
                    d.hover = connectTargetAt(s, p, d.source!!)
                    if (d.hover != null && s.index.vertex(d.hover) == null) d.hover = null
                }
                DragKind.Reconnect -> {
                    d.cur = p
                    d.hover = reconnectTargetAt(s, p)
                }
                DragKind.Waypoint -> {
                    val q = if (e.isAltDown) p else PointD(Geo.snap(p.x), Geo.snap(p.y))
                    if (Geo.isLoop(s.index, d.transition!!)) s.moveLoopCorner(d.transition!!, d.index, q) else d.transition!!.points[d.index] = q
                }
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
                DragKind.Pan -> {
                    if (d.rightButton && !d.moved) showContextMenu(s, d.start, e.point)
                    repaint()
                }
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
                DragKind.Resize, DragKind.Region, DragKind.Waypoint -> s.commit()
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
                DragKind.Reconnect -> {
                    val target = reconnectTargetAt(s, toWorld(e.point))?.let { s.index.vertex(it) }
                    if (target != null) {
                        s.reconnect(d.transition!!, d.atSource, target)
                        editor.renderProps()
                    }
                    repaint()
                }
                DragKind.Band -> {
                    editor.renderProps()
                    repaint()
                }
            }
            editor.renderStatus()
        }

        private var wheelBurstEnd = 0L
        private var wheelTrackpad = false

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            val rotation = e.preciseWheelRotation
            // The mouse wheel zooms around the pointer and Shift+wheel pans sideways; a trackpad pans in
            // both directions (macOS sends its horizontal scroll as Shift+wheel) and pinches to zoom
            // (Ctrl+wheel on Windows and Linux, installPinchZoom on macOS, where Cmd+wheel zooms since
            // the system keeps Ctrl+scroll for its own zoom). The JDK accumulates a
            // trackpad's sub-notch deltas, so it reports a zero wheelRotation with a non-zero precise
            // rotation, which a mouse wheel never does: one such event makes its burst of events
            // (no gap over 250 ms) a trackpad scroll.
            if (e.`when` > wheelBurstEnd) wheelTrackpad = false
            wheelBurstEnd = e.`when` + 250
            if (e.wheelRotation == 0 && rotation != 0.0) wheelTrackpad = true
            when {
                (if (SystemInfo.isMac) e.isMetaDown else e.isControlDown) -> zoomAt(exp(-rotation * 0.216), e.x.toDouble(), e.y.toDouble())
                wheelTrackpad -> {
                    // macOS reports a tenth of the pixels; Windows 1/120 of a wheel delta.
                    val d = rotation * if (SystemInfo.isMac) 10.0 else 100.0
                    if (e.isShiftDown) setView(viewX - d, viewY, zoom) else setView(viewX, viewY - d, zoom)
                }
                e.isShiftDown -> setView(viewX - rotation * 30, viewY, zoom)
                else -> zoomAt(exp(-rotation * 0.216), e.x.toDouble(), e.y.toDouble())
            }
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
        // The selected elements and the other members of their groups, which move with them.
        val picked = s.withGroupMates(s.selection.mapNotNull { ix.vertex(it) })
        val pickedIds = picked.map { it.id }.toSet()
        for (v in picked) {
            if (ix.ancestors(v).any { it.id in pickedIds }) continue
            roots.add(v)
            moving[v.id] = v
            for (d in ix.descendants(v)) moving[d.id] = d
        }
        drag = DragState(DragKind.Move).also { d ->
            d.start = p
            d.items = moving.values.map { DiagramSession.MovedItem(it, it.x, it.y) }
            d.transitions = s.model.transitions
                .filter { it.points.isNotEmpty() && it.source in moving && it.target in moving }
                .map { DiagramSession.MovedPoints(it, it.points.toList()) }
            d.roots = roots
            d.moving = moving.keys.toSet()
            d.clickedId = clickedId
            d.snapshot = s.snapshot()
            d.boxes = roots.associate { it.id to RectD(it.x, it.y, it.w, it.h) }
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
            HitKind.Handle -> Cursor.getPredefinedCursor(when (hit.handle) {
                "sep" -> if (s.index.vertex(hit.id)?.regionLayout == RegionLayout.Horizontal) Cursor.E_RESIZE_CURSOR else Cursor.S_RESIZE_CURSOR
                "resize-nw" -> Cursor.NW_RESIZE_CURSOR
                "resize-ne" -> Cursor.NE_RESIZE_CURSOR
                "resize-sw" -> Cursor.SW_RESIZE_CURSOR
                "resize-se" -> Cursor.SE_RESIZE_CURSOR
                else -> Cursor.CROSSHAIR_CURSOR
            })
            HitKind.End -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
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
            HitKind.Handle, HitKind.End -> {}
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
                        drag?.snapshot?.let { s.restore(it) }
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
                    // Arrow keys don't move elements: elements move by dragging, which keeps them in their regions.
                    e.consume()
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
                KeyEvent.VK_G -> {
                    if (e.isAltDown || e.isControlDown || e.isMetaDown || e.isShiftDown) return
                    s.toggleGroup()
                    editor.renderProps()
                    repaint()
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

        /** Largest side of an exported image, in pixels. */
        const val MAX_IMAGE_SIDE = 16384.0

        private val CORNERS = listOf("nw", "ne", "sw", "se")
        private val RESIZABLE = setOf(VertexType.State, VertexType.Comment, VertexType.Fork, VertexType.Join)
    }
}
