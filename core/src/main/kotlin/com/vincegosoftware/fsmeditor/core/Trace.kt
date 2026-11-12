package com.vincegosoftware.fsmeditor.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.IdentityHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The Trace Player's logic, as src/trace/format.ts, replay.ts and player.ts of the VS Code edition (ported from
// Trace.cs of the Visual Studio edition): log formats, replaying records on the machines, and the session (logs,
// timeline, cursor) behind the Trace Player tool window.

enum class TraceKind {
    Event, Enter, Exit, Transition;

    val text: String get() = name.lowercase()

    companion object {
        fun parse(s: String?): TraceKind? = when (s) {
            "event" -> Event
            "enter" -> Enter
            "exit" -> Exit
            "transition" -> Transition
            else -> null
        }
    }
}

/** One line of a log that a format recognized. */
class TraceRecord(
    val kind: TraceKind,
    /** Milliseconds (since 1970, or since midnight for times without a date); null when the line has none. */
    val time: Double?,
    /** Machine name, followed for a submachine by the submachine states leading to it (`Order.Payment`); "" when the line has none. */
    val machine: String,
    /** xmi:id of the state or transition, or the event's name. */
    val id: String,
    /** Name of the state, transition or event. */
    val name: String,
    /** Transitions without an id: the names of their source and target. */
    val source: String? = null,
    val target: String? = null,
    /** 0-based line number in the file. */
    val line: Int,
    /** The line as written, for display. */
    val text: String,
)

class TraceFormatException(message: String) : Exception(message)

/** How the lines of a log are recognized: the built-in format, or one of the Trace formats setting. */
interface TraceFormat {
    val name: String
    val builtIn: Boolean

    /** The record of a line, or null when the format doesn't recognize it. */
    fun parse(text: String, line: Int): TraceRecord?
}

/**
 * The trace formats. The built-in one is what an `onTrace` hook of the generated code can write, after any prefix of
 * the application's logger: `2026-11-11T10:02:03.120Z INFO fsm Order.Payment enter p_auth Authorizing`. Others come
 * from the setting: a JSON array of `{ name, rules: [{ kind?, match }], time?, machine? }`, the rules being regular
 * expressions with named groups (time, machine, id, name, source, target, kind).
 */
object TraceFormats {
    const val BUILT_IN_NAME = "FSM Editor (built-in)"

    /** An ISO 8601 date and time, or a time of day, as loggers print them. */
    private val isoTime = Pattern.compile("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?|\b\d{2}:\d{2}:\d{2}(?:[.,]\d+)?""")
    private val builtInLine = Pattern.compile("""(?:^|\s)fsm\s+(\S+)\s+(event|enter|exit|transition)\s+(\S+)(?:\s+(.*?))?\s*$""")
    private val number = Regex("""^\d+(\.\d+)?$""")
    private val timeOfDay = Regex("""^(\d{2}):(\d{2}):(\d{2})(?:[.,](\d+))?$""")
    private val compactOffset = Regex("""([+-]\d{2})(\d{2})$""")

    val builtIn: TraceFormat = object : TraceFormat {
        override val name = BUILT_IN_NAME
        override val builtIn = true

        override fun parse(text: String, line: Int): TraceRecord? {
            val m = builtInLine.matcher(text)
            if (!m.find()) return null
            val t = isoTime.matcher(text.substring(0, m.start()))
            return TraceRecord(
                kind = TraceKind.parse(m.group(2))!!,
                time = if (t.find()) parseTime(t.group()) else null,
                machine = m.group(1),
                id = m.group(3),
                name = m.group(4) ?: m.group(3),
                line = line,
                text = text,
            )
        }
    }

