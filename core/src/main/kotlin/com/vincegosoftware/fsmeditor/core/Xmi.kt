package com.vincegosoftware.fsmeditor.core

import kotlin.math.abs

class XmiException(message: String) : Exception(message)

/**
 * Reads and writes state machine files: XMI 2.5.1 holding a UML 2.5.1 model,
 * plus a UML DI diagram (umldi:UMLStateMachineDiagram) with the layout.
 *
 * Layout mapping:
 * - every vertex, region and comment has a UMLShape with dc:Bounds;
 * - every drawn transition has a UMLEdge whose waypoints are the centers of
 *   its source and target with the bend points in between, plus an optional
 *   UMLLabel whose center is the middle of those waypoints moved by the
 *   label offset;
 * - the region layout of an orthogonal state follows from its region shapes.
 *
 * `toXmi(fromXmi(x))` round-trips without loss (coordinates are rounded to 0.1 px),
 * and the output is identical to the one of FSM Editor for VS Code.
 */
object Xmi {
    const val NS_XMI = "http://www.omg.org/spec/XMI/20131001"
    const val NS_UML = "http://www.omg.org/spec/UML/20161101"
    const val NS_UMLDI = "http://www.omg.org/spec/UML/20161101/UMLDI"
    const val NS_DC = "http://www.omg.org/spec/DD/20100524/DC"
    const val NS_DI = "http://www.omg.org/spec/DD/20100524/DI"

    private const val PROFILE_NAME = "FsmStereotypes"
    const val PROFILE_NS = "https://github.com/vincedupuis/fsm-editor/profiles/FsmStereotypes"
    private const val UML_STATE = "$NS_UML/UML.xmi#State"
    /** Language of the opaque behaviors and expressions (argument-less calls, see [Expressions]). */
    private const val LANGUAGE = "FSM"

    private const val LABEL_W = 100.0
    private const val LABEL_H = 16.0

    private val TIME_TRIGGER = Regex("^after\\((.+)\\)$")
    private val UNSAFE = Regex("[^A-Za-z0-9_]")

    // ------------------------------------------------------------------ writing

