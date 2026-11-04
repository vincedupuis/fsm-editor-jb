package com.vincegosoftware.fsmeditor.core

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Standalone SVG drawing of a diagram, cropped to its content, in a light
 * theme whatever the editor theme. It draws the same shapes as the canvas.
 */
object SvgExport {
    private const val CSS = """
text{font-family:-apple-system,"Segoe UI",Helvetica,Arial,sans-serif;fill:#1f1f1f;font-size:12px}
.shape{fill:#fdfdfd;stroke:#333;stroke-width:1.4}
.fill{fill:#333}
.stroke{fill:none;stroke:#333;stroke-width:1.6}
.sep{stroke:#333;stroke-width:1;opacity:.8}
.sep.dashed{stroke-dasharray:7 4}
.name{font-weight:600}
.activity,.tlabel{font-size:11px}
.stereo,.region-name{font-size:10px;font-style:italic;opacity:.8}
.invariant{font-size:11px;font-style:italic}
.glyph{font-size:11px;font-weight:700}
.v-comment .shape{fill:#fbf3cf}
.v-comment text{font-size:11px}
.hit{fill:transparent}
.anchor{fill:none;stroke:#333;stroke-width:1;stroke-dasharray:4 3;opacity:.7}
.line,.arrow{fill:none;stroke:#333;stroke-width:1.3;stroke-linejoin:round}
.tlabel{paint-order:stroke;stroke:#fff;stroke-width:4px;stroke-linejoin:round}"""

    /** Fill of a state (`.shape` in the style sheet). */
    private const val STATE_FILL = "#fdfdfd"

