package com.linuxwff.pigapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The in-app editor is schema driven; a field that is not in
 * `assets/uipack-editor-schema.json` simply cannot be edited. Keep the asset
 * honest: unique paths, usable types, and every key must be one the parser
 * actually reads (`tools/gen_uipack_schema.py` produces it from the source).
 */
class UiPackEditorSchemaTest {
    private fun schema(): JSONObject {
        val candidates = listOf(
            File("src/main/assets/uipack-editor-schema.json"),
            File("app/src/main/assets/uipack-editor-schema.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("schema asset not found from ${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    private fun parserSource(): String {
        val candidates = listOf(
            File("src/main/java/com/linuxwff/pigapp/UiPack.kt"),
            File("app/src/main/java/com/linuxwff/pigapp/UiPack.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText().orEmpty()
    }

    @Test
    fun everyFieldIsWellFormed() {
        val root = schema()
        val groups = root.getJSONArray("groups")
        assertTrue("expected the editor schema to have groups", groups.length() > 0)
        val source = parserSource()
        val validTypes = setOf(
            "color", "token", "options", "bool", "int", "float",
            "string", "font", "json",
        )
        var fields = 0
        for (g in 0 until groups.length()) {
            val group = groups.getJSONObject(g)
            val id = group.optString("id")
            assertTrue("group $g has no id", id.isNotBlank())
            assertTrue("group $id has no file", group.optString("file").isNotBlank())
            val list = group.getJSONArray("fields")
            for (i in 0 until list.length()) {
                val field = list.getJSONObject(i)
                fields++
                val key = field.optString("key")
                assertTrue("$id[$i] has no key", key.isNotBlank())
                assertTrue("$id.$key has no label", field.optString("label").isNotBlank())
                val type = field.optString("type")
                assertTrue("$id.$key has unknown type $type", type in validTypes)
                assertTrue(
                    "$id.$key is not read by UiPackParser",
                    source.isEmpty() || "\"$key\"" in source,
                )
                if (type == "options") {
                    val options = field.optJSONArray("options")
                    assertTrue("$id.$key has no options", options != null && options.length() > 0)
                }
                if (type == "token") {
                    val options = field.optJSONArray("options")
                    assertTrue("$id.$key has no colour tokens", options != null && options.length() > 0)
                }
            }
        }
        assertTrue("schema is suspiciously small ($fields fields)", fields >= 250)
    }

    @Test
    fun fieldPathsAreUniquePerFile() {
        val groups = schema().getJSONArray("groups")
        val seen = mutableSetOf<String>()
        for (g in 0 until groups.length()) {
            val group = groups.getJSONObject(g)
            val file = group.optString("file")
            val fields = group.getJSONArray("fields")
            for (i in 0 until fields.length()) {
                val path = fields.getJSONObject(i).getJSONArray("path")
                val joined = (0 until path.length()).joinToString(".") { path.optString(it) }
                val id = "$file#$joined"
                assertTrue("duplicate field $id", seen.add(id))
            }
        }
    }

    /**
     * Write every schema default through its path, then parse the result with
     * the real parser. This catches a wrong `path` far more reliably than
     * looking at JSON by eye: a misplaced key either vanishes or warns.
     */
    @Test
    fun schemaDefaultsRoundTripThroughParser() {
        val root = schema()
        val groups = root.getJSONArray("groups")
        val files = mutableMapOf<String, JSONObject>()
        files["manifest.json"] = JSONObject()
        for (g in 0 until groups.length()) {
            val group = groups.getJSONObject(g)
            val file = group.optString("file")
            val doc = files.getOrPut(file) { JSONObject() }
            val fields = group.getJSONArray("fields")
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                if (!field.has("default") || field.isNull("default")) continue
                val path = field.getJSONArray("path")
                var node = doc
                for (step in 0 until path.length() - 1) {
                    val key = path.optString(step)
                    node = node.optJSONObject(key) ?: JSONObject().also { node.put(key, it) }
                }
                node.put(path.optString(path.length() - 1), field.opt("default"))
            }
        }
        val parsed = UiPackParser.parse(files.mapValues { it.value.toString() })
        val pack = parsed.pack
        assertNotNull("defaults should parse: " + parsed.warnings, pack)
        // android.graphics.Color is a stub in plain JVM tests; colour parsing
        // warnings are expected there and are covered by the Robolectric suite.
        val structural = parsed.warnings.filterNot { "不是有效颜色" in it }
        assertTrue("unexpected warnings: $structural", structural.isEmpty())
        assertEquals(18, pack?.theme?.cornerRadius)
        assertEquals(15, pack?.types?.body)
        assertEquals("collapsed", pack?.layout?.toolCards)
        assertEquals(6, pack?.layout?.composerMaxLines)
        assertEquals(14, pack?.layout?.bubble?.paddingH)
        assertEquals(true, pack?.layout?.header?.show)
        assertEquals(true, pack?.components?.showHeaderAvatar)
    }

    @Test
    fun coversEveryLayoutSection() {
        val groups = schema().getJSONArray("groups")
        val ids = (0 until groups.length()).map { groups.getJSONObject(it).optString("id") }.toSet()
        val expected = setOf(
            "theme", "typography", "components", "chat", "composer", "bubble",
            "toolCard", "thinking", "markdown", "chrome", "drawer", "header",
            "sidebar", "skinSwitcher", "packManager", "settings", "manifest",
        )
        assertEquals(emptySet<String>(), expected - ids)
    }
}
