package com.vincegosoftware.fsmeditor.core

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Builds an [FsmModel] in code (ModelBuilder of the VS Code edition's test/unit/harness.ts). */
internal class TestBuilder(name: String, kind: MachineKind = MachineKind.Behavioral) {
    val model = FsmModel().apply {
        this.name = name
        this.kind = kind
        regions.add(Region("r_root"))
    }
    private var n = 0

    fun vertex(type: VertexType, parent: String = "r_root", id: String? = null, extra: (Vertex) -> Unit = {}): String {
        val v = Vertex(id ?: "v${++n}", type, "", parent, 0.0, 0.0, 10.0, 10.0)
        extra(v)
        model.vertices.add(v)
        return v.id
    }

    /** A state; [regions] > 0 makes it composite, with region ids `<id>_r0`, `<id>_r1`, ... */
    fun state(name: String, parent: String = "r_root", regions: Int = 0, id: String? = null, extra: (Vertex) -> Unit = {}): String =
        vertex(VertexType.State, parent, id ?: name) { v ->
            v.name = name
            v.regions = MutableList(regions) { Region("${v.id}_r$it") }
            extra(v)
        }

    fun t(source: String, target: String, id: String? = null, extra: (Transition) -> Unit = {}): String {
        val t = Transition(id ?: "t${++n}", source, target)
        extra(t)
        model.transitions.add(t)
        return t.id
    }

    /** Initial pseudostate of [region] targeting [target]. */
    fun init(target: String, region: String = "r_root") {
        t(vertex(VertexType.Initial, region), target)
    }
}

internal object TestFiles {
    val EXAMPLES: String = System.getProperty("fsm.examples") ?: "../examples"

    /** fsm-editor-common's sidecar and its interface's cases, copied into plugin/cli by scripts/fetch-common.sh. */
    val CLI: File = File(System.getProperty("fsm.cli") ?: "../plugin/cli")
    val API_CASES: File = File(CLI, "api-cases/cases.jsonl")

    init {
        // The sidecar of this machine's platform folder (scripts/fetch-common.sh copies every platform's from a
        // release), unless given.
        if (Common.executablePath == null && System.getenv("FSM_EDITOR_COMMON") == null) {
            Common.executablePath = File(CLI, Common.platform).listFiles { f -> f.name.startsWith("fsm-editor-common") }?.firstOrNull()?.path
        }
    }

    fun example(file: String) = File(EXAMPLES, file).readText()

    /** A machine's two files read; fails on problems. */
    fun read(model: String, layout: String? = null): ReadMachine {
        val r = Format.readMachine(model, layout)
        assertFalse(r.failed, r.problems?.joinToString("; ") ?: "")
        return r
    }

    /** An example's model, with its layout. */
    fun exampleModel(name: String): FsmModel = read(example("$name.fsm"), example("$name.fsm.layout")).model!!

    /** Writes a copy of a model (generated names given), as the editor saves it. */
    fun write(model: FsmModel): Written {
        val w = Format.writeMachine(model.copy())
        assertTrue(w.lost.isEmpty(), w.lost.joinToString("; "))
        return w
    }

    /** A model saved and read again. */
    fun roundTrip(model: FsmModel): FsmModel {
        val w = write(model)
        return read(w.model, w.layout).model!!
    }
}
