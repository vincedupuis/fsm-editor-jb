package com.vincegosoftware.fsmeditor.core

import java.io.File
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Marketplace listing, checked before a release builds the plugin (`:plugin:verifyPlugin` checks the rest of the
 * descriptor): the plugin's name and id, the same in `plugin.xml` and `plugin/build.gradle.kts`, its icon, the links of
 * its description, which the Marketplace shows (absolute, over https), and the README's links, relative ones naming
 * files of this repo and their headings.
 */
class ListingTest {
    private val root = File(System.getProperty("fsm.root") ?: "..")
    private val pluginXml = File(root, "plugin/src/main/resources/META-INF/plugin.xml").readText()
    private val buildScript = File(root, "plugin/build.gradle.kts").readText()

    private fun xmlText(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim()

    private fun tag(name: String) = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(pluginXml)?.groupValues?.get(1)

    private fun gradle(name: String) = Regex("""^\s*$name = "([^"]*)"""", RegexOption.MULTILINE).find(buildScript)?.groupValues?.get(1)

    @Test
    fun namesThePluginAsItsBuildScriptDoes() {
        val name = xmlText(tag("name")!!)
        assertTrue(name.isNotBlank())
        assertEquals(gradle("name"), name, "the name in plugin.xml and in plugin/build.gradle.kts")
        assertEquals(gradle("id"), tag("id")?.trim(), "the id in plugin.xml and in plugin/build.gradle.kts")
        assertTrue(!tag("vendor").isNullOrBlank(), "a vendor")
    }

    @Test
    fun hasAnIcon() {
        assertTrue(File(root, "plugin/src/main/resources/META-INF/pluginIcon.svg").isFile, "META-INF/pluginIcon.svg")
    }

    @Test
    fun describesItselfWithAbsoluteLinks() {
        val description = tag("description")!!
        assertTrue(description.length >= 40, "a description")
        val bad = Regex("""(?:href|src)="([^"]*)"""").findAll(description).map { it.groupValues[1] }.filterNot { it.startsWith("https://") }.toList()
        assertEquals(emptyList(), bad, "links of the description that the Marketplace can't follow")
    }

    @Test
    fun theReadmeLinksResolve() {
        assertEquals(emptyList(), brokenLinks(File(root, "README.md")))
        for (doc in listOf("docs/CODEGEN.md", "docs/UML-CONFORMANCE.md")) assertEquals(emptyList(), brokenLinks(File(root, doc)), doc)
    }

    /** GitHub's anchor of a heading: lowercase, punctuation dropped, spaces as hyphens. */
    private fun slug(heading: String) = heading.trim().lowercase().replace(Regex("""[^\p{L}\p{N}\s_-]"""), "").replace(Regex("""\s"""), "-")

    /** The problems of the relative links of a Markdown file (its own anchors too): files that don't exist, headings that don't either. */
    private fun brokenLinks(file: File): List<String> {
        val text = file.readText().replace(Regex("```[\\s\\S]*?```"), "")
        val out = mutableListOf<String>()
        for (m in Regex("""(!?)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)|<img [^>]*src="([^"]+)"""").findAll(text)) {
            val image = m.groupValues[1] == "!" || m.groupValues[3].isNotEmpty()
            val target = m.groupValues[3].ifEmpty { m.groupValues[2] }
            if (target.startsWith("http://") || target.startsWith("https://")) {
                if (image && !target.startsWith("https://")) out += "$target: an image over http"
                continue
            }
            if (target.startsWith("mailto:")) continue
            val path = target.substringBefore('#')
            val anchor = target.substringAfter('#', "")
            val resolved = if (path.isEmpty()) file else File(file.parentFile, URLDecoder.decode(path, "UTF-8"))
            if (!resolved.exists()) {
                out += "$target: no such file"
                continue
            }
            if (anchor.isNotEmpty() && resolved.name.endsWith(".md")) {
                val headings = Regex("""^#+\s+(.*)$""", RegexOption.MULTILINE).findAll(resolved.readText()).map { slug(it.groupValues[1]) }.toSet()
                if (anchor !in headings) out += "$target: no heading #$anchor"
            }
        }
        return out
    }
}
