package com.vincegosoftware.fsmeditor.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RectD(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h

    fun contains(p: PointD): Boolean = p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h

    companion object {
        fun fromPoints(a: PointD, b: PointD) = RectD(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
    }
}

/** The area of a region inside its composite state. */
class RegionRect(val id: String, val name: String, val rect: RectD)

enum class TextAnchor { Start, Middle, End }

class LabelPlacement(val x: Double, val y: Double, val anchor: TextAnchor)

/** Point halfway along a polyline, with the direction of the segment it is on. */
class PolylineMidpoint(val point: PointD, val dx: Double, val dy: Double)

/**
 * Diagram geometry shared by the canvas, the SVG export and the XMI writer
 * (region shapes): compartments, regions, borders, routes and labels.
 */
object Geometry {
    const val GRID = 10.0
    const val NAME_HEIGHT = 24.0
    const val LINE_HEIGHT = 14.0

    fun snap(n: Double): Double = Num.round(n / GRID) * GRID

    fun clamp(n: Double, min: Double, max: Double): Double = max(min, min(max, n))

    /** Height of the name compartment plus the internal activities compartment. */
    fun headerHeight(model: FsmModel, v: Vertex): Double {
        var h = NAME_HEIGHT + (if (v.stereotype.isNotEmpty()) 12 else 0)
        val n = Labels.activityLines(model, v).size
        if (n > 0) h += n * LINE_HEIGHT + 6
        return h
    }

    fun regionRects(model: FsmModel, v: Vertex): List<RegionRect> {
        val regs = v.regions
        if (regs.isEmpty()) return emptyList()
        val top = v.y + min(headerHeight(model, v), v.h - 20)
        val bh = v.y + v.h - top
        val n = regs.size
        val horizontal = v.regionLayout == RegionLayout.Horizontal
        return regs.mapIndexed { i, r ->
            val rect = if (horizontal) RectD(v.x + i * v.w / n, top, v.w / n, bh) else RectD(v.x, top + i * bh / n, v.w, bh / n)
            RegionRect(r.id, r.name, rect)
        }
    }

    /** Deepest region under [p], ignoring the states in [exclude]. */
    fun regionAt(ix: ModelIndex, p: PointD, exclude: Set<String>): RegionRect? {
        val states = ix.model.vertices
            .filter { it.type == VertexType.State && it.regions.isNotEmpty() && it.id !in exclude }
            .sortedByDescending { ix.depth(it) }
        for (s in states) {
            for (r in regionRects(ix.model, s)) {
                if (r.rect.contains(p)) return r
            }
        }
        return null
    }

    /** Deepest state under [p] (with a small margin around its border). */
    fun stateAt(ix: ModelIndex, p: PointD, exclude: Set<String>): Vertex? =
        ix.model.vertices
            .filter { it.type == VertexType.State && it.id !in exclude && RectD(it.x - 6, it.y - 6, it.w + 12, it.h + 12).contains(p) }
            .sortedByDescending { ix.depth(it) }
            .firstOrNull()

    /** Nearest point on the border of [s]. */
    fun borderPoint(s: Vertex, p: PointD): PointD {
        val x = clamp(p.x, s.x, s.x + s.w)
        val y = clamp(p.y, s.y, s.y + s.h)
        val d0 = abs(x - s.x)
        val d1 = abs(s.x + s.w - x)
        val d2 = abs(y - s.y)
        val d3 = abs(s.y + s.h - y)
        val m = min(min(d0, d1), min(d2, d3))
        if (m == d0) return PointD(s.x, y)
        if (m == d1) return PointD(s.x + s.w, y)
        if (m == d2) return PointD(x, s.y)
        return PointD(x, s.y + s.h)
    }

    /** Centers the connection point [cp] on the border of [s], nearest to [p]. */
    fun snapToBorder(cp: Vertex, s: Vertex, p: PointD) {
        val b = borderPoint(s, p)
        cp.x = b.x - cp.w / 2
        cp.y = b.y - cp.h / 2
    }

    private enum class ShapeKind { Rect, Diamond, Circle }

    private fun kindOf(v: Vertex): ShapeKind = when (v.type) {
        VertexType.State, VertexType.Comment, VertexType.Fork, VertexType.Join -> ShapeKind.Rect
        VertexType.Choice -> ShapeKind.Diamond
        else -> ShapeKind.Circle
    }

    /** Point where the ray from the center of [v] towards [p] leaves its outline. */
    fun clip(v: Vertex, p: PointD): PointD {
        val c = v.center
        val dx = p.x - c.x
        val dy = p.y - c.y
        if (dx == 0.0 && dy == 0.0) return c
        return when (kindOf(v)) {
            ShapeKind.Circle -> {
                val d = sqrt(dx * dx + dy * dy)
                val r = min(v.w, v.h) / 2
                PointD(c.x + dx / d * r, c.y + dy / d * r)
            }
            ShapeKind.Diamond -> {
                val t = 1 / (abs(dx) / (v.w / 2) + abs(dy) / (v.h / 2))
                PointD(c.x + dx * t, c.y + dy * t)
            }
            ShapeKind.Rect -> {
                val tx = if (dx != 0.0) v.w / 2 / abs(dx) else Double.POSITIVE_INFINITY
                val ty = if (dy != 0.0) v.h / 2 / abs(dy) else Double.POSITIVE_INFINITY
                val t = min(tx, ty)
                PointD(c.x + dx * t, c.y + dy * t)
            }
        }
    }

    /** The polyline a transition is drawn along, or null when an end is missing. */
    fun route(ix: ModelIndex, t: Transition): List<PointD>? {
        val s = ix.vertex(t.source) ?: return null
        val g = ix.vertex(t.target) ?: return null
        val pts = t.points
        if (s === g && pts.isEmpty()) {
            // Self transition: a loop over the top-right corner.
            val gap = max(18.0, min(30.0, s.w / 3))
            val a = PointD(s.x + s.w * 0.72, s.y - gap)
            val b = PointD(s.x + s.w + gap, s.y - gap)
            val c = PointD(s.x + s.w + gap, s.y + min(s.h * 0.3, 22.0))
            val start0 = if (s.type == VertexType.State) PointD(a.x, s.y) else clip(s, a)
            val end0 = if (s.type == VertexType.State) PointD(s.x + s.w, c.y) else clip(s, c)
            return listOf(start0, a, b, c, end0)
        }
        val first = pts.firstOrNull()
        val last = pts.lastOrNull()
        val start: PointD
        val end: PointD
        if (ix.isInside(g, s.id)) {
            start = borderPoint(s, first ?: g.center)
            end = clip(g, last ?: start)
        } else if (ix.isInside(s, g.id)) {
            end = borderPoint(g, last ?: s.center)
            start = clip(s, first ?: end)
        } else {
            start = clip(s, first ?: g.center)
            end = clip(g, last ?: s.center)
        }
        return listOf(start) + pts + listOf(end)
    }

    /** Point halfway along the polyline, with the direction of the segment it is on. */
    fun polylineMid(pts: List<PointD>): PolylineMidpoint {
        var total = 0.0
        for (i in 1 until pts.size) total += dist(pts[i - 1], pts[i])
        var half = total / 2
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = dist(a, b)
            if (len >= half && len > 0) {
                val k = half / len
                return PolylineMidpoint(PointD(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k), b.x - a.x, b.y - a.y)
            }
            half -= len
        }
        return PolylineMidpoint(pts[0], 1.0, 0.0)
    }

    fun dist(a: PointD, b: PointD): Double = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))

    /** Label position and alignment: beside the middle of the line, above it or to its left. */
    fun labelPos(t: Transition, pts: List<PointD>): LabelPlacement {
        val mid = polylineMid(pts)
        val m = mid.point
        val off = t.labelOffset ?: PointD(0.0, 0.0)
        var len = sqrt(mid.dx * mid.dx + mid.dy * mid.dy)
        if (len == 0.0) len = 1.0
        var nx = -mid.dy / len
        var ny = mid.dx / len
        if (ny > 0.2 || (abs(ny) <= 0.2 && nx > 0)) {
            nx = -nx
            ny = -ny
        }
        val anchor = if (nx < -0.5) TextAnchor.End else if (nx > 0.5) TextAnchor.Start else TextAnchor.Middle
        return LabelPlacement(
            m.x + nx * 6 + off.x,
            m.y + ny * 6 + off.y + (if (ny < -0.5) -2 else 4),
            anchor,
        )
    }

    fun distToSegment(p: PointD, a: PointD, b: PointD): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 != 0.0) clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / l2, 0.0, 1.0) else 0.0
        return dist(p, PointD(a.x + t * dx, a.y + t * dy))
    }

    /** Estimated width of a transition label in the 11px diagram font. */
    fun estimateLabelWidth(label: String): Double = label.length * 6.6

    /** Bounds of everything drawn, including captions and transition labels. */
    fun contentBounds(ix: ModelIndex, labelWidth: (String) -> Double = ::estimateLabelWidth): RectD {
        val model = ix.model
        if (model.vertices.isEmpty()) return RectD(0.0, 0.0, 400.0, 300.0)
        var x1 = Double.POSITIVE_INFINITY
        var y1 = Double.POSITIVE_INFINITY
        var x2 = Double.NEGATIVE_INFINITY
        var y2 = Double.NEGATIVE_INFINITY
        fun add(x: Double, y: Double) {
            x1 = min(x1, x)
            y1 = min(y1, y)
            x2 = max(x2, x)
            y2 = max(y2, y)
        }
        for (v in model.vertices) {
            add(v.x, v.y)
            val caption = v.invariant.isNotEmpty() || (v.name.isNotEmpty() && v.type != VertexType.State)
            add(v.x + v.w, v.y + v.h + if (caption) 16 else 0)
        }
        for (t in model.transitions) {
            val pts = route(ix, t) ?: continue
            for (p in pts) add(p.x, p.y)
            val label = Labels.transitionLabel(model, t)
            if (label.isNotEmpty()) {
                val lp = labelPos(t, pts)
                val w = labelWidth(label)
                val x0 = when (lp.anchor) {
                    TextAnchor.End -> lp.x - w
                    TextAnchor.Start -> lp.x
                    TextAnchor.Middle -> lp.x - w / 2
                }
                add(x0, lp.y - 12)
                add(x0 + w, lp.y + 4)
            }
        }
        return RectD(x1, y1, x2 - x1, y2 - y1)
    }

    /** Vertices in drawing order: outer ones first, border points above their state. */
    fun drawingOrder(ix: ModelIndex): List<Vertex> =
        ix.model.vertices
            .mapIndexed { i, v -> Triple(v, i, ix.depth(v) + if (ix.isBorderVertex(v)) 0.5 else 0.0) }
            .sortedWith(compareBy<Triple<Vertex, Int, Double>> { it.third }.thenBy { it.second })
            .map { it.first }

    /** Whether a transition is drawn as a line (internal transitions are listed in the state instead). */
    fun isDrawn(ix: ModelIndex, t: Transition): Boolean =
        !(t.kind == TransitionKind.Internal && t.source == t.target && ix.vertex(t.source)?.type == VertexType.State)
}