    /**
     * Milliseconds of a time as written in a log: an ISO 8601 date and time (since 1970), a time of day (since
     * midnight), or a number (milliseconds, or seconds when below 10^11, as Unix timestamps in seconds are). Null otherwise.
     */
    fun parseTime(text: String?): Double? {
        val s = (text ?: "").trim()
        if (s.isEmpty()) return null
        if (number.matches(s)) {
            val n = s.toDouble()
            return if (n < 1e11) n * 1000 else n
        }
        timeOfDay.matchEntire(s)?.let { day ->
            val (h, m, sec, frac) = day.destructured
            val ms = if (frac.isNotEmpty()) "0.$frac".toDouble() * 1000 else 0.0
            return ((h.toInt() * 60 + m.toInt()) * 60 + sec.toInt()) * 1000 + ms
        }
        val iso = s.replace(' ', 'T').replace(',', '.')
        // Like JavaScript's Date.parse: a date alone is UTC, a date and time without an offset local time.
        val instant = try {
            OffsetDateTime.parse(compactOffset.replace(iso, "$1:$2")).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (_: DateTimeParseException) {
                    return null
                }
            }
        }
        return floor(instant.epochSecond * 1000.0 + instant.nano / 1_000_000)
    }

    private class Rule(val kind: TraceKind?, val re: Pattern)

    private fun Matcher.named(name: String): String? = try {
        group(name)
    } catch (_: IllegalArgumentException) {
        null
    }

    private class CompiledFormat(override val name: String, val rules: List<Rule>, val timeRe: Pattern?, val fixedMachine: String) : TraceFormat {
        override val builtIn = false

        override fun parse(text: String, line: Int): TraceRecord? {
            for (rule in rules) {
                val m = rule.re.matcher(text)
                if (!m.find()) continue
                val kind = rule.kind ?: TraceKind.parse(m.named("kind")?.trim()?.lowercase()) ?: continue
                var timeText = m.named("time")
                if (timeText == null && timeRe != null) {
                    val tm = timeRe.matcher(text)
                    if (tm.find()) timeText = tm.named("time")
                }
                val id = m.named("id")?.trim() ?: ""
                val nm = m.named("name")?.trim() ?: ""
                val source = m.named("source")?.trim()
                val target = m.named("target")?.trim()
                if (id.isEmpty() && nm.isEmpty() && !(kind == TraceKind.Transition && !source.isNullOrEmpty() && !target.isNullOrEmpty())) continue
                val machine = m.named("machine")?.trim()
                return TraceRecord(
                    kind = kind,
                    time = if (timeText != null) parseTime(timeText) else null,
                    machine = if (machine.isNullOrEmpty()) fixedMachine else machine,
                    id = id,
                    name = nm.ifEmpty { id.ifEmpty { "$source -> $target" } },
                    source = source,
                    target = target,
                    line = line,
                    text = text,
                )
            }
            return null
        }
    }

    /** A format of the setting (a JSON object, parsed), compiled. Throws a [TraceFormatException] for a bad specification. */
    fun compile(spec: Any?): TraceFormat {
        val o = spec as? Map<*, *>
        val name = (o?.get("name") as? String)?.trim()?.ifEmpty { null } ?: "Unnamed format"
        fun regex(source: Any?, what: String): Pattern {
            if (source !is String || source.isEmpty()) throw TraceFormatException("Trace format '$name': $what needs a regular expression.")
            return try {
                Pattern.compile(source)
            } catch (e: PatternSyntaxException) {
                throw TraceFormatException("Trace format '$name': $what is not a valid regular expression: ${e.description}")
            }
        }
        val list = o?.get("rules") as? List<*>
        if (list.isNullOrEmpty()) throw TraceFormatException("Trace format '$name' has no rules.")
        val rules = list.mapIndexed { i, item ->
            val r = item as? Map<*, *>
            val k = r?.get("kind")
            val kindText = if (k == null || k == "") null else k.toString()
            val kind = kindText?.let { TraceKind.parse(it) }
            if (kindText != null && kind == null) throw TraceFormatException("Trace format '$name': rule ${i + 1} has kind '$kindText'; use event, enter, exit or transition.")
            val re = regex(r?.get("match"), "rule ${i + 1}")
            if (kind == null && !re.pattern().contains("?<kind>")) throw TraceFormatException("Trace format '$name': rule ${i + 1} needs a kind, or a 'kind' group.")
            Rule(kind, re)
        }
        val time = o["time"] as? String
        val timeRe = if (!time.isNullOrEmpty()) regex(time, "time") else null
        val fixedMachine = (o["machine"] as? String)?.trim() ?: ""
        return CompiledFormat(name, rules, timeRe, fixedMachine)
    }

    /** The built-in format, then those of the setting (a JSON array); bad ones are left out and reported in [errors]. */
    fun load(settingJson: String?, errors: MutableList<String>? = null): List<TraceFormat> {
        val output = mutableListOf(builtIn)
        if (settingJson.isNullOrBlank()) return output
        val specs = try {
            Json.parse(settingJson)
        } catch (e: Json.FormatException) {
            errors?.add("The trace formats setting is not valid JSON: ${e.message}")
            return output
        }
        for (spec in specs as? List<*> ?: emptyList<Any>()) {
            try {
                output.add(compile(spec))
            } catch (e: TraceFormatException) {
                errors?.add(e.message ?: "")
            }
        }
        return output
    }

    /** The format recognizing the most of [lines] (the first one on a tie, so the built-in format when none does). */
    fun detect(formats: List<TraceFormat>, lines: List<String>): TraceFormat {
        var best = formats[0]
        var bestCount = -1
        for (f in formats) {
            val n = lines.indices.count { f.parse(lines[it], it) != null }
            if (n > bestCount) {
                best = f
                bestCount = n
            }
        }
        return best
    }
}

/** A machine instance the records are about: a machine, or a submachine reached through submachine states (`Order.Payment`). */
class TraceInstance(
    /** Its path, e.g. `Order` or `Order.Payment`, or another key unique in the timeline. */
    val key: String,
    /** Its name for display, when not its key. */
    var label: String?,
    /** Full path of its .fsm file. */
    val file: String,
    val model: FsmModel,
    val index: ModelIndex,
) {
    /** What each record designates in its model, computed once. */
    internal val resolved = IdentityHashMap<TraceRecord, TraceReplay.Resolved>()

    fun with(key: String, label: String) = TraceInstance(key, label, file, model, index)
}

