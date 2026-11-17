package com.vincegosoftware.fsmeditor.core

import kotlin.math.abs

class XmiException(message: String) : Exception(message)

/** A model read from an old file, with what the JSON format has no place for. */
class XmiModel(
    val model: FsmModel,
    /** xmi:id of the state machine, which hrefs to it name (`Payment.fsm#sm`). */
    val xmiId: String,
    /** Name of the context classifier, which the JSON format doesn't hold. */
    val context: String,
)

/**
 * Reads state machine files of earlier versions: XMI 2.5.1 holding a UML 2.5.1 model, plus a UML DI diagram
 * (umldi:UMLStateMachineDiagram) with the layout. Nothing writes this format any more: saving such a file writes the
 * JSON format ([Format]), converting it ([Conversion]). A port of the Visual Studio edition's `Xmi.cs` (the VS Code
 * edition's `src/xmi.ts`), which reads coordinates as JavaScript does (`+x || 0`: an infinite one stays infinite, and
 * the writer reports it).
 *
 * Layout mapping:
 * - every vertex, region and comment has a UMLShape with dc:Bounds;
 * - every drawn transition has a UMLEdge whose waypoints are the centers of its source and target with the bend
 *   points in between, plus an optional UMLLabel whose center is the middle of those waypoints moved by the label
 *   offset;
 * - the region layout of an orthogonal state, and each region's size along it, follow from its region shapes;
 * - colors and groups are kept in one xmi:Extension at the end of the diagram, with a
 *   <style element="id" color="#rrggbb" entry="#rrggbb" …/> per colored vertex or transition (UML DI has no color),
 *   then a <group id="g" members="id id …"/> per group of vertices.
 *
 * Files written by earlier versions may end with the model hash, in its own xmi:Extension (<modelHash value="…"/>):
 * the reader ignores it.
 */
object Xmi {
    const val NS_XMI = "http://www.omg.org/spec/XMI/20131001"
    const val NS_UML = "http://www.omg.org/spec/UML/20161101"
    const val NS_UMLDI = "http://www.omg.org/spec/UML/20161101/UMLDI"
    const val NS_DC = "http://www.omg.org/spec/DD/20100524/DC"
    const val NS_DI = "http://www.omg.org/spec/DD/20100524/DI"

    const val PROFILE_NS = "https://github.com/vincedupuis/fsm-editor/profiles/FsmStereotypes"

    private const val EXTENDER = "FSM Editor"

    private val TIME_TRIGGER = Regex("^after\\((.+)\\)$")

