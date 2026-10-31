package com.linuxwff.pigapp.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.PackEditorSource
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Schema (app/src/main/assets/uipack-editor-schema.json)
// ---------------------------------------------------------------------------

private data class EditorField(
    val key: String,
    val path: List<String>,
    val type: String,
    val label: String,
    val default: Any?,
    val nullable: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(),
    val desc: String? = null,
)

private data class EditorGroup(
    val id: String,
    val title: String,
    val file: String,
    val path: List<String>,
    val desc: String,
    val fields: List<EditorField>,
)

private data class EditorRaw(
    val id: String,
    val title: String,
    val file: String,
    val path: List<String>,
    val desc: String,
)

private data class EditorSchema(
    val groups: List<EditorGroup>,
    val raw: List<EditorRaw>,
)

private object Missing

private fun loadEditorSchema(context: Context): EditorSchema {
    val text = runCatching {
        context.assets.open("uipack-editor-schema.json").use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull().orEmpty()
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return EditorSchema(emptyList(), emptyList())

    fun field(json: JSONObject): EditorField {
        val path = json.optJSONArray("path")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        val options = json.optJSONArray("options")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        val default = when {
            !json.has("default") || json.isNull("default") -> null
            else -> json.opt("default")
        }
        return EditorField(
            key = json.optString("key"),
            path = path,
            type = json.optString("type", "string"),
            label = json.optString("label").ifBlank { json.optString("key") },
            default = default,
            nullable = json.optBoolean("nullable", false),
            min = json.optDouble("min", Double.NaN).takeIf { !it.isNaN() },
            max = json.optDouble("max", Double.NaN).takeIf { !it.isNaN() },
            step = json.optDouble("step", Double.NaN).takeIf { !it.isNaN() },
            options = options,
            desc = json.optString("desc").takeIf { it.isNotBlank() },
        )
    }

    val groups = mutableListOf<EditorGroup>()
    root.optJSONArray("groups")?.let { array ->
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val path = json.optJSONArray("path")?.let { p ->
                (0 until p.length()).map { p.optString(it) }
            }.orEmpty()
            val fields = mutableListOf<EditorField>()
            json.optJSONArray("fields")?.let { f ->
                for (i in 0 until f.length()) {
                    f.optJSONObject(i)?.let { fields.add(field(it)) }
                }
            }
            groups.add(
                EditorGroup(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    file = json.optString("file"),
                    path = path,
                    desc = json.optString("desc"),
                    fields = fields,
                ),
            )
        }
    }
    val raw = mutableListOf<EditorRaw>()
    root.optJSONArray("raw")?.let { array ->
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val path = json.optJSONArray("path")?.let { p ->
                (0 until p.length()).map { p.optString(it) }
            }.orEmpty()
            raw.add(
                EditorRaw(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    file = json.optString("file"),
                    path = path,
                    desc = json.optString("desc"),
                ),
            )
        }
    }
    return EditorSchema(groups, raw)
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

private fun JSONObject.valueAt(path: List<String>): Any? {
    var node: Any? = this
    for (key in path) {
        if (node !is JSONObject || !node.has(key)) return Missing
        node = node.opt(key)
    }
    return if (node == JSONObject.NULL) null else node
}

private fun JSONObject.putAt(path: List<String>, value: Any?) {
    if (path.isEmpty()) return
    var node = this
    for (key in path.dropLast(1)) {
        val child = node.optJSONObject(key) ?: JSONObject().also { node.put(key, it) }
        node = child
    }
    node.put(path.last(), value ?: JSONObject.NULL)
}

private fun JSONObject.removeAt(path: List<String>): Boolean {
    if (path.isEmpty()) return false
    var node = this
    for (key in path.dropLast(1)) {
        node = node.optJSONObject(key) ?: return false
    }
    if (!node.has(path.last())) return false
    node.remove(path.last())
    return true
}

/** Values read back from JSON (Long/Double) compare equal to schema defaults. */
private fun sameValue(a: Any?, b: Any?): Boolean {
    if (a == null || b == null) return a == null && b == null
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    if (a is JSONArray || a is JSONObject) return a.toString() == b.toString()
    return a == b
}