    fun toXmi(source: FsmModel): String {
        val m = source.copy().normalize()
        val ix = ModelIndex(m)
        val topRegions = m.regions.map { it.id }.toSet()
        val protocol = m.kind == MachineKind.Protocol
        val smId = m.id

        fun isMachinePoint(v: Vertex) = (v.type == VertexType.EntryPoint || v.type == VertexType.ExitPoint) && v.parent in topRegions

        // Events: one signal event (and signal) per event name, one time event per time trigger.
        val events = HashMap<String, String>()
        val eventElements = ArrayList<XNode>()
        fun eventFor(trigger: String): String {
            events[trigger]?.let { return it }
            val time = TIME_TRIGGER.find(trigger)
            val safe = UNSAFE.replace(trigger, "_")
            val id = "ev_$safe"
            if (time != null) {
                eventElements.add(
                    XNode("packagedElement", "xmi:type" to "uml:TimeEvent", "xmi:id" to id, "name" to trigger, "isRelative" to "true").add(
                        XNode("when", "xmi:type" to "uml:TimeExpression", "xmi:id" to id + "_when").add(
                            XNode("expr", "xmi:type" to "uml:LiteralString", "xmi:id" to id + "_expr", "value" to time.groupValues[1]),
                        ),
                    ),
                )
            } else {
                eventElements.add(XNode("packagedElement", "xmi:type" to "uml:SignalEvent", "xmi:id" to id, "name" to trigger, "signal" to "sig_$safe"))
                eventElements.add(XNode("packagedElement", "xmi:type" to "uml:Signal", "xmi:id" to "sig_$safe", "name" to trigger))
            }
            events[trigger] = id
            return id
        }

        fun behavior(tag: String, id: String, body: String): List<XNode> =
            if (body.isEmpty()) emptyList()
            else listOf(XNode(tag, "xmi:type" to "uml:OpaqueBehavior", "xmi:id" to id).add(XNode("language").add(LANGUAGE), XNode("body").add(body)))

        fun constraint(tag: String, id: String, body: String): List<XNode> =
            if (body.isEmpty()) emptyList()
            else listOf(
                XNode(tag, "xmi:type" to "uml:Constraint", "xmi:id" to id).add(
                    XNode("specification", "xmi:type" to "uml:OpaqueExpression", "xmi:id" to id + "_spec").add(
                        XNode("language").add(LANGUAGE), XNode("body").add(body),
                    ),
                ),
            )

        fun triggers(tag: String, ownerId: String, list: List<String>): List<XNode> =
            list.mapIndexed { i, t -> XNode(tag, "xmi:type" to "uml:Trigger", "xmi:id" to "${ownerId}_$tag${i + 1}", "name" to t, "event" to eventFor(t)) }

        // Regions enclosing a vertex, innermost first.
        fun regionChain(v: Vertex): List<String> {
            val output = ArrayList<String>()
            val seen = HashSet<String>()
            var cur: Vertex? = v
            while (cur != null && seen.add(cur.id)) {
                if (ix.isBorderVertex(cur)) {
                    cur = ix.vertex(cur.parent)
                    continue
                }
                output.add(cur.parent)
                cur = ix.regionOwner[cur.parent]
            }
            return output
        }

        // A transition is owned by the innermost region that contains both of its ends.
        val transitionsByRegion = HashMap<String, MutableList<Transition>>()
        for (t in m.transitions) {
            val s = ix.vertex(t.source)
            val g = ix.vertex(t.target)
            val sc = if (s != null) regionChain(s) else emptyList()
            val gc = (if (g != null) regionChain(g) else emptyList()).toSet()
            val container = sc.firstOrNull { it in gc } ?: m.regions[0].id
            transitionsByRegion.getOrPut(container) { ArrayList() }.add(t)
        }

        fun transitionNode(t: Transition): XNode =
            XNode(
                "transition",
                "xmi:type" to (if (protocol) "uml:ProtocolTransition" else "uml:Transition"),
                "xmi:id" to t.id,
                "kind" to t.kind.key,
                "source" to t.source,
                "target" to t.target,
            )
                .add(triggers("trigger", t.id, t.triggers))
                .add(
                    if (protocol) constraint("preCondition", t.id + "_pre", t.precondition) + constraint("postCondition", t.id + "_post", t.postcondition)
                    else constraint("guard", t.id + "_guard", t.guard),
                )
                .add(behavior("effect", t.id + "_effect", t.effect))

        fun commentNode(c: Vertex): XNode =
            XNode("ownedComment", "xmi:type" to "uml:Comment", "xmi:id" to c.id, "annotatedElement" to c.anchors.joinToString(" "))
                .add(XNode("body").add(c.text))

        fun pointNode(tag: String, v: Vertex): XNode =
            XNode(tag, "xmi:type" to "uml:Pseudostate", "xmi:id" to v.id, "name" to v.name, "kind" to v.type.key)

        lateinit var vertexNode: (Vertex) -> XNode

        fun regionNode(r: Region): XNode {
            val kids = m.vertices.filter { it.parent == r.id && !isMachinePoint(it) }
            return XNode("region", "xmi:type" to "uml:Region", "xmi:id" to r.id, "name" to r.name)
                .add(kids.filter { it.type == VertexType.Comment }.map(::commentNode))
                .add(kids.filter { it.type != VertexType.Comment }.map { vertexNode(it) })
                .add((transitionsByRegion[r.id] ?: emptyList<Transition>()).map(::transitionNode))
        }

        vertexNode = fun(v: Vertex): XNode {
            if (v.type == VertexType.Final) return XNode("subvertex", "xmi:type" to "uml:FinalState", "xmi:id" to v.id, "name" to v.name)
            if (v.type.isPseudostate) return pointNode("subvertex", v)
            val node = XNode("subvertex", "xmi:type" to "uml:State", "xmi:id" to v.id, "name" to v.name)
                .add(behavior("entry", v.id + "_entry", v.entry))
                .add(behavior("exit", v.id + "_exit", v.exit))
                .add(behavior("doActivity", v.id + "_do", v.doActivity))
                .add(constraint("stateInvariant", v.id + "_inv", v.invariant))
                .add(triggers("deferrableTrigger", v.id, v.deferrable))
            for (cp in m.vertices) {
                if (cp.parent != v.id) continue
                if (cp.type == VertexType.EntryPoint || cp.type == VertexType.ExitPoint) node.add(pointNode("connectionPoint", cp))
                if (cp.type == VertexType.ConnectionPointRef) {
                    val file = v.submachine.split('#')[0]
                    node.add(
                        XNode("connection", "xmi:type" to "uml:ConnectionPointReference", "xmi:id" to cp.id).add(
                            XNode(if (cp.pointKind == PointKind.Exit) "exit" else "entry", "href" to "$file#${cp.ref}"),
                        ),
                    )
                }
            }
            for (r in v.regions) node.add(regionNode(r))
            if (v.submachine.isNotEmpty()) node.add(XNode("submachine", "href" to v.submachine))
            return node
        }

        val machine = XNode(
            "packagedElement",
            "xmi:type" to (if (protocol) "uml:ProtocolStateMachine" else "uml:StateMachine"),
            "xmi:id" to smId,
            "name" to m.name,
        )
        if (m.documentation.isNotEmpty()) {
            machine.add(
                XNode("ownedComment", "xmi:type" to "uml:Comment", "xmi:id" to smId + "_doc", "annotatedElement" to smId)
                    .add(XNode("body").add(m.documentation)),
            )
        }
        machine.add(m.vertices.filter(::isMachinePoint).map { pointNode("connectionPoint", it) })
        machine.add(m.regions.map(::regionNode))

        val packaged = ArrayList<XNode>()
        if (m.context.isNotEmpty()) {
            // The context classifier owns the state machine as its classifier behavior.
            machine.name = "ownedBehavior"
            packaged.add(
                XNode("packagedElement", "xmi:type" to "uml:Class", "xmi:id" to smId + "_context", "name" to m.context, "classifierBehavior" to smId)
                    .add(machine),
            )
        } else {
            packaged.add(machine)
        }
        // Events are collected while building the machine, so they come after it.
        packaged.addAll(eventElements)

        // Stereotypes: an embedded profile with one stereotype per name, extending UML::State.
        val stereotyped = m.vertices.filter { it.type == VertexType.State && it.stereotype.isNotEmpty() }
        val stereoNames = stereotyped.map { it.stereotype }.distinct()
        val profileApplication = ArrayList<XNode>()
        val applications = ArrayList<XNode>()
        if (stereoNames.isNotEmpty()) {
            val profile = XNode("packagedElement", "xmi:type" to "uml:Profile", "xmi:id" to "fsm_profile", "name" to PROFILE_NAME, "URI" to PROFILE_NS).add(
                XNode("metaclassReference", "xmi:type" to "uml:ElementImport", "xmi:id" to "fsm_profile_state").add(
                    XNode("importedElement", "xmi:type" to "uml:Class", "href" to UML_STATE),
                ),
            )
            for (s in stereoNames) {
                profile.add(
                    XNode("packagedElement", "xmi:type" to "uml:Stereotype", "xmi:id" to "st_$s", "name" to s).add(
                        XNode("ownedAttribute", "xmi:type" to "uml:Property", "xmi:id" to "st_${s}_base", "name" to "base_State", "association" to "ext_$s").add(
                            XNode("type", "href" to UML_STATE),
                        ),
                    ),
                    XNode("packagedElement", "xmi:type" to "uml:Extension", "xmi:id" to "ext_$s", "name" to "E_${s}_State", "memberEnd" to "st_${s}_base ext_${s}_end").add(
                        XNode(
                            "ownedEnd", "xmi:type" to "uml:ExtensionEnd", "xmi:id" to "ext_${s}_end", "name" to "extension_$s",
                            "type" to "st_$s", "association" to "ext_$s", "aggregation" to "composite",
                        ),
                    ),
                )
            }
            packaged.add(profile)
            profileApplication.add(XNode("profileApplication", "xmi:type" to "uml:ProfileApplication", "xmi:id" to "fsm_profile_app", "appliedProfile" to "fsm_profile"))
            for (v in stereotyped) {
                applications.add(XNode("$PROFILE_NAME:${v.stereotype}", "xmi:id" to v.id + "_st", "base_State" to v.id))
            }
        }

        // Diagram
        fun bounds(x: Double, y: Double, w: Double, h: Double) =
            XNode("bounds", "xmi:type" to "dc:Bounds", "x" to Num.r1(x), "y" to Num.r1(y), "width" to Num.r1(w), "height" to Num.r1(h))
        val shapes = ArrayList<XNode>()
        for (v in m.vertices) {
            shapes.add(XNode("ownedElement", "xmi:type" to "umldi:UMLShape", "xmi:id" to "di_" + v.id, "modelElement" to v.id).add(bounds(v.x, v.y, v.w, v.h)))
            if (v.type == VertexType.State) {
                for (rb in Geometry.regionRects(m, v)) {
                    shapes.add(
                        XNode("ownedElement", "xmi:type" to "umldi:UMLShape", "xmi:id" to "di_" + rb.id, "modelElement" to rb.id)
                            .add(bounds(rb.rect.x, rb.rect.y, rb.rect.w, rb.rect.h)),
                    )
                }
            }
        }
        val edges = ArrayList<XNode>()
        for (t in m.transitions) {
            val s = ix.vertex(t.source) ?: continue
            val g = ix.vertex(t.target) ?: continue
            if (t.kind == TransitionKind.Internal && s === g && s.type == VertexType.State) continue // drawn in the state's compartment
            val pts = listOf(s.center) + t.points + listOf(g.center)
            val edge = XNode(
                "ownedElement", "xmi:type" to "umldi:UMLEdge", "xmi:id" to "di_" + t.id, "modelElement" to t.id,
                "source" to "di_" + s.id, "target" to "di_" + g.id,
            ).add(pts.map { XNode("waypoint", "xmi:type" to "dc:Point", "x" to Num.r1(it.x), "y" to Num.r1(it.y)) })
            val off = t.labelOffset
            if (off != null) {
                val mid = Geometry.polylineMid(pts).point
                val cx = mid.x + off.x
                val cy = mid.y + off.y
                edge.add(
                    XNode("ownedElement", "xmi:type" to "umldi:UMLLabel", "xmi:id" to "di_${t.id}_label", "modelElement" to t.id)
                        .add(bounds(cx - LABEL_W / 2, cy - LABEL_H / 2, LABEL_W, LABEL_H)),
                )
            }
            edges.add(edge)
        }
        for (c in m.vertices.filter { it.type == VertexType.Comment }) {
            for ((i, a) in c.anchors.withIndex()) {
                if (ix.vertex(a) == null && ix.transition(a) == null) continue
                edges.add(
                    XNode(
                        "ownedElement", "xmi:type" to "umldi:UMLEdge", "xmi:id" to "di_${c.id}_anchor${i + 1}", "modelElement" to c.id,
                        "source" to "di_" + c.id, "target" to "di_$a",
                    ),
                )
            }
        }
        val diagram = XNode("umldi:UMLStateMachineDiagram", "xmi:id" to smId + "_diagram", "name" to m.name, "modelElement" to smId, "isFrame" to "false")
            .add(shapes, edges)

        val root = XNode(
            "xmi:XMI",
            "xmi:version" to "20131001",
            "xmlns:xmi" to NS_XMI,
            "xmlns:uml" to NS_UML,
            "xmlns:umldi" to NS_UMLDI,
            "xmlns:dc" to NS_DC,
            "xmlns:di" to NS_DI,
        )
        if (stereoNames.isNotEmpty()) root.attrs.add("xmlns:$PROFILE_NAME" to PROFILE_NS)
        root.add(XNode("uml:Model", "xmi:id" to smId + "_model", "name" to m.name).add(profileApplication, packaged), diagram, applications)
        return XmlTree.write(root)
    }