class TimelineEntry(
    val record: TraceRecord,
    /** Index of the log it comes from. */
    val trace: Int = 0,
    /** Its effective time (its own, or the previous record's). */
    val time: Double = 0.0,
    /** Null when the record's machine could not be resolved. */
    val instance: TraceInstance?,
)

/** What a diagram highlights for one instance. */
class TraceHighlight(
    /** Active states (and final states). */
    val active: List<String>,
    /** Transitions taken by the current step: since the instance's last event, or the last one when it has no events. */
    val taken: List<String>,
    /** Pseudostates on the way of the taken transitions (choices, junctions, forks, ...). */
    val via: List<String>,
    /** Elements of the current record, when it is about this instance. */
    val current: List<String>,
    /** The event of the current step, when there is one. */
    val event: String?,
) {
    /** Set for a diagram: the instance's label, and its color (an index in a palette, or -1 when its machine has only one instance). */
    var label: String = ""
    var color: Int = -1
}

/** Replays records on the machines they are about: active states, transitions taken by the current step, current element. */
object TraceReplay {
    internal class Resolved(val state: Vertex? = null, val transitions: List<Transition> = emptyList())

    private fun isState(v: Vertex?): Boolean = v != null && (v.type == VertexType.State || v.type == VertexType.Final)

    /** Vertices a compound transition goes through: pseudostates and connection point references. */
    private fun isWaypoint(v: Vertex): Boolean = v.type.isPseudostate || v.type == VertexType.ConnectionPointRef

    private val nonIdent = Regex("[^A-Za-z0-9_]+")
    private val edgeUnderscores = Regex("^_+|_+$")
    private val pathSeparators = Regex("""::|\.|/""")
    private val way = Regex("""^\s*(.+?)\s*->\s*(.+?)(?:\s+\(.*\))?\s*$""")

    /** Identifier form of a name, as the code generator makes it (letters, digits, `_`). */
    internal fun ident(text: String): String {
        val s = edgeUnderscores.replace(nonIdent.replace(text.trim(), "_"), "")
        return if (s.isNotEmpty() && s[0].isDigit()) "_$s" else s
    }

    private fun sameName(v: Vertex, name: String) = v.name == name || (v.name.isNotEmpty() && ident(v.name) == name)

    /** The state designated by [reference]: an xmi:id, a name, or a path of names (`On::Playing`, `On.Playing`, `On/Playing`). */
    fun findState(ix: ModelIndex, reference: String?): Vertex? {
        if (reference.isNullOrEmpty()) return null
        val byId = ix.vertex(reference)
        if (isState(byId)) return byId
        val states = ix.model.vertices.filter { isState(it) }
        states.firstOrNull { sameName(it, reference) }?.let { return it }
        val path = reference.split(pathSeparators).filter { it.isNotEmpty() }
        if (path.size < 2) return null
        val last = path.last()
        return states.firstOrNull { v ->
            if (!sameName(v, last)) return@firstOrNull false
            // The other names, innermost first, are enclosing states in that order (not necessarily direct parents).
            val ancestors = ix.ancestors(v)
            var k = 0
            for (i in path.size - 2 downTo 0) {
                while (k < ancestors.size && !sameName(ancestors[k], path[i])) k++
                if (k++ >= ancestors.size) return@firstOrNull false
            }
            true
        }
    }

    /**
     * The transitions designated by a transition record: the one with its xmi:id, else the way from its source to its
     * target (given by [source]/[target], or a name like `A -> B (e)`) through pseudostates, shortest first.
     */
    fun findTransitions(ix: ModelIndex, id: String, name: String, source: String? = null, target: String? = null): List<Transition> {
        ix.model.transitions.firstOrNull { it.id == id }?.let { return listOf(it) }
        var src = source
        var tgt = target
        if (src.isNullOrEmpty() || tgt.isNullOrEmpty()) {
            val m = way.find(name.ifEmpty { id }) ?: return emptyList()
            src = m.groupValues[1]
            tgt = m.groupValues[2]
        }
        val from = findVertex(ix, src) ?: return emptyList()
        val to = findVertex(ix, tgt) ?: return emptyList()
        // Breadth-first through pseudostates, so the shortest way wins.
        val queue = ArrayDeque<Pair<Vertex, List<Transition>>>()
        queue.add(from to emptyList())
        val seen = hashSetOf(from.id)
        while (queue.isNotEmpty()) {
            val (at, path) = queue.removeFirst()
            for (t in ix.outgoing(at.id)) {
                val next = ix.vertex(t.target) ?: continue
                if (next.id == to.id) return path + t
                if (isWaypoint(next) && seen.add(next.id)) queue.add(next to path + t)
            }
        }
        return emptyList()
    }