private fun asText(value: Any?): String = when (value) {
    null -> ""
    is JSONArray -> value.toString(2)
    is JSONObject -> value.toString(2)
    is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    else -> value.toString()
}

private fun prettyJson(value: Any?): String = when (value) {
    null -> ""
    is JSONArray -> value.toString(2)
    is JSONObject -> value.toString(2)
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

// ---------------------------------------------------------------------------
// Editor state
// ---------------------------------------------------------------------------

private class PackEditorState(
    private val source: PackEditorSource,
    private val schema: EditorSchema,
) {
    val assets: Map<String, String> = source.assets
    /** Resolved file name per schema group (manifest may pin another path). */
    private val manifest: JSONObject? = runCatching {
        JSONObject(source.files["manifest.json"].orEmpty())
    }.getOrNull()

    fun fileOf(name: String): String = when (name) {
        "theme/colors.json" -> manifest?.optString("theme").orEmpty().ifBlank { name }
        "layout/chat.json" -> manifest?.optString("layout").orEmpty().ifBlank { name }
        "theme/typography.json" -> manifest?.optString("typography").orEmpty().ifBlank { name }
        else -> name
    }

    private val docs = mutableMapOf<String, JSONObject>()
    private val originalText = LinkedHashMap(source.files)
    private val originalHas = mutableSetOf<String>()
    private val originalValue = mutableMapOf<String, Any?>()
    private val extraFiles = mutableListOf<Pair<String, String>>() // file -> original text
    /** Files whose original text is not valid JSON; never overwritten until edited. */
    private val unparseable = mutableSetOf<String>()
    private val editedFiles = mutableSetOf<String>()

    var revision by mutableStateOf(0)
        private set

    init {
        fun loadDoc(file: String) {
            if (docs.containsKey(file)) return
            val text = source.files[file]
            if (text.isNullOrBlank()) {
                docs[file] = JSONObject()
                return
            }
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed == null) unparseable.add(file)
            docs[file] = parsed ?: JSONObject()
        }

        val known = mutableSetOf<String>()
        schema.groups.forEach { group ->
            val file = fileOf(group.file)
            known.add(file)
            loadDoc(file)
        }
        schema.raw.forEach { raw -> loadDoc(fileOf(raw.file)) }
        loadDoc("manifest.json")
        source.files.forEach { (file, text) ->
            if (file !in known && file != "manifest.json" && file.endsWith(".json")) {
                extraFiles.add(file to text)
                val parsed = runCatching { JSONObject(text) }.getOrNull()
                if (parsed == null) unparseable.add(file) else docs[file] = parsed
            }
        }
        // Snapshot the unedited values so "reset" and the dirty counter work.
        allFieldPaths().forEach { (file, path) ->
            val id = fieldId(file, path)
            val value = docs[file]?.valueAt(path) ?: Missing
            if (value !== Missing) {
                originalHas.add(id)
                originalValue[id] = value
            }
        }
    }

    private fun fieldId(file: String, path: List<String>): String =
        file + "#" + path.joinToString(".")

    private fun allFieldPaths(): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, List<String>>>()
        schema.groups.forEach { group ->
            val file = fileOf(group.file)
            group.fields.forEach { out.add(file to it.path) }
        }
        return out
    }

    fun fieldFile(group: EditorGroup): String = fileOf(group.file)

    fun value(file: String, field: EditorField): Any? {
        // Reading [revision] subscribes the row to edits; the values themselves
        // are plain objects, not snapshot state.
        if (revision < 0) return field.default
        val value = docs[file]?.valueAt(field.path) ?: Missing
        return if (value === Missing) field.default else value
    }

    fun isSet(file: String, field: EditorField): Boolean {
        if (revision < 0) return false
        return docs[file]?.valueAt(field.path) !== Missing
    }

    fun isDirty(file: String, field: EditorField): Boolean {
        if (revision < 0) return false
        val id = fieldId(file, field.path)
        val present = isSet(file, field)
        val wasPresent = id in originalHas
        if (present != wasPresent) return true
        if (!present) return false
        return !sameValue(value(file, field), originalValue[id])
    }

    fun set(file: String, field: EditorField, value: Any?) {
        val doc = docs.getOrPut(file) { JSONObject() }
        doc.putAt(field.path, value)
        editedFiles.add(file)
        revision++
    }

    fun reset(file: String, field: EditorField) {
        val id = fieldId(file, field.path)
        val doc = docs.getOrPut(file) { JSONObject() }
        // Restore the source value instead of deleting an explicitly-set key:
        // deleting is the same visual result but would still count as an edit.
        if (id in originalHas) doc.putAt(field.path, originalValue[id]) else doc.removeAt(field.path)
        editedFiles.add(file)
        revision++
    }

    fun rawValue(entry: EditorRaw): Any? {
        if (revision < 0) return null
        return docs[fileOf(entry.file)]?.valueAt(entry.path)
    }

    fun setRaw(entry: EditorRaw, value: Any?) {
        val file = fileOf(entry.file)
        if (entry.path.isEmpty()) {
            // Root JSON files (strings.json, nav.json, ...) replace the document.
            docs[file] = (value as? JSONObject) ?: JSONObject()
        } else {
            val doc = docs.getOrPut(file) { JSONObject() }
            if (value == null) doc.removeAt(entry.path) else doc.putAt(entry.path, value)
        }
        editedFiles.add(file)
        unparseable.remove(file)
        revision++
    }

    fun extraText(file: String): String =
        (if (revision < 0) null else runCatching { docs[file]?.toString(2) }.getOrNull())
            ?: originalText[file]
            ?: ""

    fun setExtraText(file: String, text: String): Boolean {
        val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return false
        docs[file] = parsed
        unparseable.remove(file)
        editedFiles.add(file)
        revision++
        return true
    }

    fun extraJsonFiles(): List<String> = extraFiles.map { it.first }

    fun assetFiles(): List<String> = assets.keys.sorted()

    fun modifiedCount(): Int {
        var count = 0
        schema.groups.forEach { group ->
            val file = fileOf(group.file)
            group.fields.forEach { if (isDirty(file, it)) count++ }
        }
        return count
    }

    fun matches(group: EditorGroup, field: EditorField, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return field.label.lowercase().contains(needle) ||
            field.key.lowercase().contains(needle) ||
            field.path.joinToString(".").lowercase().contains(needle) ||
            (field.desc?.lowercase()?.contains(needle) == true) ||
            group.title.lowercase().contains(needle)
    }

    /** Draft files: untouched files pass through, edited objects are re-serialized. */
    fun files(): Map<String, String> {
        val out = LinkedHashMap(originalText)
        // A pack without a manifest is silently ignored by the parser.
        val manifestDoc = docs["manifest.json"]
        if (manifestDoc != null && !manifestDoc.has("name")) manifestDoc.put("name", source.name)
        docs.forEach { (file, doc) ->
            if (file in unparseable && file !in editedFiles) return@forEach
            if (doc.length() > 0 || originalText.containsKey(file) || file == "manifest.json") {
                out[file] = doc.toString(2)
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Editor screen
// ---------------------------------------------------------------------------

@Composable
fun PackEditorDialog(
    vm: AppViewModel,
    initialPackId: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val schema = remember(context) { loadEditorSchema(context) }
    var source by remember { mutableStateOf(vm.packEditorSource(initialPackId)) }
    val state = remember(source) { PackEditorState(source, schema) }
    val scheme = MaterialTheme.colorScheme

    var query by remember { mutableStateOf("") }
    var onlyDirty by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(true) }
    var previewApplied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }
    var rawTarget by remember { mutableStateOf<RawTarget?>(null) }
    var expanded by remember { mutableStateOf(setOf("theme", "chat", "composer")) }

    val revision = state.revision
    val modified = remember(revision) { state.modifiedCount() }

    val exportZip = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.exportPackZip(state.files(), state.assets, uri)
    }

    fun close() {
        if (previewApplied && !saved) vm.restoreActivePack()
        onDismiss()
    }

    // Stay in the editor after a copy: the just-written local pack becomes the
    // new source, so the "protected" badge and copy-on-save behaviour go away.
    fun adopt(id: String) {
        source = vm.packEditorSource(id)
        previewApplied = true
        saved = false
    }

    LaunchedEffect(revision, preview) {
        if (preview && revision > 0) {
            kotlinx.coroutines.delay(220)
            vm.previewPackFiles(state.files())
            previewApplied = true
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("UI 编辑器", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(source.name)
                                if (source.protected) append(" · 受保护")
                                if (modified > 0) append(" · $modified 项已改") else append(" · 未修改")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source.protected) {
                                scheme.tertiary
                            } else if (modified > 0) {
                                scheme.primary
                            } else {
                                scheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { showCopy = true }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制为新 pack",
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "实时预览",
                            tint = if (preview) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { exportZip.launch("ui-pack.zip") }) {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = "导出 ZIP",
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showSave = true }) {
                        Text("保存", color = scheme.primary)
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        placeholder = { Text("搜索键名 / 标签 / 说明", style = MaterialTheme.typography.bodySmall) },
                    )
                    FilterChip(
                        selected = onlyDirty,
                        onClick = { onlyDirty = !onlyDirty },
                        label = { Text("已改", style = MaterialTheme.typography.labelSmall) },
                    )
                    IconButton(onClick = { showHelp = !showHelp }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "显示说明",
                            tint = if (showHelp) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { expanded = schema.groups.map { it.id }.toSet() }) {
                        Text("展开全部", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { expanded = emptySet() }) {
                        Text("收起全部", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (preview) "实时预览已开" else "预览已关",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.25f))

                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    schema.groups.forEach { group ->
                        val file = state.fieldFile(group)
                        val fields = group.fields.filter { field ->
                            (!onlyDirty || state.isDirty(file, field)) &&
                                state.matches(group, field, query)
                        }
                        if (fields.isEmpty()) return@forEach
                        item(key = "group:${group.id}") {
                            GroupHeader(
                                title = group.title,
                                path = group.path.joinToString(".") + "  ·  " + group.file,
                                desc = group.desc,
                                dirty = fields.count { state.isDirty(file, it) },
                                open = query.isNotBlank() || group.id in expanded,
                                onToggle = {
                                    expanded = if (group.id in expanded) {
                                        expanded - group.id
                                    } else {
                                        expanded + group.id
                                    }
                                },
                            )
                        }
                        if (query.isNotBlank() || group.id in expanded) {
                            items(fields, key = { "f:${group.id}:${it.key}" }) { field ->
                                FieldRow(
                                    field = field,
                                    value = state.value(file, field),
                                    isSet = state.isSet(file, field),
                                    dirty = state.isDirty(file, field),
                                    showHelp = showHelp,
                                    fontOptions = state.assetFiles()
                                        .filter { it.endsWith(".ttf") || it.endsWith(".otf") },
                                    onSet = { state.set(file, field, it) },
                                    onReset = { state.reset(file, field) },
                                )
                            }
                        }
                    }

                    item(key = "raw-header") {
                        SectionHeader("结构 / 原始 JSON", "节点树、列表与映射在这里编辑")
                    }
                    items(schema.raw, key = { "raw:${it.id}" }) { entry ->
                        val value = state.rawValue(entry)
                        RawRow(
                            title = entry.title,
                            subtitle = entry.file + if (entry.path.isNotEmpty()) {
                                " · " + entry.path.joinToString(".")
                            } else {
                                ""
                            },
                            desc = entry.desc,
                            preview = if (value == null) "未设置" else asText(value),
                            onEdit = {
                                rawTarget = RawTarget(entry.title, prettyJson(value)) { text ->
                                    val parsed = runCatching { JSONObject("{\"v\":$text}") }.getOrNull()
                                    if (text.isBlank()) {
                                        state.setRaw(entry, null)
                                        true
                                    } else if (parsed != null) {
                                        state.setRaw(entry, parsed.opt("v"))
                                        true
                                    } else {
                                        false
                                    }
                                }
                            },
                        )
                    }

                    item(key = "files-header") {
                        SectionHeader("其他文件", "windows/*.json 等；资源文件只读")
                    }
                    items(state.extraJsonFiles(), key = { "file:$it" }) { file ->
                        val text = state.extraText(file)
                        RawRow(
                            title = file,
                            subtitle = "${text.length} 字符",
                            desc = null,
                            preview = text.take(160).replace("\n", " "),
                            onEdit = {
                                rawTarget = RawTarget(file, state.extraText(file)) { updated ->
                                    state.setExtraText(file, updated)
                                }
                            },
                        )
                    }
                    items(state.assetFiles(), key = { "asset:$it" }) { asset ->
                        RawRow(
                            title = asset,
                            subtitle = "assets",
                            desc = null,
                            preview = "只读资源（字体会在保存时原样复制）",
                            onEdit = null,
                        )
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(28.dp)) }
                }
            }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf(source.name) }
        val protectedPack = source.protected
        PackAlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(if (protectedPack) "另存为新 pack" else "保存") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("方案名称") },
                    )
                    Text(
                        if (protectedPack) {
                            "默认主题受保护，不会改动原 pack；保存会复制出一份可编辑的本地 pack。"
                        } else {
                            "保存会直接更新「${source.name}」，名称也可以在这里改。" +
                                "想给 Termux 用可以「导出」成 ZIP。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSave = false
                        vm.saveEditorPack(
                            name = name,
                            files = state.files(),
                            assets = state.assets,
                            overwriteId = if (protectedPack) null else source.id,
                        ) { id ->
                            if (id != null) {
                                saved = true
                                onDismiss()
                            }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("取消") }
            },
        )
    }

    if (showCopy) {
        var name by remember {
            mutableStateOf(
                derivedCopyName(source.name, vm.ui.localPacks.map { it.name } + source.name),
            )
        }
        PackAlertDialog(
            onDismissRequest = { showCopy = false },
            title = { Text("复制为新 pack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("方案名称") },
                    )
                    Text(
                        "把当前草稿复制成一个新的本地 pack，并继续在编辑器里打开它；" +
                            "原 pack 不会被修改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCopy = false
                        vm.saveEditorPack(name, state.files(), state.assets, overwriteId = null) { id ->
                            if (id != null) adopt(id)
                        }
                    },
                ) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { showCopy = false }) { Text("取消") }
            },
        )
    }

    rawTarget?.let { target ->
        RawJsonDialog(
            title = target.title,
            initial = target.initial,
            onDismiss = { rawTarget = null },
            onApply = { text ->
                if (target.apply(text)) {
                    rawTarget = null
                    true
                } else {
                    false
                }
            },
        )
    }
}

