package com.vincegosoftware.fsmeditor.core

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Trace Player's formats, replay and session, as test/unit/trace.test.ts of the VS Code edition checks them (ported from TraceTests.cs). */
class TraceTest {
    private fun examples(file: String) = File(ParityTest.EXAMPLES, file).absoluteFile.normalize().path

    private fun example(file: String) = Xmi.fromXmi(File(examples(file)).readText())

    private fun instance(key: String, model: FsmModel, file: String? = null) = TraceInstance(key, null, file ?: "/$key.fsm", model, ModelIndex(model))

    /** The timeline of [lines] read with [format], every record about the instance of its machine (or [fallback]). */
    private fun timeline(lines: List<String>, format: TraceFormat, instances: Map<String, TraceInstance>, fallback: TraceInstance? = null) =
        lines.mapIndexedNotNull { i, text -> format.parse(text, i) }.map { TimelineEntry(it, instance = instances[it.machine] ?: fallback) }

    private fun kindsOf(entries: List<TimelineEntry>): Map<String, Set<TraceKind>> {
        val kinds = HashMap<String, MutableSet<TraceKind>>()
        for (e in entries) e.instance?.let { kinds.getOrPut(it.key) { HashSet() }.add(e.record.kind) }
        return kinds
    }

    private fun at(entries: List<TimelineEntry>, cursor: Int, key: String) =
        TraceReplay.replay(entries, cursor, kindsOf(entries)).firstOrNull { it.first.key == key }?.second