    fun export(session: DiagramSession): String {
        session.reindex()
        val ix = session.index
        val model = session.model
        val b = Geometry.contentBounds(ix)
        val pad = 20.0
        val x = Num.r1(b.x - pad)
        val y = Num.r1(b.y - pad)
        val w = Num.r1(b.w + pad * 2)
        val h = Num.r1(b.h + pad * 2)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${f(x)} ${f(y)} ${f(w)} ${f(h)}\" width=\"${f(ceil(b.w + pad * 2))}\" height=\"${f(ceil(b.h + pad * 2))}\">\n")
        sb.append("<title>${esc(model.name.ifEmpty { "State machine" })}</title>\n")
        sb.append("<style>$CSS</style>\n")
        sb.append("<rect x=\"${f(x)}\" y=\"${f(y)}\" width=\"${f(w)}\" height=\"${f(h)}\" fill=\"#fff\"/>\n")
        val sorted = Geometry.drawingOrder(ix)
        var fills = 0
        for (v in sorted) vertexShape(sb, session, v) { ++fills }
        for (v in sorted.filter { it.type == VertexType.Comment }) anchors(sb, ix, v)
        for (t in model.transitions) transitionShape(sb, ix, model, t)
        sb.append("\n</svg>\n")
        return sb.toString()
    }

    private fun f(n: Double) = Num.format(n)
    private fun f1(n: Double) = Num.format(Num.r1(n))

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun text(x: Double, y: Double, cls: String, text: String, anchor: String = "middle", color: String? = null) =
        "<text class=\"$cls\" x=\"${f1(x)}\" y=\"${f1(y)}\" text-anchor=\"$anchor\"${style(color?.let { "fill:$it" })}>${esc(text)}</text>"

    /** A style attribute with the given declarations (nulls skipped), or nothing. */
    private fun style(vararg decls: String?): String {
        val d = decls.filterNot { it.isNullOrEmpty() }.joinToString(";")
        return if (d.isNotEmpty()) " style=\"$d\"" else ""
    }

    private fun cross(cx: Double, cy: Double, k: Double, style: String) =
        "<path class=\"stroke\" d=\"M${f(cx - k)},${f(cy - k)} L${f(cx + k)},${f(cy + k)} M${f(cx + k)},${f(cy - k)} L${f(cx - k)},${f(cy + k)}\"$style/>"

    /** Draws a vertex; [nextFill] numbers the fill gradients of composite states. */
    private fun vertexShape(sb: StringBuilder, session: DiagramSession, v: Vertex, nextFill: () -> Int) {
        val model = session.model
        val x = v.x
        val y = v.y
        val w = v.w
        val h = v.h
        val cx = x + w / 2
        val cy = y + h / 2
        val r = min(w, h) / 2
        sb.append("<g class=\"vertex v-${v.type.key}\">")
        val c = ElementColors.of(v.color)
        val shapeSt = if (c == null) "" else style("fill:" + ElementColors.tint(c), "stroke:$c")
        val fillSt = if (c == null) "" else style("fill:$c")
        fun strokeSt(width: String?) = style(c?.let { "stroke:$it" }, width?.let { "stroke-width:$it" })
        val sepSt = if (c == null) "" else style("stroke:$c")
        when (v.type) {
            VertexType.State -> {
                val lines = Geometry.activityRowItems(model, v)
                val regions = Geometry.regionRects(model, v)
                var st = shapeSt
                if (regions.isNotEmpty()) {
                    // Composite state: its color tints the header, its background color the regions.
                    val bg = ElementColors.background(v.itemColors)
                    val head = if (c != null) ElementColors.tint(c) else STATE_FILL
                    val body = if (bg != null) ElementColors.tint(bg) else STATE_FILL
                    if (head != body) {
                        val id = "fsm-fill-${nextFill()}"
                        val at = f(Num.round((regions[0].rect.y - y) / h * 10000) / 10000)
                        sb.append("<defs><linearGradient id=\"$id\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
                        sb.append("<stop offset=\"$at\" style=\"stop-color:$head\"/><stop offset=\"$at\" style=\"stop-color:$body\"/></linearGradient></defs>")
                        st = style("fill:url(#$id)", c?.let { "stroke:$it" })
                    }
                }
                sb.append("<rect class=\"shape\" x=\"${f(x)}\" y=\"${f(y)}\" width=\"${f(w)}\" height=\"${f(h)}\" rx=\"10\"$st/>")
                val title = v.name + if (v.submachine.isNotEmpty()) " : " + session.machineLabel(v.submachine) else ""
                val centered = regions.isEmpty() && lines.isEmpty()
                var ty = y + 16
                val stereo = v.stereotype.isNotEmpty()
                if (stereo) {
                    sb.append(text(cx, y + if (centered) h / 2 - 4 else 13.0, "stereo", "«${v.stereotype}»", "middle", c))
                    ty += 12
                }
                if (centered) {
                    sb.append(text(cx, y + h / 2 + if (stereo) 10 else 4, "name", title, "middle", c))
                } else {
                    sb.append(text(cx, ty, "name", title, "middle", c))
                    var ly = y + Geometry.NAME_HEIGHT + if (stereo) 12 else 0
                    if (lines.isNotEmpty()) {
                        sb.append("<line class=\"sep\" x1=\"${f(x)}\" y1=\"${f(ly)}\" x2=\"${f(x + w)}\" y2=\"${f(ly)}\"$sepSt/>")
                        for ((l, lc) in lines) {
                            ly += Geometry.LINE_HEIGHT
                            sb.append(text(x + 8, ly, "activity", l, "start", lc))
                        }
                    }
                }
                if (regions.isNotEmpty()) {
                    val top = regions[0].rect.y
                    sb.append("<line class=\"sep\" x1=\"${f(x)}\" y1=\"${f1(top)}\" x2=\"${f(x + w)}\" y2=\"${f1(top)}\"$sepSt/>")
                    for ((i, region) in regions.withIndex()) {
                        val rr = region.rect
                        if (i > 0) {
                            sb.append(
                                if (v.regionLayout == RegionLayout.Horizontal) "<line class=\"sep dashed\" x1=\"${f1(rr.x)}\" y1=\"${f1(rr.y)}\" x2=\"${f1(rr.x)}\" y2=\"${f1(rr.y + rr.h)}\"$sepSt/>"
                                else "<line class=\"sep dashed\" x1=\"${f1(rr.x)}\" y1=\"${f1(rr.y)}\" x2=\"${f1(rr.x + rr.w)}\" y2=\"${f1(rr.y)}\"$sepSt/>",
                            )
                        }
                        if (region.name.isNotEmpty()) sb.append(text(rr.x + 6, rr.y + 12, "region-name", region.name, "start", c))
                    }
                }
                if (v.submachine.isNotEmpty()) {
                    val gx = x + w - 26
                    val gy = y + h - 14
                    val glyphSt = strokeSt("1.2")
                    sb.append("<rect class=\"stroke\" x=\"${f(gx)}\" y=\"${f(gy)}\" width=\"8\" height=\"6\" rx=\"2\"$glyphSt/>")
                    sb.append("<rect class=\"stroke\" x=\"${f(gx + 13)}\" y=\"${f(gy)}\" width=\"8\" height=\"6\" rx=\"2\"$glyphSt/>")
                    sb.append("<line class=\"stroke\" x1=\"${f(gx + 8)}\" y1=\"${f(gy + 3)}\" x2=\"${f(gx + 13)}\" y2=\"${f(gy + 3)}\"$glyphSt/>")
                }
                if (v.invariant.isNotEmpty()) sb.append(text(x + 4, y + h + 14, "invariant", "{${v.invariant}}", "start", ElementColors.item(v.color, v.itemColors, "invariant")))
            }
            VertexType.Final ->
                sb.append("<circle class=\"shape\" cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r)}\"$shapeSt/><circle class=\"fill\" cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(max(2.0, r - 5))}\"$fillSt/>")
            VertexType.Initial, VertexType.Junction ->
                sb.append("<circle class=\"fill\" cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r)}\"$fillSt/>")
            VertexType.ShallowHistory, VertexType.DeepHistory -> {
                sb.append("<circle class=\"shape\" cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r)}\"$shapeSt/>")
                sb.append(text(cx, cy + 4, "glyph", if (v.type == VertexType.DeepHistory) "H*" else "H", "middle", c))
            }
            VertexType.Choice ->
                sb.append("<path class=\"shape\" d=\"M${f(cx)},${f(y)} L${f(x + w)},${f(cy)} L${f(cx)},${f(y + h)} L${f(x)},${f(cy)} Z\"$shapeSt/>")
            VertexType.Fork, VertexType.Join ->
                sb.append("<rect class=\"fill\" x=\"${f(x)}\" y=\"${f(y)}\" width=\"${f(w)}\" height=\"${f(h)}\" rx=\"1\"$fillSt/>")
            VertexType.ConnectionPointRef, VertexType.EntryPoint -> {
                sb.append("<circle class=\"shape\" cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r)}\"$shapeSt/>")
                if (v.type == VertexType.ConnectionPointRef && v.pointKind == PointKind.Exit) sb.append(cross(cx, cy, r * 0.62, strokeSt("1.3")))
            }
            VertexType.ExitPoint -> {
                sb.append("<circle class=\"shape\" cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r)}\"$shapeSt/>")
                sb.append(cross(cx, cy, r * 0.62, strokeSt("1.3")))
            }
            VertexType.Terminate -> {
                sb.append("<rect class=\"hit\" x=\"${f(x)}\" y=\"${f(y)}\" width=\"${f(w)}\" height=\"${f(h)}\"/>")
                sb.append("<path class=\"stroke\" d=\"M${f(x)},${f(y)} L${f(x + w)},${f(y + h)} M${f(x + w)},${f(y)} L${f(x)},${f(y + h)}\"${strokeSt("2")}/>")
            }
            VertexType.Comment -> {
                val fold = 12.0
                sb.append("<path class=\"shape\" d=\"M${f(x)},${f(y)} H${f(x + w - fold)} L${f(x + w)},${f(y + fold)} V${f(y + h)} H${f(x)} Z\"$shapeSt/>")
                sb.append("<path class=\"stroke\" d=\"M${f(x + w - fold)},${f(y)} V${f(y + fold)} H${f(x + w)}\"${strokeSt("1")}/>")
                val lines = Geometry.commentLines(v.text, w - 16)
                for ((i, line) in lines.withIndex()) {
                    if (16 + i * 14 < h) sb.append(text(x + 8, y + 17 + i * 14, "", line, "start", c))
                }
            }
        }
        val caption = if (v.type == VertexType.ConnectionPointRef) session.pointName(session.index.vertex(v.parent), v.ref).ifEmpty { "?" } else v.name
        if (caption.isNotEmpty() && v.type != VertexType.State && v.type != VertexType.Comment) {
            val below = if (v.type == VertexType.Fork || v.type == VertexType.Join) y + h + 13 else y + h + 12
            sb.append(text(cx, below, "activity", caption, "middle", c))
        }
        sb.append("</g>")
    }

    /** Arrow head (its two side points) at [b] for a line coming from [a]. */
    fun arrowHead(a: PointD, b: PointD): Pair<PointD, PointD> {
        val ang = atan2(b.y - a.y, b.x - a.x)
        val l = 10.0
        val w = 0.42
        return PointD(b.x - l * cos(ang - w), b.y - l * sin(ang - w)) to PointD(b.x - l * cos(ang + w), b.y - l * sin(ang + w))
    }

    private fun transitionShape(sb: StringBuilder, ix: ModelIndex, model: FsmModel, t: Transition) {
        if (!Geometry.isDrawn(ix, t)) return
        val pts = Geometry.route(ix, t) ?: return
        val d = "M" + pts.joinToString(" L") { "${f1(it.x)},${f1(it.y)}" }
        sb.append("<g class=\"transition\">")
        val c = ElementColors.of(t.color)
        val lineSt = if (c == null) "" else style("stroke:$c")
        sb.append("<path class=\"line\" d=\"$d\"$lineSt/>")
        val b = pts[pts.size - 1]
        val (p1, p2) = arrowHead(pts[pts.size - 2], b)
        sb.append("<path class=\"arrow\" d=\"M${f1(p1.x)},${f1(p1.y)} L${f1(b.x)},${f1(b.y)} L${f1(p2.x)},${f1(p2.y)}\"$lineSt/>")
        val lines = Labels.transitionLabelItems(model, t)
        if (lines.isNotEmpty()) {
            val lp = Geometry.labelPos(t, pts)
            val anchor = when (lp.anchor) {
                TextAnchor.End -> "end"
                TextAnchor.Start -> "start"
                TextAnchor.Middle -> "middle"
            }
            sb.append("<text class=\"tlabel\" x=\"${f1(lp.x)}\" y=\"${f1(Geometry.labelLineY(lp, 0, lines.size))}\" text-anchor=\"$anchor\">" +
                lines.withIndex().joinToString("") { (i, l) ->
                    val lc = ElementColors.item(t.color, t.itemColors, l.first)
                    "<tspan x=\"${f1(lp.x)}\" dy=\"${if (i > 0) f1(Geometry.LABEL_LINE_HEIGHT) else "0"}\"${style(lc?.let { "fill:$it" })}>${esc(l.second)}</tspan>"
                } + "</text>")
        }
        sb.append("</g>")
    }

    private fun anchors(sb: StringBuilder, ix: ModelIndex, v: Vertex) {
        for ((from, to) in anchorLines(ix, v)) {
            sb.append("<line class=\"anchor\" x1=\"${f1(from.x)}\" y1=\"${f1(from.y)}\" x2=\"${f1(to.x)}\" y2=\"${f1(to.y)}\"/>")
        }
    }

    /** The dashed lines (from, to) linking a comment to the elements it annotates. */
    fun anchorLines(ix: ModelIndex, comment: Vertex): List<Pair<PointD, PointD>> {
        val output = ArrayList<Pair<PointD, PointD>>()
        for (a in comment.anchors) {
            val target = ix.vertex(a)
            var p: PointD? = null
            if (target != null) {
                p = target.center
            } else {
                val t = ix.transition(a)
                if (t != null) {
                    val pts = Geometry.route(ix, t)
                    if (pts != null) p = Geometry.polylineMid(pts).point
                }
            }
            if (p == null) continue
            val from = Geometry.clip(comment, p)
            val to = if (target != null) Geometry.clip(target, from) else p
            output.add(from to to)
        }
        return output
    }
}