    /**
     * Point halfway along the polyline, for transition labels, computed as polylineMid of xmi.ts does (its own
     * formula and JavaScript's Math.hypot, not [Geometry.polylineMid]), so the file stays byte-identical.
     */
    private fun labelMid(pts: List<PointD>): PointD {
        var total = 0.0
        for (i in 1 until pts.size) total += Num.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        var half = total / 2
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = Num.hypot(b.x - a.x, b.y - a.y)
            if (len >= half && len > 0) return PointD(a.x + (b.x - a.x) * half / len, a.y + (b.y - a.y) * half / len)
            half -= len
        }
        return pts[0]
    }

    // ------------------------------------------------------------------ reading

    private val CANONICAL = mapOf(NS_XMI to "xmi", NS_UML to "uml", NS_UMLDI to "umldi", NS_DC to "dc", NS_DI to "di")

    private fun typeOf(el: XmlElem): String = el.attr("xmi:type") ?: if (el.ns == NS_UML) "uml:" + el.local else ""
    private fun idOf(el: XmlElem): String = el.attr("xmi:id") ?: ""
    private fun kids(el: XmlElem, name: String): List<XmlElem> = el.children.filter { it.name == name }
    private fun kid(el: XmlElem?, name: String): XmlElem? = el?.children?.firstOrNull { it.name == name }

    /** A reference property, written as an attribute (`a="id1 id2"`) or as child elements (`<a xmi:idref|href>`). */
    private fun refs(el: XmlElem, name: String): MutableList<String> {
        val output = JsWhitespace.split(el.attr(name))
        for (c in kids(el, name)) {
            val r = c.attr("xmi:idref") ?: c.attr("href")
            if (!r.isNullOrEmpty()) output.add(r)
        }
        return output
    }

    private fun firstRef(el: XmlElem, name: String): String? = el.attr(name) ?: refs(el, name).firstOrNull()

    private fun localId(reference: String?): String {
        val r = reference ?: ""
        val i = r.indexOf('#')
        return if (i >= 0) r.substring(i + 1) else r
    }

    /** Text of an opaque behavior or constraint (its first body). */
    private fun bodyOf(el: XmlElem?, trim: Boolean = true): String {
        if (el == null) return ""
        val spec = kid(el, "specification") ?: el
        val b = kid(spec, "body")
        val text = b?.text ?: spec.attr("body") ?: spec.attr("value") ?: ""
        return if (trim) JsWhitespace.trim(text) else text
    }

    private fun walk(el: XmlElem): Sequence<XmlElem> = sequence {
        yield(el)
        for (c in el.children) yieldAll(walk(c))
    }

    private fun or(vararg values: String?): String = values.firstOrNull { !it.isNullOrEmpty() } ?: ""

    /** Parses a state machine file. Throws [XmlParseException] or [XmiException] when it cannot be read. */
    fun fromXmi(text: String): FsmModel = read(text).model

    /** Parses a state machine file, with what the JSON format has no place for. */
    fun read(text: String): XmiModel {
        val root = XmlTree.parse(text, CANONICAL)
        if (root.name != "xmi:XMI" && root.name != "uml:Model") {
            throw XmiException("Not an XMI document: the root element is <${root.name}>, expected <xmi:XMI>.")
        }
        var machineEl: XmlElem? = null
        var contextEl: XmlElem? = null
        val elementsById = HashMap<String, XmlElem>()
        fun visit(el: XmlElem, parent: XmlElem?) {
            val id = idOf(el)
            if (id.isNotEmpty()) elementsById[id] = el
            val t = typeOf(el)
            if (machineEl == null && (t == "uml:StateMachine" || t == "uml:ProtocolStateMachine")) {
                machineEl = el
                if (parent != null && typeOf(parent) == "uml:Class") contextEl = parent
            }
            for (c in el.children) visit(c, el)
        }
        visit(root, null)
        val sm = machineEl ?: throw XmiException("The document does not contain a UML state machine.")

        fun eventText(trig: XmlElem): String {
            val ev = elementsById[localId(firstRef(trig, "event"))]
            if (ev != null && typeOf(ev) == "uml:TimeEvent") {
                val expr = kid(kid(ev, "when") ?: ev, "expr")
                val value = if (expr != null) expr.attr("value") ?: bodyOf(expr) else ""
                if (value.isNotEmpty()) return "after($value)"
            }
            if (ev != null && typeOf(ev) == "uml:SignalEvent") {
                val sig = elementsById[localId(ev.attr("signal"))]
                return or(sig?.attr("name"), ev.attr("name"), trig.attr("name"))
            }
            return or(ev?.attr("name"), trig.attr("name"))
        }

        val xmiId = or(idOf(sm), "sm")
        val model = FsmModel()
        model.name = sm.attr("name") ?: ""
        model.kind = if (typeOf(sm) == "uml:ProtocolStateMachine") MachineKind.Protocol else MachineKind.Behavioral
        for (c in kids(sm, "ownedComment")) {
            if (xmiId in refs(c, "annotatedElement")) model.documentation = bodyOf(c)
        }

        fun vertexBase(el: XmlElem, type: VertexType, parent: String) =
            Vertex(idOf(el), type, el.attr("name") ?: "", parent, Double.NaN, Double.NaN)

        fun readTransition(el: XmlElem) {
            val kindText = el.attr("kind")
            val t = Transition(
                idOf(el),
                localId(firstRef(el, "source")),
                localId(firstRef(el, "target")),
                when (kindText) {
                    "internal" -> TransitionKind.Internal
                    "local" -> TransitionKind.Local
                    else -> TransitionKind.External
                },
            )
            t.triggers = kids(el, "trigger").map(::eventText).filter { it.isNotEmpty() }.toMutableList()
            t.guard = bodyOf(kid(el, "guard"))
            t.action = bodyOf(kid(el, "effect"))
            t.precondition = bodyOf(kid(el, "preCondition"))
            t.postcondition = bodyOf(kid(el, "postCondition"))
            model.transitions.add(t)
        }

        fun readPoint(el: XmlElem, parent: String) {
            val kind = if (el.attr("kind") == "exitPoint") VertexType.ExitPoint else VertexType.EntryPoint
            model.vertices.add(vertexBase(el, kind, parent))
        }

        lateinit var readRegion: (XmlElem) -> Unit

        fun readVertex(el: XmlElem, parent: String) {
            val t = typeOf(el)
            if (t == "uml:FinalState") {
                model.vertices.add(vertexBase(el, VertexType.Final, parent))
                return
            }
            if (t == "uml:Pseudostate") {
                val k = VertexType.parseKey(el.attr("kind") ?: "initial")
                model.vertices.add(vertexBase(el, if (k != null && k.isPseudostate) k else VertexType.Initial, parent))
                return
            }
            val v = vertexBase(el, VertexType.State, parent)
            v.entry = bodyOf(kid(el, "entry"))
            v.exit = bodyOf(kid(el, "exit"))
            v.doActivity = bodyOf(kid(el, "doActivity"))
            v.invariant = bodyOf(kid(el, "stateInvariant"))
            v.deferrable = kids(el, "deferrableTrigger").map(::eventText).filter { it.isNotEmpty() }.toMutableList()
            v.submachine = refs(el, "submachine").firstOrNull() ?: ""
            model.vertices.add(v)
            for (cp in kids(el, "connectionPoint")) readPoint(cp, v.id)
            for (c in kids(el, "connection")) {
                val entryRef = refs(c, "entry").firstOrNull()
                val exitRef = refs(c, "exit").firstOrNull()
                val reference = vertexBase(c, VertexType.ConnectionPointRef, v.id)
                reference.name = ""
                reference.pointKind = if (exitRef != null && entryRef == null) PointKind.Exit else PointKind.Entry
                reference.ref = localId(entryRef ?: exitRef ?: "")
                model.vertices.add(reference)
            }
            for (r in kids(el, "region")) {
                v.regions.add(Region(idOf(r), r.attr("name") ?: ""))
                readRegion(r)
            }
        }

        readRegion = { el ->
            val id = idOf(el)
            for (c in el.children) {
                when (c.name) {
                    "subvertex" -> readVertex(c, id)
                    "transition" -> readTransition(c)
                    "ownedComment" -> {
                        val comment = vertexBase(c, VertexType.Comment, id)
                        comment.text = bodyOf(c, false)
                        comment.anchors = refs(c, "annotatedElement")
                        model.vertices.add(comment)
                    }
                }
            }
        }

        for (r in kids(sm, "region")) {
            model.regions.add(Region(idOf(r), r.attr("name") ?: ""))
            readRegion(r)
        }
        if (model.regions.isEmpty()) model.regions.add(Region(xmiId + "_region"))
        for (cp in kids(sm, "connectionPoint")) readPoint(cp, model.regions[0].id)

        // Stereotype applications: elements of the embedded profile's namespace.
        val byId = HashMap<String, Vertex>()
        for (v in model.vertices) byId[v.id] = v
        for (c in root.children) {
            if (c.ns != PROFILE_NS) continue
            byId[localId(c.attr("base_State"))]?.stereotype = c.local
        }

        // Layout
        val transitions = HashMap<String, Transition>()
        for (t in model.transitions) transitions[t.id] = t
        val regionShapes = HashMap<String, RectD>()
        for (d in root.children) {
            if (d.ns != NS_UMLDI) continue
            for (ext in kids(d, "xmi:Extension")) {
                if (ext.attr("extender") != EXTENDER) continue
                for (st in kids(ext, "style")) {
                    val id = st.attr("element") ?: ""
                    fun read(name: String) = (st.attr(name) ?: "").lowercase()
                    fun apply(setColor: (String) -> Unit, items: MutableMap<String, String>) {
                        val color = read("color")
                        if (ElementColors.isColor(color)) setColor(color)
                        val found = LinkedHashMap<String, String>()
                        for (k in ElementColors.ITEM_KEYS) {
                            val c = read(if (k == "action") "effect" else k)
                            if (ElementColors.isColor(c)) found[k] = c
                        }
                        if (found.isNotEmpty()) {
                            items.clear()
                            items.putAll(found)
                        }
                    }
                    val sv = byId[id]
                    val stt = transitions[id]
                    if (sv != null) apply({ sv.color = it }, sv.itemColors)
                    else if (stt != null) apply({ stt.color = it }, stt.itemColors)
                }
                // A vertex listed in several groups stays in the first.
                for (g in kids(ext, "group")) {
                    val id = g.attr("id") ?: ""
                    if (id.isEmpty()) continue
                    for (member in JsWhitespace.split(g.attr("members"))) {
                        val gv = byId[member]
                        if (gv != null && gv.group.isEmpty()) gv.group = id
                    }
                }
            }
            for (el in walk(d)) {
                val type = typeOf(el)
                val target = firstRef(el, "modelElement")
                if (target.isNullOrEmpty()) continue
                val b = kid(el, "bounds")
                val t = transitions[target]
                if (type == "umldi:UMLShape" && b != null) {
                    val box = RectD(bound(b.attr("x")), bound(b.attr("y")), bound(b.attr("width")), bound(b.attr("height")))
                    val v = byId[target]
                    if (v != null) {
                        v.x = box.x
                        v.y = box.y
                        v.w = box.w
                        v.h = box.h
                    } else {
                        regionShapes[target] = box
                    }
                } else if (type == "umldi:UMLEdge" && t != null) {
                    // Read as JavaScript reads them (+x || 0): an infinite coordinate stays infinite (the writer reports it).
                    val pts = kids(el, "waypoint").map { PointD(bound(it.attr("x")), bound(it.attr("y"))) }
                    if (pts.size > 2) t.points = pts.subList(1, pts.size - 1).toMutableList()
                    val label = el.children.firstOrNull { typeOf(it) == "umldi:UMLLabel" }
                    val lb = kid(label, "bounds")
                    if (lb != null && pts.size >= 2) {
                        val mid = labelMid(pts)
                        // Not rounded: the middle is off the 0.1 px grid, and an offset rounded to 0.1 px could move the
                        // label by 0.1 px on each save. Written again from the same waypoints, it gives the same label box.
                        t.labelOffset = PointD(
                            Num.parseJs(lb.attr("x")) + Num.parseJs(lb.attr("width")) / 2 - mid.x,
                            Num.parseJs(lb.attr("y")) + Num.parseJs(lb.attr("height")) / 2 - mid.y,
                        )
                    }
                }
            }
        }
        for (v in model.vertices) {
            if (v.regions.size >= 2) {
                val a = regionShapes[v.regions[0].id]
                val b = regionShapes[v.regions[1].id]
                if (a != null && b != null) {
                    v.regionLayout = if (abs(b.x - a.x) > abs(b.y - a.y)) RegionLayout.Horizontal else RegionLayout.Vertical
                }
            }
            // Each region's own size, along the layout.
            for (r in v.regions) {
                val box = regionShapes[r.id] ?: continue
                r.size = if (v.regionLayout == RegionLayout.Horizontal) box.w else box.h
            }
        }

        // Elements without a shape get a default place and size.
        var slot = 0
        for (v in model.vertices) {
            if (v.w == 0.0 || v.h == 0.0) {
                val (w, h) = v.type.defaultSize
                v.w = w
                v.h = h
            }
            if (v.x.isNaN() || v.y.isNaN()) {
                v.x = 40.0 + slot % 5 * 180
                v.y = 40.0 + slot / 5 * 120
                slot++
            }
        }
        model.normalize()
        return XmiModel(model, xmiId, contextEl?.attr("name") ?: "")
    }

    /** A bound of a shape as JavaScript's `+s || 0` reads it: missing or invalid gives 0, infinite stays infinite. */
    private fun bound(s: String?): Double {
        val d = Num.parseJs(s)
        return if (d.isNaN()) 0.0 else d
    }

}