private data class RawTarget(
    val title: String,
    val initial: String,
    val apply: (String) -> Boolean,
)

@Composable
private fun GroupHeader(
    title: String,
    path: String,
    desc: String,
    dirty: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                path + if (desc.isNotBlank()) " · $desc" else "",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (dirty > 0) {
            Text(
                "$dirty 项",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Field controls
// ---------------------------------------------------------------------------

@Composable
private fun FieldRow(
    field: EditorField,
    value: Any?,
    isSet: Boolean,
    dirty: Boolean,
    showHelp: Boolean,
    fontOptions: List<String>,
    onSet: (Any?) -> Unit,
    onReset: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Text(
                field.key,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
                IconButton(onClick = onReset, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重置",
                        modifier = Modifier.size(14.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        when (field.type) {
            "color" -> ColorField(field, value, isSet, onSet)
            "token", "options" -> OptionField(field, value, onSet)
            "bool" -> Switch(
                checked = value as? Boolean ?: false,
                onCheckedChange = { onSet(it) },
                modifier = Modifier.height(28.dp),
            )
            "int", "float" -> NumberField(field, value, isSet, onSet)
            "font" -> FontField(field, value, fontOptions, onSet)
            "json" -> JsonField(field, value, onSet)
            else -> OutlinedTextField(
                value = asText(value),
                onValueChange = { onSet(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        if (showHelp && field.desc != null) {
            Text(
                field.desc,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ColorField(
    field: EditorField,
    value: Any?,
    isSet: Boolean,
    onSet: (Any?) -> Unit,
) {
    var picking by remember(field.path) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val text = asText(value)
    val color = parseHexColor(text)
    Row(
        Modifier.fillMaxWidth().clickable { picking = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color ?: scheme.surfaceVariant)
                .border(1.dp, scheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (field.nullable && !isSet) "自动" else text.ifBlank { "未设置" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (field.nullable && !isSet) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
    if (picking) {
        ColorPickerDialog(
            initial = if (isSet) text else null,
            nullable = field.nullable,
            onDismiss = { picking = false },
            onPick = {
                picking = false
                onSet(it)
            },
        )
    }
}

@Composable
private fun OptionField(field: EditorField, value: Any?, onSet: (Any?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val text = asText(value)
    val isToken = field.type == "token"
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isToken) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(packColor(text))
                        .border(1.dp, scheme.outline.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text.ifBlank { "自动" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isToken) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(packColor(option))
                                        .border(1.dp, scheme.outline.copy(alpha = 0.5f), CircleShape),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(option, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        open = false
                        onSet(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    field: EditorField,
    value: Any?,
    isSet: Boolean,
    onSet: (Any?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isInt = field.type == "int"
    val min = field.min ?: 0.0
    val max = field.max ?: 100.0
    val fallback = (field.default as? Number)?.toDouble() ?: min
    if (field.nullable && !isSet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "自动（继承）",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSet(fallback) }) { Text("设置") }
        }
        return
    }
    val numeric = (value as? Number)?.toDouble() ?: fallback
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Slider(
            value = numeric.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { raw ->
                onSet(if (isInt) raw.toDouble().let { kotlin.math.round(it) } else raw.toDouble())
            },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { editing = true }) {
            Text(
                if (isInt) numeric.toLong().toString() else String.format("%.2f", numeric),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
    if (editing) {
        NumberDialog(
            title = field.label,
            initial = numeric,
            min = min,
            max = max,
            isInt = isInt,
            onDismiss = { editing = false },
            onApply = {
                editing = false
                onSet(it)
            },
        )
    }
}

@Composable
private fun FontField(
    field: EditorField,
    value: Any?,
    fontOptions: List<String>,
    onSet: (Any?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = asText(value),
            onValueChange = { onSet(it) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("sans / serif / mono / assets/fonts/x.ttf") },
        )
        Box {
            TextButton(onClick = { open = true }) { Text("预设") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (listOf("sans", "serif", "mono", "cursive") + fontOptions).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            open = false
                            onSet(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonField(field: EditorField, value: Any?, onSet: (Any?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { editing = true }) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("编辑 JSON")
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (value == null) "未设置" else asText(value).replace("\n", " "),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    if (editing) {
        RawJsonDialog(
            title = field.label + " · " + field.path.joinToString("."),
            initial = prettyJson(value),
            onDismiss = { editing = false },
            onApply = { text ->
                val parsed = runCatching { JSONObject("{\"v\":$text}") }.getOrNull()
                if (text.isBlank()) {
                    onSet(null)
                    editing = false
                    true
                } else if (parsed != null) {
                    onSet(parsed.opt("v"))
                    editing = false
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun RawRow(
    title: String,
    subtitle: String,
    desc: String?,
    preview: String,
    onEdit: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                preview,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onEdit != null) {
            Icon(
                Icons.Default.Code,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun RawJsonDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Boolean,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 320.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("{ }") },
                )
                if (error != null) {
                    Text(error!!, style = MaterialTheme.typography.labelSmall, color = scheme.error)
                }
                Text(
                    "留空表示移除该键；内容必须是合法 JSON。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onApply(text.trim())) {
                        error = "JSON 解析失败"
                    }
                },
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun NumberDialog(
    title: String,
    initial: Double,
    min: Double,
    max: Double,
    isInt: Boolean,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
) {
    var text by remember {
        mutableStateOf(if (isInt) initial.toLong().toString() else initial.toString())
    }
    val parsed = text.trim().toDoubleOrNull()
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isInt) KeyboardType.Number else KeyboardType.Decimal,
                    ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    "范围 " + if (isInt) "${min.toLong()}–${max.toLong()}" else "$min–$max",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = parsed ?: return@TextButton
                    onApply(if (isInt) kotlin.math.round(value) else value)
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ColorPickerDialog(
    initial: String?,
    nullable: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val start = parseHexColor(initial ?: "") ?: Color(0xFFA78BFA)
    val startHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(start.toArgb(), it) }
    }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var saturation by remember { mutableStateOf(startHsv[1]) }
    var brightness by remember { mutableStateOf(startHsv[2]) }
    var alpha by remember { mutableStateOf(start.alpha) }
    var hex by remember { mutableStateOf(hexString(start)) }
    var valid by remember { mutableStateOf(true) }
    val scheme = MaterialTheme.colorScheme
    val current = Color.hsv(hue, saturation, brightness, alpha)

    fun syncHex(h: Float = hue, s: Float = saturation, v: Float = brightness, a: Float = alpha) {
        hue = h
        saturation = s
        brightness = v
        alpha = a
        hex = hexString(Color.hsv(h, s, v).copy(alpha = a))
        valid = true
    }

    fun readHex(text: String) {
        hex = text
        val parsed = parseHexColor(text)
        valid = parsed != null
        if (parsed != null) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(parsed.toArgb(), hsv)
            hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
            alpha = parsed.alpha
        }
    }

    fun fromPointer(x: Float, y: Float, width: Float, height: Float) {
        val s = (x / width).coerceIn(0f, 1f)
        val v = 1f - (y / height).coerceIn(0f, 1f)
        syncHex(s = s, v = v)
    }

    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(current)
                        .border(1.dp, scheme.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("颜色", style = MaterialTheme.typography.titleSmall)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    fromPointer(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    fromPointer(
                                        change.position.x,
                                        change.position.y,
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                    )
                                },
                            )
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    drawCircle(
                        color = Color.White,
                        radius = 6.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(
                            saturation * size.width,
                            (1f - brightness) * size.height,
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    syncHex(h = (offset.x / size.width).coerceIn(0f, 1f) * 359.9f)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    syncHex(h = (change.position.x / size.width).coerceIn(0f, 1f) * 359.9f)
                                },
                            )
                        },
                ) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Red, Color.Yellow, Color.Green,
                                Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                            ),
                        ),
                    )
                    val x = (hue / 359.9f) * size.width
                    drawCircle(
                        color = Color.White,
                        radius = 7.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x, size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    syncHex(a = (offset.x / size.width).coerceIn(0f, 1f))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    syncHex(a = (change.position.x / size.width).coerceIn(0f, 1f))
                                },
                            )
                        },
                ) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(current.copy(alpha = 0f), current.copy(alpha = 1f)),
                        ),
                    )
                    val x = alpha * size.width
                    drawCircle(
                        color = Color.White,
                        radius = 7.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(x, size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { readHex(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !valid,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("#RRGGBB 或 #AARRGGBB") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onPick(hex) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (nullable) {
                    TextButton(onClick = { onPick(null) }) { Text("自动") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun parseHexColor(text: String): Color? {
    val raw = text.trim()
    if (raw.isEmpty()) return null
    return runCatching {
        Color(android.graphics.Color.parseColor(if (raw.startsWith("#")) raw else "#$raw"))
    }.getOrNull()
}

private fun hexString(color: Color): String {
    val argb = color.toArgb()
    val alpha = (argb ushr 24) and 0xFF
    return if (alpha == 0xFF) {
        String.format(java.util.Locale.US, "#%06X", argb and 0xFFFFFF)
    } else {
        String.format(java.util.Locale.US, "#%08X", argb)
    }
}

/**
 * Name used when a pack is duplicated: "<name> 副本", then "<name> 副本 2", ...
 * Re-copying an existing copy walks back to its stem so names do not stack.
 */
internal fun derivedCopyName(base: String, existing: Collection<String>): String {
    val clean = base.trim().ifBlank { "UI pack" }
    val stem = clean.replace(Regex("\\s*副本(\\s+\\d+)?$"), "").ifBlank { clean }
    val names = existing.map { it.trim() }.toSet()
    var candidate = "$stem 副本"
    var index = 2
    while (candidate in names) {
        candidate = "$stem 副本 $index"
        index++
    }
    return candidate
}
