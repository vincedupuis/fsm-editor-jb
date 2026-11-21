package com.vincegosoftware.fsmeditor

import junit.framework.TestCase
import java.io.File

/**
 * The IDE scenarios shared by the three editions (`plugin/src/test/scenarios.md`, a copy of the VS Code edition's
 * `test/scenarios.md`): every `###` id is implemented by a test of this folder (its [Scenario] annotation) or listed
 * under "Not applicable" for the edition `JetBrains`, and no test names an id the file doesn't have.
 */
class ScenariosTest : TestCase() {
    private val repo = File(System.getProperty("fsm.examples") ?: "../examples").absoluteFile.parentFile

    fun testEveryScenarioIsImplementedOrNotApplicable() {
        val text = File(repo, "plugin/src/test/scenarios.md").readText().replace("\r\n", "\n")
        val ids = Regex("^### (\\S+)\\s*$", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("no ### scenario ids in scenarios.md", ids.isNotEmpty())
        assertEquals("ids named twice", ids.size, ids.toSet().size)
        // The rows of its table: | id | Edition | Why |.
        val rows = Regex("^\\|([^|]*)\\|([^|]*)\\|", RegexOption.MULTILINE).findAll(text.substringAfter("\n## Not applicable", ""))
            .map { it.groupValues[1].trim().trim('`') to it.groupValues[2].trim() }
        val notApplicable = rows.filter { it.second == "JetBrains" }.map { it.first }.toSet()

        // The tests of this module (the compiled classes of this package and below): the methods annotated with @Scenario.
        val here = File(ScenariosTest::class.java.getResource("ScenariosTest.class")!!.toURI()).parentFile
        val tests = HashMap<String, MutableList<String>>()
        val problems = ArrayList<String>()
        here.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            val name = ScenariosTest::class.java.packageName + "." + f.relativeTo(here).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val type = Class.forName(name, false, ScenariosTest::class.java.classLoader)
            for (m in type.declaredMethods) {
                val scenario = m.getAnnotation(Scenario::class.java) ?: continue
                val test = "${type.simpleName}.${m.name}"
                if (!m.name.startsWith("test") || !java.lang.reflect.Modifier.isPublic(m.modifiers)) problems += "$test names scenarios but isn't a test that runs"
                if (scenario.ids.isEmpty()) problems += "$test names no scenario"
                for (id in scenario.ids) tests.getOrPut(id) { ArrayList() } += test
            }
        }
        for (id in ids) {
            if (id !in tests && id !in notApplicable) problems += "$id: neither implemented (@Scenario(\"$id\")) nor not applicable to JetBrains"
            if (id in tests && id in notApplicable) problems += "$id: implemented (${tests[id]}) and listed as not applicable"
        }
        for ((id, by) in tests) if (id !in ids) problems += "$by: the unknown scenario $id"
        for (id in notApplicable) if (id !in ids) problems += "Not applicable: the unknown scenario $id"
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }
}