    /** A state by findState(), or any vertex by xmi:id or name (transitions may start or end at pseudostates). */
    private fun findVertex(ix: ModelIndex, reference: String): Vertex? =
        findState(ix, reference) ?: ix.vertex(reference) ?: ix.model.vertices.firstOrNull { sameName(it, reference) }

    internal fun resolve(entry: TimelineEntry): Resolved {
        val inst = entry.instance!!
        return inst.resolved.getOrPut(entry.record) { resolveIn(inst.index, entry.record) }
    }

    private fun resolveIn(ix: ModelIndex, rec: TraceRecord): Resolved = when (rec.kind) {
        TraceKind.Enter, TraceKind.Exit -> Resolved(state = findState(ix, rec.id) ?: findState(ix, rec.name))
        TraceKind.Transition -> Resolved(transitions = findTransitions(ix, rec.id, rec.name, rec.source, rec.target))
        TraceKind.Event -> Resolved()
    }

    /** Whether the record designates something in its instance's model (events always do). */
    fun isResolved(entry: TimelineEntry): Boolean {
        if (entry.instance == null) return false
        val r = resolve(entry)
        return entry.record.kind == TraceKind.Event || r.state != null || r.transitions.isNotEmpty()
    }

    private class Run(val inst: TraceInstance) {
        /** Active states, in the order they became active. */
        val active = LinkedHashSet<String>()
        var taken = mutableListOf<String>()
        var event: String? = null
    }

    /**
     * The highlights of every instance (in the order they first appear) after the entries up to [cursor] (included; -1
     * for none). [kinds] tells, for each instance key, which kinds of records the whole timeline has for it: without
     * events, a step is a single transition; without `enter` records, the active states follow the transitions' targets.
     */
    fun replay(entries: List<TimelineEntry>, cursor: Int, kinds: Map<String, Set<TraceKind>>): List<Pair<TraceInstance, TraceHighlight>> {
        val runs = LinkedHashMap<String, Run>()
        val last = min(cursor, entries.size - 1)
        for (i in 0..last) {
            val e = entries[i]
            val inst = e.instance ?: continue
            val run = runs.getOrPut(inst.key) {
                // A submachine's first records belong to the step of the machine containing it.
                val parent = runs.values.filter { inst.key.startsWith(it.inst.key + ".") }.maxByOrNull { it.inst.key.length }
                Run(inst).apply { event = parent?.event }
            }
            val has = kinds[inst.key]
            val r = resolve(e)
            when (e.record.kind) {
                TraceKind.Event -> {
                    // A step starts, in the submachines of the instance too (`Order.Payment` for `Order`).
                    for (r in runs.values) {
                        if (r !== run && !r.inst.key.startsWith(run.inst.key + ".")) continue
                        r.taken = mutableListOf()
                        r.event = e.record.name
                    }
                }
                TraceKind.Enter -> r.state?.let { enter(run, it) }
                TraceKind.Exit -> r.state?.let { leave(run, it) }
                TraceKind.Transition -> {
                    val ids = r.transitions.map { it.id }
                    if (has != null && TraceKind.Event in has) run.taken.addAll(ids) else run.taken = ids.toMutableList()
                    if (!(has != null && TraceKind.Enter in has) && r.transitions.isNotEmpty()) {
                        val target = run.inst.index.vertex(r.transitions.last().target)
                        if (isState(target)) enter(run, target!!)
                    }
                }
            }
        }
        val cur = if (last >= 0) entries[last] else null
        return runs.values.map { run ->
            val ix = run.inst.index
            val taken = run.taken.distinct()
            val via = LinkedHashSet<String>()
            for (id in taken) {
                val t = ix.transition(id) ?: continue
                for (end in listOf(t.source, t.target)) {
                    val v = ix.vertex(end)
                    if (v != null && isWaypoint(v)) via.add(v.id)
                }
            }
            val current = mutableListOf<String>()
            if (cur?.instance != null && cur.instance.key == run.inst.key) {
                val r = resolve(cur)
                r.state?.let { current.add(it.id) }
                current.addAll(r.transitions.map { it.id })
            }
            run.inst to TraceHighlight(run.active.toList(), taken, via.toList(), current, run.event)
        }
    }

    /** Enters [v]: its enclosing states become active, and whatever else was active in its region is left. */
    private fun enter(run: Run, v: Vertex) {
        val ix = run.inst.index
        for (s in ix.ancestors(v).reversed() + v) {
            if (s.id in run.active) continue
            for (id in run.active.toList()) {
                val other = ix.vertex(id)
                if (other != null && other.parent == s.parent && other.id != s.id) leave(run, other)
            }
            run.active.add(s.id)
        }
    }

    /** Leaves [v] and everything active inside it. */
    private fun leave(run: Run, v: Vertex) {
        val ix = run.inst.index
        run.active.remove(v.id)
        for (id in run.active.toList()) {
            val inner = ix.vertex(id)
            if (inner != null && ix.isInside(inner, v.id)) run.active.remove(id)
        }
    }
}

