package com.vincegosoftware.fsmeditor.editor

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/** Line icons of the toolbox and toolbar (those of the VS Code edition), drawn on a 24×24 grid in the current text color. */
object ToolIcons {
    private enum class Paint { Stroke, Fill, Hole, Faint, Dashed, Heavy }

    private class Part(val paint: Paint, val shape: Shape)

    private fun s(path: String) = Part(Paint.Stroke, parsePath(path))
    private fun rect(x: Double, y: Double, w: Double, h: Double, r: Double, p: Paint = Paint.Stroke) =
        Part(p, if (r > 0) RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2) else Rectangle2D.Double(x, y, w, h))
    private fun circle(cx: Double, cy: Double, r: Double, p: Paint = Paint.Stroke) = Part(p, Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, p: Paint = Paint.Stroke) = Part(p, Line2D.Double(x1, y1, x2, y2))

    private val parts: Map<String, List<Part>> = mapOf(
        "state" to listOf(rect(3.0, 6.0, 18.0, 12.0, 4.0)),
        "composite" to listOf(rect(2.0, 3.0, 20.0, 18.0, 4.0), line(2.0, 8.0, 22.0, 8.0), rect(6.0, 11.0, 7.0, 6.0, 2.0)),
        "orthogonal" to listOf(rect(2.0, 3.0, 20.0, 18.0, 4.0), line(2.0, 8.0, 22.0, 8.0), line(2.0, 14.5, 22.0, 14.5, Paint.Dashed)),
        "submachine" to listOf(rect(2.0, 5.0, 20.0, 14.0, 4.0), rect(11.0, 12.0, 4.0, 3.0, 1.0), rect(17.0, 12.0, 3.0, 3.0, 1.0), line(15.0, 13.5, 17.0, 13.5)),
        "final" to listOf(circle(12.0, 12.0, 8.0), circle(12.0, 12.0, 4.5, Paint.Fill)),
        "initial" to listOf(circle(12.0, 12.0, 5.5, Paint.Fill)),
        "shallowHistory" to listOf(circle(12.0, 12.0, 8.5), s("M9,8 v8 M15,8 v8 M9,12 h6")),
        "deepHistory" to listOf(circle(12.0, 12.0, 8.5), s("M7.5,8 v8 M12.5,8 v8 M7.5,12 h5 M16.5,8.5 v4 M14.7,9.5 l3.6,2 M18.3,9.5 l-3.6,2")),
        "choice" to listOf(s("M12,4 l8,8 l-8,8 l-8,-8 z")),
        "junction" to listOf(circle(12.0, 12.0, 4.5, Paint.Fill)),
        "fork" to listOf(line(12.0, 2.0, 12.0, 9.0), rect(3.0, 9.0, 18.0, 3.5, 0.0, Paint.Fill), line(7.0, 12.5, 7.0, 21.0), line(17.0, 12.5, 17.0, 21.0)),
        "join" to listOf(line(7.0, 2.0, 7.0, 11.0), line(17.0, 2.0, 17.0, 11.0), rect(3.0, 11.0, 18.0, 3.5, 0.0, Paint.Fill), line(12.0, 14.5, 12.0, 22.0)),
        "entryPoint" to listOf(Part(Paint.Faint, parsePath("M2,12 h5 M17,12 h5")), circle(12.0, 12.0, 5.0)),
        "exitPoint" to listOf(Part(Paint.Faint, parsePath("M2,12 h5 M17,12 h5")), circle(12.0, 12.0, 5.0), s("M9.2,9.2 l5.6,5.6 M14.8,9.2 l-5.6,5.6")),
        "terminate" to listOf(Part(Paint.Heavy, parsePath("M6,6 l12,12 M18,6 L6,18"))),
        "connectionPointRef" to listOf(
            rect(2.0, 5.0, 14.0, 14.0, 3.5), circle(16.0, 9.0, 3.0, Paint.Hole), circle(16.0, 16.0, 3.0, Paint.Hole),
            s("M14.2,14.2 l3.6,3.6 M17.8,14.2 l-3.6,3.6"),
        ),
        "transition" to listOf(s("M3,18 L20,5"), s("M13,5 h7 v7")),
        "region" to listOf(rect(2.0, 3.0, 20.0, 18.0, 4.0), line(2.0, 12.0, 22.0, 12.0, Paint.Dashed), s("M12,15 v4 M10,17 h4")),
        "comment" to listOf(s("M4,3 h11 l5,5 v13 H4 z"), s("M15,3 v5 h5"), s("M7,12 h9 M7,16 h7")),
        "undo" to listOf(s("M9,14 L4,9 l5,-5"), s("M4,9 H15"), Part(Paint.Stroke, Arc2D.Double(10.0, 9.0, 10.0, 10.0, 90.0, -180.0, Arc2D.OPEN)), s("M15,19 H12")),
        "redo" to listOf(s("M15,14 l5,-5 l-5,-5"), s("M20,9 H9"), Part(Paint.Stroke, Arc2D.Double(4.0, 9.0, 10.0, 10.0, 90.0, 180.0, Arc2D.OPEN)), s("M9,19 H12")),
        "zoomIn" to listOf(circle(11.0, 11.0, 7.0), s("M21,21 l-5,-5 M8,11 h6 M11,8 v6")),
        "zoomOut" to listOf(circle(11.0, 11.0, 7.0), s("M21,21 l-5,-5 M8,11 h6")),
        "fit" to listOf(s("M4,9 V4 h5 M20,9 V4 h-5 M4,15 v5 h5 M20,15 v5 h-5")),
        "export" to listOf(s("M12,3 v12 M7,8 l5,-5 l5,5"), s("M5,14 v6 h14 v-6")),
        "xmi" to listOf(s("M8,4 C6,4 6,6 6,8 C6,10 4,12 4,12 C4,12 6,12 6,16 C6,20 6,20 8,20 M16,4 C18,4 18,6 18,8 C18,10 20,12 20,12 C20,12 18,12 18,16 C18,20 18,20 16,20")),
        "code" to listOf(s("M8,7 l-5,5 l5,5 M16,7 l5,5 l-5,5 M14,4 l-4,16")),
        "trace" to listOf(s("M3,12 h4 l3,-7 l4,14 l3,-7 h4")),
        // The Trace Player's First, Previous and Next record (after VS Code's Restart, Step Back and Step Over).
        "first" to listOf(Part(Paint.Stroke, Arc2D.Double(4.0, 4.0, 16.0, 16.0, 135.0, -315.0, Arc2D.OPEN)), s("M6,2 v4.5 h4.5")),
        "stepBack" to listOf(Part(Paint.Stroke, Arc2D.Double(5.0, 6.0, 14.0, 14.0, 0.0, 180.0, Arc2D.OPEN)), s("M2,10 l3,3 l3,-3"), circle(12.0, 19.0, 2.0, Paint.Fill)),
        "stepOver" to listOf(Part(Paint.Stroke, Arc2D.Double(5.0, 6.0, 14.0, 14.0, 0.0, 180.0, Arc2D.OPEN)), s("M16,10 l3,3 l3,-3"), circle(12.0, 19.0, 2.0, Paint.Fill)),
        "trash" to listOf(s("M4,7 h16 M9,7 V4 h6 v3 M6,7 l1,13 h10 l1,-13")),
        "toolbox" to listOf(rect(4.0, 4.0, 7.0, 7.0, 1.5), rect(13.0, 4.0, 7.0, 7.0, 1.5), rect(4.0, 13.0, 7.0, 7.0, 1.5), circle(16.5, 16.5, 3.5)),
        "properties" to listOf(s("M4,6 h16 M4,12 h16 M4,18 h10"), circle(8.0, 6.0, 1.6, Paint.Fill), circle(15.0, 12.0, 1.6, Paint.Fill)),
    )

    /** The icon [name] (a tool id or a toolbar action), or null when unknown. */
    fun get(name: String, size: Int = 18): Icon? {
        val list = parts[name] ?: return null
        return ToolIcon(list, size)
    }

    private class ToolIcon(private val list: List<Part>, private val size: Int) : Icon {
        override fun getIconWidth() = JBUI.scale(size)
        override fun getIconHeight() = JBUI.scale(size)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.translate(x, y)
                val k = iconWidth / 24.0
                g2.scale(k, k)
                val fg = c?.foreground ?: UIUtil.getLabelForeground()
                val bg = c?.background ?: UIUtil.getPanelBackground()
                val faint = Color(fg.red, fg.green, fg.blue, 128)
                fun pen(w: Float, dashed: Boolean = false) =
                    if (dashed) BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(2.5f, 2f), 0f)
                    else BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                for (p in list) {
                    when (p.paint) {
                        Paint.Fill -> {
                            g2.color = fg
                            g2.fill(p.shape)
                        }
                        Paint.Hole -> {
                            g2.color = bg
                            g2.fill(p.shape)
                            g2.color = fg
                            g2.stroke = pen(1.5f)
                            g2.draw(p.shape)
                        }
                        Paint.Faint -> {
                            g2.color = faint
                            g2.stroke = pen(1.5f)
                            g2.draw(p.shape)
                        }
                        Paint.Dashed -> {
                            g2.color = fg
                            g2.stroke = pen(1.5f, true)
                            g2.draw(p.shape)
                        }
                        Paint.Heavy -> {
                            g2.color = fg
                            g2.stroke = pen(2f)
                            g2.draw(p.shape)
                        }
                        Paint.Stroke -> {
                            g2.color = fg
                            g2.stroke = pen(1.5f)
                            g2.draw(p.shape)
                        }
                    }
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** Parses the SVG path commands the icons use: M, L, H, V, C, Z and their relative forms. */
    private fun parsePath(d: String): Shape {
        val path = Path2D.Double()
        val tokens = Regex("[MLHVCZmlhvcz]|-?[0-9]*\\.?[0-9]+").findAll(d).map { it.value }.toList()
        var i = 0
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var cmd = 'M'
        fun num() = tokens[i++].toDouble()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.closePath()
                    x = startX
                    y = startY
                    continue
                }
            }
            when (cmd) {
                'M', 'm' -> {
                    val nx = num()
                    val ny = num()
                    x = if (cmd == 'm') x + nx else nx
                    y = if (cmd == 'm') y + ny else ny
                    path.moveTo(x, y)
                    startX = x
                    startY = y
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val nx = num()
                    val ny = num()
                    x = if (cmd == 'l') x + nx else nx
                    y = if (cmd == 'l') y + ny else ny
                    path.lineTo(x, y)
                }
                'H', 'h' -> {
                    val n = num()
                    x = if (cmd == 'h') x + n else n
                    path.lineTo(x, y)
                }
                'V', 'v' -> {
                    val n = num()
                    y = if (cmd == 'v') y + n else n
                    path.lineTo(x, y)
                }
                'C', 'c' -> {
                    val (ox, oy) = if (cmd == 'c') x to y else 0.0 to 0.0
                    val c = DoubleArray(6) { num() }
                    path.curveTo(ox + c[0], oy + c[1], ox + c[2], oy + c[3], ox + c[4], oy + c[5])
                    x = ox + c[4]
                    y = oy + c[5]
                }
                else -> i++
            }
        }
        return path
    }
}