    // ------------------------------------------------------------------ reading

    private val CANONICAL = mapOf(NS_XMI to "xmi", NS_UML to "uml", NS_UMLDI to "umldi", NS_DC to "dc", NS_DI to "di")

    private fun typeOf(el: XmlElem): String = el.attr("xmi:type") ?: if (el.ns == NS_UML) "uml:" + el.local else ""
    private fun idOf(el: XmlElem): String = el.attr("xmi:id") ?: ""
    private fun kids(el: XmlElem, name: String): List<XmlElem> = el.children.filter { it.name == name }
    private fun kid(el: XmlElem?, name: String): XmlElem? = el?.children?.firstOrNull { it.name == name }

    private val REF_SEPARATORS = Regex("[ \t\r\n]+")

    /** A reference property, written as an attribute (`a="id1 id2"`) or as child elements (`<a xmi:idref|href>`). */
    private fun refs(el: XmlElem, name: String): MutableList<String> {
        val output = (el.attr(name) ?: "").split(REF_SEPARATORS).filter { it.isNotEmpty() }.toMutableList()
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
    private fun bodyOf(el: XmlElem?): String {
        if (el == null) return ""
        val spec = kid(el, "specification") ?: el
        val b = kid(spec, "body")
        if (b != null) return b.text.trim()
        return (spec.attr("body") ?: spec.attr("value") ?: "").trim()
    }

    private fun walk(el: XmlElem): Sequence<XmlElem> = sequence {
        yield(el)
        for (c in el.children) yieldAll(walk(c))
    }

    private fun or(vararg values: String?): String = values.firstOrNull { !it.isNullOrEmpty() } ?: ""

    /** Parses a state machine file. Throws [XmlParseException] or [XmiException] when it cannot be read. */
    fun fromXmi(text: String): FsmModel {
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

        val model = FsmModel()
        model.id = or(idOf(sm), "sm")
        model.name = sm.attr("name") ?: ""
        model.kind = if (typeOf(sm) == "uml:ProtocolStateMachine") MachineKind.Protocol else MachineKind.Behavioral
        model.context = contextEl?.attr("name") ?: ""
        for (c in kids(sm, "ownedComment")) {
            if (model.id in refs(c, "annotatedElement")) model.documentation = bodyOf(c)
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
            t.effect = bodyOf(kid(el, "effect"))
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
                        comment.text = bodyOf(c)
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
        if (model.regions.isEmpty()) model.regions.add(Region(model.id + "_region"))
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
            for (el in walk(d)) {
                val type = typeOf(el)
                val target = firstRef(el, "modelElement")
                if (target.isNullOrEmpty()) continue
                val b = kid(el, "bounds")
                val t = transitions[target]
                if (type == "umldi:UMLShape" && b != null) {
                    val box = RectD(Num.parse(b.attr("x")), Num.parse(b.attr("y")), Num.parse(b.attr("width")), Num.parse(b.attr("height")))
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
                    val pts = kids(el, "waypoint").map { PointD(Num.parse(it.attr("x")), Num.parse(it.attr("y"))) }
                    if (pts.size > 2) t.points = pts.subList(1, pts.size - 1).toMutableList()
                    val label = el.children.firstOrNull { typeOf(it) == "umldi:UMLLabel" }
                    val lb = kid(label, "bounds")
                    if (lb != null && pts.size >= 2) {
                        val mid = Geometry.polylineMid(pts).point
                        t.labelOffset = PointD(
                            Num.r1(Num.parse(lb.attr("x")) + Num.parse(lb.attr("width")) / 2 - mid.x),
                            Num.r1(Num.parse(lb.attr("y")) + Num.parse(lb.attr("height")) / 2 - mid.y),
                        )
                    }
                }
            }
        }
        for (v in model.vertices) {
            if (v.regions.size < 2) continue
            val a = regionShapes[v.regions[0].id]
            val b = regionShapes[v.regions[1].id]
            if (a != null && b != null) {
                v.regionLayout = if (abs(b.x - a.x) > abs(b.y - a.y)) RegionLayout.Horizontal else RegionLayout.Vertical
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
        return model.normalize()
    }
}