/**
 * What a diagram shows of a trace: the highlights of the instances of its machine, merged by element id with the
 * colors of the instances where it is active, taken (transitions and the pseudostates on their way) or current.
 */
class TraceView(instances: List<TraceHighlight>) {
    val instances: List<TraceHighlight> = instances.sortedBy { it.color }
    val active = HashMap<String, MutableList<Int>>()
    val taken = HashMap<String, MutableList<Int>>()
    val current = HashSet<String>()

    /** The event of the instance of the current record, else of the first instance that has one; "" without. */
    val event: String

    init {
        for (inst in this.instances) {
            for (id in inst.active) active.getOrPut(id) { mutableListOf() }.add(inst.color)
            for (id in inst.taken + inst.via) taken.getOrPut(id) { mutableListOf() }.add(inst.color)
            current.addAll(inst.current)
        }
        val at = this.instances.firstOrNull { it.current.isNotEmpty() } ?: this.instances.firstOrNull { !it.event.isNullOrEmpty() }
        event = at?.event ?: ""
    }

    /** The status bar text: `▶ Trace`, and the event of the current step. */
    val statusText: String get() = "▶ Trace" + if (event.isNotEmpty()) " · event $event" else ""

    companion object {
        /** Instance colors of the palette (see the editors' themes). */
        const val COLORS = 6
    }
}

/** A machine file the session can use, parsed. */
class TraceMachine(
    /** Full path of the .fsm file. */
    val file: String,
    val model: FsmModel,
    val index: ModelIndex = ModelIndex(model),
)

/** A log opened in the Trace Player. */
class TraceLog internal constructor(
    /** Full path of the file. */
    val path: String,
    format: TraceFormat,
) {
    val name: String get() = Paths.get(path).fileName.toString()
    var format: TraceFormat = format
        internal set

    /** Whether the format was picked: detected from the first lines, or chosen. */
    var formatPicked = false
        internal set
    var records = mutableListOf<TraceRecord>()
        private set

    /** Effective time of each record (its own, the previous record's, or the time it was read). */
    var times = mutableListOf<Double>()
        private set

    /** Bytes of the file already read, up to the last line break. */
    var offset = 0L
        internal set
    var lines = 0
        internal set

    /** Whether the file was read once: lines without a time read after that get the time they were read. */
    var followed = false
        internal set
    var error: String? = null
        internal set

    /** The instances of the machine paths its lines name (each log is a run of its own: its instances are its own). */
    internal var instances = LinkedHashMap<String, TraceInstance?>()
        private set

    /** Lines naming no machine: the file of the machine chosen for them ("" before), whether it was asked, and the instance. */
    var machineFile = ""
        internal set
    var machineAsked = false
        internal set
    internal var instance: TraceInstance? = null

    /** Lines naming no machine: the name of the one chosen for them, "" when none yet. */
    val machineName: String get() = instance?.model?.name ?: ""

    /** Whether some of its lines name no machine (so the window offers to choose it). */
    val needsMachine: Boolean get() = records.any { it.machine.isEmpty() }

    /** Forgets what was read. */
    internal fun restart() {
        records = mutableListOf()
        times = mutableListOf()
        offset = 0
        lines = 0
        instance = null
        instances = LinkedHashMap()
    }
}

/** A row of the Trace Player's list of records. */
class TraceRow(
    val index: Int,
    val time: String,
    val trace: Int,
    val machine: String,
    val color: Int,
    val kind: TraceKind,
    val name: String,
    val resolved: Boolean,
)

/**
 * The Trace Player's state, without the IDE: the logs, each read with a format and followed as it grows (the IDE
 * calls [readNew] when a file changes); the timeline, their records merged by time (lines without a time take the
 * previous line's); the cursor, which Live keeps on the last record; and the machine instance of every record, `Order`
 * or `Order.Payment`, resolved to the .fsm file of the machine among those the IDE gives ([setMachines]). The lines of
 * a log that name no machine are about one instance of a machine the user chooses ([shouldAsk], [chooseMachine]).
 */
class TraceSession {
    private val windows = System.getProperty("os.name", "").startsWith("Windows")

    /** Key of a file path: case-insensitive on Windows. */
    private fun pathKey(p: String) = if (windows) p.lowercase() else p

    private var formats: List<TraceFormat> = listOf(TraceFormats.builtIn)
    private var formatErrors: List<String> = emptyList()
    private var kinds: Map<String, Set<TraceKind>> = emptyMap()

    /** Color of each instance key: its rank among the instances of its file, or -1 when it is the only one. */
    private var colors: Map<String, Int> = emptyMap()

    /** The machines the IDE gave (open ones first), files read for submachines, and the instances of machine paths. */
    private var machineList: List<TraceMachine> = emptyList()
    private val machinesByFile = HashMap<String, TraceMachine>()
    private var read: (String) -> TraceMachine? = { null }
    private val instances = HashMap<String, TraceInstance?>()

