package com.vincegosoftware.fsmeditor.core

import com.vincegosoftware.fsmeditor.core.TestFiles.CASES
import com.vincegosoftware.fsmeditor.core.TestFiles.EXAMPLES
import com.vincegosoftware.fsmeditor.core.TestFiles.SCHEMAS
import com.vincegosoftware.fsmeditor.core.TestFiles.fixture
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reader and validator against fsm-cli's test cases (plugin/cli/cases, copied by scripts/fetch-cli.sh from
 * ../fsm-cli/tests/cases): the same problems, at the same lines, with the same ids. fsm-cli's own messages about files
 * it can't read (a missing submachine file, one that isn't valid) are its loader's, which the editor doesn't have: it
 * reports such a submachine as not found, as fsm-cli does too. CasesTests.cs of the Visual Studio edition.
 */
class CasesTest {
    @ParameterizedTest
    @MethodSource("folders")
    fun theSameProblemsAsFsm(folder: String) {
        assertTrue(CASES.isDirectory, "plugin/cli/cases is missing: run scripts/fetch-cli.sh (it copies fsm-cli's tests/cases)")
        val dir = File(CASES, folder)
        @Suppress("UNCHECKED_CAST")
        val expected = PlainJson.parse(File(dir, "expected.json").readText()) as Map<String, List<Map<String, Any?>>>
        val read = expected.keys.associateWith { Format.readModel(File(dir, it).readText()) }
        var compared = 0
        for ((file, list) in expected) {
            val r = read.getValue(file)
            val found = if (r.failed) {
                r.problems!!.map { "${it.line}  error: ${it.message}" }
            } else {
                // Submachines as the editor resolves them: the machine of a readable file in the folder, else not found.
                val info = HashMap<String, SubmachineInfo>()
                for (v in r.model!!.vertices.filter { it.type == VertexType.State && it.submachine.isNotEmpty() }) {
                    val sub = read[v.submachine]
                    info[v.submachine] = if (sub != null && !sub.failed) SubmachineInfo(true, sub.model!!.name, points = sub.model.machineConnectionPoints()) else SubmachineInfo(false)
                }
                Validation.validate(r.model, info).map { "${r.lines[it.id] ?: 1} ${it.id} ${it.severity.key}: ${it.message}" }
            }
            val wanted = list.map { "${(it["line"] as Double).toInt()} ${it["id"]} ${it["severity"]}: ${it["message"]}" }
                .filter { m -> LOADER.none { m.endsWith(it) } }
            assertEquals(wanted, found, "$folder/$file")
            compared += wanted.size
        }
        assertTrue(compared > 0 || folder == "examples", "$folder: nothing compared")
    }

    /**
     * The layout file is the editions' own (the VS Code edition's schemas/fsm-layout.schema.json, copied in the plugin's
     * resources): the reader takes the files of fixtures/layout/valid, the reference's, and the examples' layout files,
     * and refuses those of fixtures/layout/invalid, as the schema does.
     */
    @Test
    fun layoutFilesTheSchemaAcceptsAreTakenTheOthersRefused() {
        for ((dir, valid) in listOf(fixture("layout/valid") to true, fixture("layout/invalid") to false, File(EXAMPLES) to true)) {
            val files = dir.listFiles { f -> f.name.endsWith(".fsm.layout") }!!
            assertTrue(files.isNotEmpty(), dir.path)
            for (f in files) {
                val problem = try {
                    Format.layoutShapeProblem(Json.parse(f.readText()))
                } catch (e: JsonSyntaxException) {
                    e.message
                }
                assertEquals(valid, problem == null, "${f.path}: $problem")
            }
        }
    }

    @Test
    fun theSchemaIsThere() = assertTrue(File(SCHEMAS, "fsm.schema.json").isFile, "plugin/cli/schemas is missing: run scripts/fetch-cli.sh")

    companion object {
        private val LOADER = listOf(" does not exist or cannot be read.", " is not a valid state machine file.")

        @JvmStatic
        fun folders(): List<String> = CASES.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: listOf("(missing)")
    }
}