    private fun localMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000), ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble()

    /** The model of the tracing tests: S, a junction leading to a choice, X and Y. */
    private fun traced(): FsmModel {
        val m = FsmModel().apply {
            name = "Traced"
            regions.add(Region("r_root"))
        }
        fun v(id: String, type: VertexType, name: String = id) = m.vertices.add(Vertex(id, type, name, "r_root", w = 10.0, h = 10.0))
        fun t(id: String, s: String, t: String, trigger: String? = null, guard: String = "") =
            m.transitions.add(Transition(id, s, t).apply {
                if (trigger != null) triggers.add(trigger)
                this.guard = guard
            })
        v("S", VertexType.State)
        v("X", VertexType.State)
        v("Y", VertexType.State)
        v("j", VertexType.Junction)
        v("c", VertexType.Choice)
        v("i", VertexType.Initial, "")
        t("tInit", "i", "S")
        t("tSJ", "S", "j", "e")
        t("tJC", "j", "c", guard = "g()")
        t("tJY", "j", "Y", guard = "else")
        t("tCX", "c", "X", guard = "h()")
        t("tCY", "c", "Y", guard = "else")
        t("tXS", "X", "S", "back")
        t("tYS", "Y", "S", "back")
        return m
    }

    /** What the onTrace hook of the code generated for Traced writes: start, e (to Y through the junction's else), back, e (to X through the choice). */
    private val tracedLog = listOf(
        "fsm Traced transition tInit initial -> S",
        "fsm Traced enter S S",
        "fsm Traced event e e",
        "fsm Traced exit S S",
        "fsm Traced transition tSJ S -> j (e)",
        "fsm Traced transition tJY j -> Y [else]",
        "fsm Traced enter Y Y",
        "fsm Traced event back back",
        "fsm Traced exit Y Y",
        "fsm Traced transition tYS Y -> S (back)",
        "fsm Traced enter S S",
        "fsm Traced event e e",
        "fsm Traced exit S S",
        "fsm Traced transition tSJ S -> j (e)",
        "fsm Traced transition tJC j -> c [g()]",
        "fsm Traced transition tCX c -> X [h()]",
        "fsm Traced enter X X",
    )

    // ---------------------------------------------------------------- formats

    @Test
    fun readsTheBuiltInFormatAfterAnyPrefixWithItsTime() {
        val r = TraceFormats.builtIn.parse("2026-11-11T10:02:03.120Z INFO [main] fsm Order.Payment enter p_auth Authorizing", 4)!!
        assertEquals(TraceKind.Enter, r.kind)
        assertEquals("Order.Payment", r.machine)
        assertEquals("p_auth", r.id)
        assertEquals("Authorizing", r.name)
        assertEquals(4, r.line)
        assertEquals(java.time.Instant.parse("2026-11-11T10:02:03.120Z").toEpochMilli().toDouble(), r.time)
        assertEquals("Cart -> Payment (checkout)", TraceFormats.builtIn.parse("fsm Order transition t1 Cart -> Payment (checkout)", 0)!!.name)
        assertNull(TraceFormats.builtIn.parse("fsm Order enter", 0))
        assertNull(TraceFormats.builtIn.parse("the fsm Order is ready", 0))
    }

    @Test
    fun parsesTimes() {
        assertEquals(localMillis(2026, 11, 11, 10, 2, 3, 500), TraceFormats.parseTime("2026-11-11 10:02:03,5"))
        assertEquals(((10 * 3600 + 2 * 60 + 3) * 1000 + 250).toDouble(), TraceFormats.parseTime("10:02:03.250"))
        assertEquals(1762855323500.0, TraceFormats.parseTime("1762855323.5"))
        assertEquals(1762855323500.0, TraceFormats.parseTime("1762855323500"))
        assertEquals(java.time.Instant.parse("2026-11-11T09:02:03Z").toEpochMilli().toDouble(), TraceFormats.parseTime("2026-11-11T10:02:03+0100"))
        assertNull(TraceFormats.parseTime("soon"))
    }

    @Test
    fun readsOtherLogsWithTheRulesOfAFormat() {
        val f = TraceFormats.load(
            """[{
                "name": "App",
                "time": "^\\[(?<time>[^\\]]+)\\]",
                "rules": [
                    { "kind": "event", "match": "got (?<name>\\w+)" },
                    { "match": "(?<kind>enter|exit)ing (?<machine>\\w+)/(?<name>\\w+)" },
                    { "kind": "transition", "match": "(?<source>\\w+) => (?<target>\\w+)" }
                ],
                "machine": "Door"
            }]""",
        )[1]
        val lines = listOf("[10:00:00.000] got open", "[10:00:00.001] entering Car/Opened", "[10:00:00.002] Closed => Opened", "noise")
        val got = lines.mapIndexed { i, l -> f.parse(l, i)?.let { r -> "${r.kind.text}|${r.machine}|${r.name}|${r.source ?: ""}|${r.target ?: ""}|${r.time!!.toLong()}" } }
        assertEquals(listOf("event|Door|open|||36000000", "enter|Car|Opened|||36000001", "transition|Door|Closed -> Opened|Closed|Opened|36000002", null), got)
    }

    @Test
    fun reportsBadFormatsAndPicksTheFormatRecognizingTheMostLines() {
        assertFailsWith<TraceFormatException> { TraceFormats.compile(Json.parse("""{ "name": "X", "rules": [{ "kind": "jump", "match": "a" }] }""")) }
        assertTrue(assertFailsWith<TraceFormatException> { TraceFormats.compile(Json.parse("""{ "name": "X", "rules": [{ "match": "a(" }] }""")) }.message!!.contains("not a valid regular expression"))
        assertTrue(assertFailsWith<TraceFormatException> { TraceFormats.compile(Json.parse("""{ "name": "X", "rules": [{ "match": "a" }] }""")) }.message!!.contains("needs a kind"))
        val errors = mutableListOf<String>()
        val formats = TraceFormats.load("""[{ "name": "Bad", "rules": [] }, { "name": "Mine", "rules": [{ "kind": "enter", "match": "-> (?<name>\\w+)" }] }]""", errors)
        assertEquals(listOf("FSM Editor (built-in)", "Mine"), formats.map { it.name })
        assertEquals(listOf("Trace format 'Bad' has no rules."), errors)
        assertEquals("Mine", TraceFormats.detect(formats, listOf("-> A", "-> B", "fsm M enter s1 A")).name)
        assertEquals("FSM Editor (built-in)", TraceFormats.detect(formats, listOf("nothing here")).name)
        errors.clear()
        assertEquals(1, TraceFormats.load("[{", errors).size)
        assertTrue(errors.single().startsWith("The trace formats setting is not valid JSON"))
    }

    // ---------------------------------------------------------------- replay

    @Test
    fun replaysALogWrittenByTheOnTraceHook() {
        val entries = timeline(tracedLog, TraceFormats.builtIn, mapOf("Traced" to instance("Traced", traced())))
        assertNull(at(entries, -1, "Traced"))
        val started = at(entries, 1, "Traced")!!
        assertEquals(listOf("S"), started.active)
        assertEquals(listOf("tInit"), started.taken)
        assertEquals(listOf("S"), started.current)

        val firstE = entries.indexOfFirst { it.record.kind == TraceKind.Event }
        val toY = at(entries, firstE + 4, "Traced")!! // event, exit S, tSJ, tJY, enter Y
        assertEquals(listOf("Y"), toY.active)
        assertEquals(listOf("tSJ", "tJY"), toY.taken)
        assertEquals(listOf("j"), toY.via)
        assertEquals("e", toY.event)

        val end = at(entries, entries.size - 1, "Traced")!!
        assertEquals(listOf("X"), end.active)
        assertEquals(listOf("tSJ", "tJC", "tCX"), end.taken)
        assertEquals(listOf("c", "j"), end.via.sorted())
        assertEquals(listOf("X"), end.current)

        // Going back in time: the middle of the second step.
        val mid = at(entries, entries.size - 3, "Traced")!! // after tJC
        assertEquals(emptyList<String>(), mid.active)
        assertEquals(listOf("tSJ", "tJC"), mid.taken)
    }

    @Test
    fun showsASubmachineAsAnActiveStateInItsParentAndItsStatesInItsOwnInstance() {
        val lines = File(examples("demo.log")).readLines().takeWhile { !it.contains("Order event cancel") }
        val instances = mapOf("Order" to instance("Order", example("Order.fsm")), "Order.Payment" to instance("Order.Payment", example("Payment.fsm"), "/Payment.fsm"))
        val entries = timeline(lines, TraceFormats.builtIn, instances)
        val output = TraceReplay.replay(entries, entries.size - 1, kindsOf(entries))
        assertEquals(listOf("o_pay"), output.single { it.first.key == "Order" }.second.active)
        val payment = output.single { it.first.key == "Order.Payment" }
        assertEquals(listOf("p_auth"), payment.second.active)
        assertEquals("/Payment.fsm", payment.first.file)
    }

    @Test
    fun findsStatesAndTransitionsByNameAndFollowsTransitionsWhenTheLogHasNoEntries() {
        val model = traced()
        val ix = ModelIndex(model)
        assertEquals("S", TraceReplay.findState(ix, "S")?.id)
        assertNull(TraceReplay.findState(ix, "nope"))
        assertEquals(listOf("tSJ", "tJC", "tCX"), TraceReplay.findTransitions(ix, "", "S -> X (e)").map { it.id })
        assertEquals(listOf("tSJ", "tJY"), TraceReplay.findTransitions(ix, "", "", "S", "Y").map { it.id })

        val f = TraceFormats.load("""[{ "name": "Moves", "rules": [{ "kind": "transition", "match": "(?<source>\\w+) -> (?<target>\\w+)" }] }]""")[1]
        val entries = timeline(listOf("S -> X", "X -> S", "S -> Y"), f, emptyMap(), instance("Traced", model))
        val h = at(entries, 2, "Traced")!!
        assertEquals(listOf("Y"), h.active)
        assertEquals(listOf("tSJ", "tJY"), h.taken)
        assertEquals(listOf("X"), at(entries, 0, "Traced")!!.active)
    }

    @Test
    fun entersTheEnclosingStatesAndLeavesWhatElseWasActiveInTheRegion() {
        val m = FsmModel().apply {
            name = "Nest"
            regions.add(Region("r_root"))
        }
        fun s(id: String, parent: String, regions: Int = 0) = m.vertices.add(
            Vertex(id, VertexType.State, id, parent, w = 10.0, h = 10.0).apply { this.regions = (0 until regions).map { Region("${id}_r$it") }.toMutableList() },
        )
        s("A", "r_root")
        s("C", "r_root", 2)
        s("C1", "C_r0")
        s("C2", "C_r0")
        s("D1", "C_r1")
        val entries = timeline(
            listOf("fsm Nest enter A", "fsm Nest enter C1", "fsm Nest enter D1", "fsm Nest enter C2", "fsm Nest exit C", "fsm Nest enter C::C1"),
            TraceFormats.builtIn, mapOf("Nest" to instance("Nest", m)),
        )
        fun active(i: Int) = at(entries, i, "Nest")!!.active.sorted()
        assertEquals(listOf("C", "C1"), active(1))
        assertEquals(listOf("C", "C2", "D1"), active(3))
        assertEquals(emptyList<String>(), active(4))
        assertEquals(listOf("C", "C1"), active(5))
    }

    // ---------------------------------------------------------------- session

    private fun exampleSession() = TraceSession().apply {
        setMachines(listOf("MediaPlayer.fsm", "Order.fsm", "Payment.fsm").map { TraceMachine(examples(it), example(it)) }) { null }
    }

    private fun view(s: TraceSession, file: String) = s.views()[s.viewKey(examples(file))]!!

    @Test
    fun replaysTheDemoLogOnTheExamples() {
        val s = exampleSession()
        val log = s.open(examples("demo.log"))!!
        assertNull(s.open(examples("demo.log")))
        assertTrue(s.readNew(log))
        assertEquals(TraceFormats.BUILT_IN_NAME, log.format.name)
        assertEquals(153, s.entries.size)
        assertEquals(152, s.cursor)
        assertEquals(emptyList<String>(), s.problems)
        assertFalse(log.needsMachine)

        assertEquals(listOf("o_final"), view(s, "Order.fsm").instances.single().active)
        assertEquals(-1, view(s, "Order.fsm").instances.single().color)
        assertEquals(listOf("v_final"), view(s, "MediaPlayer.fsm").instances.single().active)
        // Payment runs alone first, then inside Order: two instances, colored in that order.
        val payment = view(s, "Payment.fsm")
        assertEquals(listOf("Payment|0", "Order.Payment|1"), payment.instances.map { "${it.label}|${it.color}" })
        assertEquals(listOf(0), payment.active["p_final"]?.toList())

        // Back in time, to the second checkout: Order in its Payment state, the Payment instance inside it authorizing.
        val at = s.entries.withIndex().filter { (_, e) -> e.record.machine == "Order.Payment" && e.record.id == "p_auth" && e.record.kind == TraceKind.Enter }[1].index
        s.seek(at)
        assertFalse(s.live)
        assertEquals(listOf("o_pay"), view(s, "Order.fsm").instances.single().active)
        assertEquals(listOf("p_auth"), view(s, "Payment.fsm").instances.single { it.label == "Order.Payment" }.active)
        assertTrue("p_auth" in view(s, "Payment.fsm").current)
        assertEquals("▶ Trace · event checkout", view(s, "Order.fsm").statusText)
        // Order's event is the step of the Payment instance inside it too: not its own last event (cancel), nor that transition.
        assertEquals("▶ Trace · event checkout", view(s, "Payment.fsm").statusText)
        assertEquals(listOf("pt1"), view(s, "Payment.fsm").instances.single { it.label == "Order.Payment" }.taken)
        val rows = s.rows()
        val cur = rows.single { it.index == s.cursor }
        assertTrue(rows.all { it.resolved })
        assertEquals("Order.Payment", cur.machine)
        assertEquals(1, cur.color)
    }

    @Test
    fun playsAtTheSpeedOfTheLog() {
        val s = exampleSession()
        s.readNew(s.open(examples("demo.log"))!!)
        s.play()
        assertTrue(s.playing)
        assertEquals(-1, s.cursor)
        assertEquals(0.0, s.nextDelay())
        s.advance()
        assertEquals(150.0, s.nextDelay()) // 150 ms between the first two lines
        s.speed = 2.0
        assertEquals(75.0, s.nextDelay())
        s.seek(s.entries.size - 1)
        assertFalse(s.playing)
        assertNull(s.nextDelay())
        assertFalse(s.live) // reaching the end by hand doesn't follow the log
        s.goLive()
        assertTrue(s.live)
        // Played to the end, it follows the log.
        s.play()
        assertFalse(s.live)
        while (s.nextDelay() != null) s.advance()
        assertFalse(s.playing)
        assertTrue(s.live)
        assertEquals(s.entries.size - 1, s.cursor)
    }

    @Test
    fun followsALogAsItGrowsReadingCompleteLinesOnly() {
        val dir = Files.createTempDirectory("fsm-trace").toFile()
        val file = File(dir, "app.log")
        file.writeText("2026-11-11T10:00:00.000Z fsm Order transition ot1 initial -> Cart\n2026-11-11T10:00:00.100Z fsm Order enter o_ca")
        val s = exampleSession()
        val log = s.open(file.path)!!
        s.readNew(log)
        assertEquals(1, s.entries.size)
        file.appendText("rt Cart\n")
        assertTrue(s.readNew(log))
        assertEquals(2, s.entries.size)
        assertEquals(1, s.cursor)
        assertEquals(listOf("o_cart"), view(s, "Order.fsm").instances.single().active)
        assertFalse(s.readNew(log))
        // Truncated (rotated): read again from the start.
        file.writeText("2026-11-11T10:00:01.000Z fsm Order transition ot1 initial -> Cart\n")
        assertTrue(s.readNew(log))
        assertEquals(1, s.entries.size)
        file.delete()
        assertTrue(s.readNew(log))
        assertEquals("app.log does not exist.", log.error)
        dir.deleteRecursively()
    }

    @Test
    fun asksOnceForTheMachineOfLinesNamingNone() {
        val dir = Files.createTempDirectory("fsm-trace").toFile()
        val file = File(dir, "door.log")
        file.writeText("-> Cart\n-> Payment\n-> Nowhere\n")
        val s = exampleSession()
        s.setFormats("""[{ "name": "Arrows", "rules": [{ "kind": "enter", "match": "-> (?<name>\\w+)" }] }]""")
        val log = s.open(file.path)!!
        s.readNew(log)
        assertEquals("Arrows", log.format.name)
        assertTrue(log.needsMachine)
        assertEquals(listOf("Choose the state machine of the lines of door.log that name none.", "3 records designate nothing in their state machine (shown crossed out)."), s.problems)
        assertTrue(s.shouldAsk(log))
        assertFalse(s.shouldAsk(log))
        s.chooseMachine(log, examples("Order.fsm"))
        assertEquals("Order", log.machineName)
        assertEquals(listOf("1 record designates nothing in their state machine (shown crossed out)."), s.problems)
        s.seek(1)
        val order = view(s, "Order.fsm").instances.single()
        assertEquals("Order (door.log)", order.label)
        assertEquals(listOf("o_pay"), order.active)
        assertFalse(s.rows().last().resolved)
        dir.deleteRecursively()
    }

    @Test
    fun reportsMachinesThatCannotBeResolved() {
        val dir = Files.createTempDirectory("fsm-trace").toFile()
        val file = File(dir, "x.log")
        file.writeText("fsm Gate enter a A\nfsm Order.Nope enter b B\n")
        val s = exampleSession()
        s.readNew(s.open(file.path)!!)
        assertEquals(
            listOf(
                "No state machine named 'Gate' in the workspace (Gate).",
                "Order has no submachine state 'Nope' (Order.Nope).",
                "2 records designate nothing in their state machine (shown crossed out).",
            ),
            s.problems,
        )
        dir.deleteRecursively()
    }

    @Test
    fun formatsTimesForDisplay() {
        assertEquals("", TraceSession.formatTime(0.0))
        assertEquals("10:02:03.250", TraceSession.formatTime(((10 * 3600 + 2 * 60 + 3) * 1000 + 250).toDouble()))
        assertEquals("10:02:03.120", TraceSession.formatTime(localMillis(2026, 11, 11, 10, 2, 3, 120)))
    }
}