    /** Why an instance could not be resolved, by machine path. */
    private val instanceProblems = HashMap<String, String>()
    private var problemList: List<String> = emptyList()

    val traces = mutableListOf<TraceLog>()
    var entries: List<TimelineEntry> = emptyList()
        private set
    var cursor = -1
        private set

    /** Following the end of the logs as they grow. */
    var live = true
        private set
    var playing = false
        private set
    var speed = 1.0

    val formatList: List<TraceFormat> get() = formats

    /** The setting's errors, then the problems of the instances and records. */
    val problems: List<String> get() = formatErrors + problemList

    val machines: List<TraceMachine> get() = machineList

    /** Reads the formats of the setting (a JSON array). */
    fun setFormats(settingJson: String?) {
        val errors = mutableListOf<String>()
        formats = TraceFormats.load(settingJson, errors)
        formatErrors = errors
        // A format that is gone: back to the built-in one.
        for (t in traces) t.format = formats.firstOrNull { it.name == t.format.name } ?: formats[0]
    }

    /** Gives the machines records can be about, the open ones first; [read] reads other machine files (submachines). */
    fun setMachines(machines: List<TraceMachine>, read: (String) -> TraceMachine?) {
        machineList = machines.toList()
        machinesByFile.clear()
        for (m in machineList) machinesByFile[pathKey(m.file)] = m
        this.read = read
        instances.clear()
        instanceProblems.clear()
        for (t in traces) {
            t.instance = null
            t.instances.clear()
        }
    }

    // ---------------------------------------------------------------- logs

    private fun full(p: String) = Paths.get(p).toAbsolutePath().normalize().toString()

    /** Adds a log (read it with [readNew]); null when it is open already. */
    fun open(path: String): TraceLog? {
        val file = full(path)
        if (traces.any { pathKey(it.path) == pathKey(file) }) return null
        val trace = TraceLog(file, formats[0])
        traces.add(trace)
        live = true
        return trace
    }

    fun close(trace: TraceLog) {
        if (!traces.remove(trace)) return
        if (traces.isEmpty()) {
            playing = false
            cursor = -1
            live = true
        }
        rebuild()
    }

    /** Reads [trace] again from the start with another format (then call [readNew]). */
    fun setFormat(trace: TraceLog, name: String): Boolean {
        val format = formats.firstOrNull { it.name == name } ?: return false
        if (trace !in traces) return false
        trace.format = format
        trace.formatPicked = true
        trace.restart()
        return true
    }

    /** A log that doesn't exist (any more). */
    fun missing(trace: TraceLog) {
        trace.error = "${trace.name} does not exist."
    }

    /**
     * Reads what was added to [trace] since the last read (complete lines only; all of it again when it was truncated
     * or replaced), then rebuilds the timeline. False when nothing changed.
     */
    fun readNew(trace: TraceLog, now: Double? = null): Boolean {
        if (trace !in traces) return false
        var bytes: ByteArray
        try {
            val (b, size) = readFrom(trace.path, trace.offset)
            bytes = b
            if (size < trace.offset) {
                // Truncated or replaced (log rotation): read it again.
                trace.restart()
                bytes = readFrom(trace.path, 0).first
            }
        } catch (_: NoSuchFileException) {
            missing(trace)
            return true
        } catch (e: AccessDeniedException) {
            trace.error = "Cannot read ${trace.name}: access denied."
            return true
        } catch (e: IOException) {
            trace.error = "Cannot read ${trace.name}: ${e.message}"
            return true
        }
        val hadError = trace.error != null
        trace.error = null
        // Complete lines only: a line break byte never occurs inside a UTF-8 character.
        val end = bytes.lastIndexOf(10.toByte()) + 1
        var text = if (end > 0) String(bytes, 0, end, Charsets.UTF_8) else ""
        if (trace.offset == 0L && text.startsWith("﻿")) text = text.substring(1)
        val lines = if (end > 0) text.split('\n') else emptyList()
        trace.offset += end
        val count = max(0, lines.size - 1)
        if (count > 0 && !trace.formatPicked) {
            trace.format = TraceFormats.detect(formats, lines.subList(0, min(count, 1000)))
            trace.formatPicked = true
        }
        var added = false
        val stamp = now ?: System.currentTimeMillis().toDouble()
        for (i in 0 until count) {
            val raw = lines[i].removeSuffix("\r")
            val rec = trace.format.parse(raw, trace.lines++) ?: continue
            val prev = trace.times.lastOrNull()
            // Without a time: the previous record's, else (lines appended while followed) the time it was read.
            trace.times.add(rec.time ?: prev ?: if (trace.followed) stamp else 0.0)
            trace.records.add(rec)
            added = true
        }
        trace.followed = true
        if (added) rebuild()
        return added || hadError
    }

