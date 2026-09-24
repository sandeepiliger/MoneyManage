package ai.labs32.khaata.core.locale

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation says the same things as the English file.
 *
 * A key missing from a translation silently falls back to English mid-screen; a placeholder
 * dropped or renumbered shows the wrong figure or crashes when the string is formatted; an
 * unescaped apostrophe fails the resource build. All three are easy to make by hand and invisible
 * in review, so each is checked here for every values-xx folder.
 */
class TranslationParityTest {

    private val res = File("src/main/res")
    private val english = read(File(res, "values/strings.xml"))

    private val translations: Map<String, File> =
        res.listFiles { dir -> dir.name.startsWith("values-") }.orEmpty()
            .map { File(it, "strings.xml") }
            .filter { it.exists() }
            .associateBy { it.parentFile.name.removePrefix("values-") }

    @Test
    fun `the app is translated into every language it offers`() {
        val offered = AppLanguage.entries.map { it.tag }.filter { it.isNotEmpty() && it != "en" }
        assertWithMessage("values-xx folders").that(translations.keys).containsAtLeastElementsIn(offered)
    }

    @Test
    fun `every translation has exactly the English keys`() {
        translations.forEach { (language, file) ->
            val translated = read(file)
            assertWithMessage("$language: missing").that(english.keys - translated.keys).isEmpty()
            assertWithMessage("$language: not in English").that(translated.keys - english.keys).isEmpty()
        }
    }

    @Test
    fun `every translation keeps the English placeholders`() {
        translations.forEach { (language, file) ->
            val translated = read(file)
            english.forEach { (key, source) ->
                val target = translated[key] ?: return@forEach
                assertWithMessage("$language $key: formatted attribute")
                    .that(target.formatted).isEqualTo(source.formatted)
                assertWithMessage("$language $key: plural quantities")
                    .that(target.texts.keys).isEqualTo(source.texts.keys)
                val sourcePlaceholders = source.texts.values.flatMap { placeholders(it, source.formatted) }.toSet()
                target.texts.forEach { (quantity, text) ->
                    val found = placeholders(text, target.formatted)
                    if (key.startsWith("string:")) {
                        assertWithMessage("$language $key: placeholders in \"$text\"")
                            .that(found.sorted()).isEqualTo(placeholders(source.texts.getValue(quantity), source.formatted).sorted())
                    } else {
                        // A plural's "one" may spell the number out, but never invent a new one.
                        assertWithMessage("$language $key[$quantity]: placeholders in \"$text\"")
                            .that(sourcePlaceholders).containsAtLeastElementsIn(found)
                    }
                }
            }
        }
    }

    @Test
    fun `no translation has an unescaped apostrophe`() {
        (translations + ("en" to File(res, "values/strings.xml"))).forEach { (language, file) ->
            read(file).forEach { (key, entry) ->
                entry.texts.values.forEach { text ->
                    assertWithMessage("$language $key: \"$text\"")
                        .that(Regex("""(?<!\\)'""").containsMatchIn(text)).isFalse()
                }
            }
        }
    }

    // ---- Reading ------------------------------------------------------------------------------

    private class Entry(val formatted: String?, val texts: Map<String, String>)

    /** Keys as "string:name" or "plurals:name"; each entry's raw text, by plural quantity. */
    private fun read(file: File): Map<String, Entry> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.documentElement
        val out = LinkedHashMap<String, Entry>()
        val nodes = root.childNodes
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name")
            val formatted = element.getAttribute("formatted").ifEmpty { null }
            when (element.tagName) {
                "string" -> out["string:$name"] = Entry(formatted, mapOf("" to element.textContent))
                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    val texts = (0 until items.length).associate { i ->
                        val item = items.item(i) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                    out["plurals:$name"] = Entry(formatted, texts)
                }
            }
        }
        return out
    }

    private fun placeholders(text: String, formatted: String?): List<String> =
        if (formatted == "false") emptyList() else PLACEHOLDER.findAll(text).map { it.value }.toList()

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx%]""")
    }
}
