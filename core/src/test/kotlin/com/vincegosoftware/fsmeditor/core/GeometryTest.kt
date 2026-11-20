package com.vincegosoftware.fsmeditor.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry parity with the VS Code edition, the reference: `src/test/geometry` is a copy of its
 * `test/unit/fixtures/geometry` (machines and the SVG its webview exports for them, text measured with
 * [Geometry.estimateLabelWidth]). After a geometry change there, copy the folder here and port the change until this
 * passes.
 */
class GeometryTest {
    private val dir = File(System.getProperty("fsm.geometry") ?: "src/test/geometry")

    @Test
    fun drawsTheGeometryFixturesAsTheReferenceSvgs() {
        val names = dir.listFiles { f -> f.name.endsWith(".fsm") }!!.map { it.name.removeSuffix(".fsm") }.sorted()
        assertTrue(names.size >= 20, "${names.size} fixtures in $dir")
        val failed = names.filter { name ->
            val session = DiagramSession(Load.loadModel(File(dir, "$name.fsm").readText(), File(dir, "$name.fsm.layout").readText()))
            SvgExport.export(session) != File(dir, "$name.svg").readText().replace("\r\n", "\n")
        }
        assertEquals(emptyList(), failed, "drawn differently from the VS Code edition")
    }
}