    private fun readFrom(path: String, offset: Long): Pair<ByteArray, Long> {
        Files.newByteChannel(Paths.get(path)).use { ch ->
            val size = ch.size()
            if (size <= offset) return ByteArray(0) to size
            ch.position(offset)
            val buffer = ByteBuffer.allocate((size - offset).toInt())
            while (buffer.hasRemaining()) {
                if (ch.read(buffer) <= 0) break
            }
            return buffer.array().copyOf(buffer.position()) to size
        }
    }

    /**
     * Whether to ask which machine the lines of [trace] naming none are about: true once, when such lines were read
     * and none was chosen.
     */
    fun shouldAsk(trace: TraceLog): Boolean {
        if (trace.machineAsked || trace.machineFile.isNotEmpty() || !trace.needsMachine) return false
        trace.machineAsked = true
        return true
    }

    /** The machine the lines of [trace] naming none are about. */
    fun chooseMachine(trace: TraceLog, file: String) {
        trace.machineFile = full(file)
        trace.instance = null
        rebuild()
    }

    // ---------------------------------------------------------------- machines

    private fun machine(file: String): TraceMachine? {
        machinesByFile[pathKey(file)]?.let { return it }
        val m = read(file) ?: return null
        machinesByFile[pathKey(file)] = m
        return m
    }

    private fun plainIdent(s: String) = s.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").replace(Regex("^_+|_+$"), "")

    /** Resolves the machine of every record not resolved yet. */
    private fun resolveInstances() {
        for (trace in traces) {
            if (trace.machineFile.isNotEmpty() && trace.instance == null) {
                machine(trace.machineFile)?.let { m ->
                    trace.instance = TraceInstance(trace.path + "#", "${m.model.name} (${trace.name})", m.file, m.model, m.index)
                }
            }
            for (rec in trace.records) {
                val path = rec.machine
                if (path.isEmpty() || trace.instances.containsKey(path)) continue
                if (!instances.containsKey(path)) {
                    var problem: String? = null
                    instances[path] = resolveInstance(path) { if (problem == null) problem = it }
                    problem?.let { instanceProblems[path] = it }
                }
                trace.instances[path] = instances[path]?.with("${trace.path}|$path", path)
            }
        }
    }

    private fun instanceOf(trace: TraceLog, rec: TraceRecord): TraceInstance? =
        if (rec.machine.isNotEmpty()) trace.instances[rec.machine] else trace.instance

    /** The instance [path] designates (`Order`, `Order.Payment`), or null when no machine fits. */
    private fun resolveInstance(path: String, problem: (String) -> Unit): TraceInstance? {
        fun named(m: TraceMachine, name: String) = m.model.name == name || plainIdent(m.model.name) == name
        val segments = path.split('.')
        var machine = machineList.firstOrNull { named(it, segments[0]) }
        if (machine == null) {
            problem("No state machine named '${segments[0]}' in the workspace ($path).")
            return null
        }
        for (seg in segments.drop(1)) {
            val state = machine!!.model.vertices.firstOrNull {
                it.type == VertexType.State && it.submachine.isNotEmpty() && (it.id == seg || it.name == seg || plainIdent(it.name) == seg)
            }
            if (state == null) {
                problem("${machine.model.name} has no submachine state '$seg' ($path).")
                return null
            }
            val file = state.submachine.substringBefore('#')
            val (subFile, hrefId) = Hrefs.resolve(machine.file, state.submachine)
            val id = hrefId.ifEmpty { "sm" }
            val sub = machine(subFile)
            if (sub == null || sub.model.id.ifEmpty { "sm" } != id) {
                problem("The submachine of ${machine.model.name}.${state.name.ifEmpty { seg }} ($file) cannot be read ($path).")
                return null
            }
            machine = sub
        }
        return TraceInstance(path, null, machine.file, machine.model, machine.index)
    }

    // ---------------------------------------------------------------- timeline

    /** Merges the logs into the timeline, keeping the cursor on the same record (or on the last one when live). */
    fun rebuild() {
        resolveInstances()
        val at = entries.getOrNull(cursor)
        val list = ArrayList<TimelineEntry>()
        traces.forEachIndexed { t, trace ->
            trace.records.forEachIndexed { i, record ->
                list.add(TimelineEntry(record, t, trace.times[i], instanceOf(trace, record)))
            }
        }
        list.sortWith(compareBy<TimelineEntry> { it.time }.thenBy { it.trace }.thenBy { it.record.line })
        entries = list
        val kindsOf = LinkedHashMap<String, MutableSet<TraceKind>>()
        // The instances of each file, in the order they first appear: their colors.
        val byFile = LinkedHashMap<String, MutableList<String>>()
        for (e in list) {
            val inst = e.instance ?: continue
            val set = kindsOf.getOrPut(inst.key) {
                byFile.getOrPut(pathKey(inst.file)) { mutableListOf() }.add(inst.key)
                HashSet()
            }
            set.add(e.record.kind)
        }
        kinds = kindsOf
        // The same path in several logs: labeled with the log's name.
        val paths = HashMap<String, Int>()
        for (t in traces) for (path in t.instances.keys) paths[path] = (paths[path] ?: 0) + 1
        for (t in traces) {
            for ((path, inst) in t.instances) inst?.label = if (paths[path]!! > 1) "$path (${t.name})" else path
        }
        val colorOf = HashMap<String, Int>()
        for (keys in byFile.values) keys.forEachIndexed { i, key -> colorOf[key] = if (keys.size > 1) i else -1 }
        colors = colorOf
        val found = LinkedHashSet<String>()
        for (trace in traces) {
            if (trace.instance == null && trace.needsMachine) {
                found.add(
                    if (trace.machineFile.isNotEmpty()) "The state machine chosen for ${trace.name} cannot be read."
                    else "Choose the state machine of the lines of ${trace.name} that name none.",
                )
            }
            for (key in trace.records.map { it.machine }.distinct()) {
                if (key.isNotEmpty()) instanceProblems[key]?.let { found.add(it) }
            }
        }
        val unresolved = list.count { !TraceReplay.isResolved(it) }
        if (unresolved > 0) found.add("$unresolved record${if (unresolved == 1) "" else "s"} designate${if (unresolved == 1) "s" else ""} nothing in their state machine (shown crossed out).")
        problemList = found.toList()
        cursor = when {
            live -> list.size - 1
            at != null -> max(-1, list.indexOfFirst { it.record === at.record })
            else -> min(cursor, list.size - 1)
        }
    }

    fun seek(index: Int) {
        live = false
        cursor = max(-1, min(index, entries.size - 1))
        if (playing && cursor >= entries.size - 1) playing = false
    }

    fun step(delta: Int) = seek(cursor + if (delta < 0) -1 else 1)

    fun first() = seek(if (entries.isNotEmpty()) 0 else -1)

    /** Plays from the cursor (from the start at the end); the IDE then calls [nextDelay] and [advance]. */
    fun play() {
        if (entries.isEmpty()) return
        live = false
        if (cursor >= entries.size - 1) cursor = -1
        playing = true
    }

    fun pause() {
        playing = false
    }

    /** While playing, the milliseconds until the next record, at the speed; null at the end, where playing turns to Live. */
    fun nextDelay(): Double? {
        if (!playing || cursor + 1 >= entries.size) {
            if (playing) live = true
            playing = false
            return null
        }
        val next = entries[cursor + 1]
        return if (cursor >= 0) max(0.0, (next.time - entries[cursor].time) / speed) else 0.0
    }

    /** Moves the cursor to the next record while playing. */
    fun advance() {
        if (playing && cursor + 1 < entries.size) cursor++
    }

    fun goLive() {
        playing = false
        live = true
        cursor = entries.size - 1
    }

    /** The highlights at the cursor of every machine file with instances, by path key (see [viewKey]). */
    fun views(): Map<String, TraceView> {
        val byFile = LinkedHashMap<String, MutableList<TraceHighlight>>()
        for ((inst, highlight) in TraceReplay.replay(entries, cursor, kinds)) {
            highlight.label = inst.label ?: inst.key
            highlight.color = colors[inst.key] ?: -1
            byFile.getOrPut(pathKey(inst.file)) { mutableListOf() }.add(highlight)
        }
        return byFile.mapValues { TraceView(it.value) }
    }

    /** The key of a machine file in [views]. */
    fun viewKey(file: String): String = pathKey(full(file))

    /** The records around the cursor. */
    fun rows(window: Int = WINDOW): List<TraceRow> {
        val from = max(0, cursor - window / 2)
        return entries.drop(from).take(window).mapIndexed { k, e ->
            TraceRow(
                index = from + k,
                time = formatTime(e.time),
                trace = e.trace,
                machine = e.instance?.let { it.label ?: it.key } ?: e.record.machine,
                color = e.instance?.let { colors[it.key] } ?: -1,
                kind = e.record.kind,
                name = e.record.name,
                resolved = TraceReplay.isResolved(e),
            )
        }
    }

    /** The cursor's time for display, "" without. */
    val cursorTime: String get() = entries.getOrNull(cursor)?.let { formatTime(it.time) } ?: ""

    companion object {
        /** Records listed around the cursor. */
        const val WINDOW = 150

        /** A record's time for display: the time of day with milliseconds, or "" for lines without a time. */
        fun formatTime(ms: Double): String {
            if (ms == 0.0) return ""
            val day = if (ms > 1e11) {
                val t = Instant.ofEpochMilli(floor(ms).toLong()).atZone(ZoneId.systemDefault()).toLocalTime()
                t.toNanoOfDay() / 1_000_000.0
            } else {
                ms % 86_400_000
            }
            fun pad(n: Double, w: Int = 2) = floor(n).toLong().toString().padStart(w, '0')
            return "${pad(day / 3_600_000)}:${pad(day / 60_000 % 60)}:${pad(day / 1000 % 60)}.${pad(day % 1000, 3)}"
        }
    }
}
