package com.linuxwff.pigapp

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("pig", Context.MODE_PRIVATE)

    val port: Int = prefs.getInt("port", 8791)
    val token: String = prefs.getString("token", null) ?: newToken().also {
        prefs.edit().putString("token", it).apply()
    }
    val cwd: String = prefs.getString("cwd", null) ?: "/data/data/com.termux/files/home"

    private val client = RelayClient("127.0.0.1", port, token)

    var ui by mutableStateOf(AppUiState())
        private set

    private val windowPositions = mutableStateMapOf<String, WindowPos>()
    private val dismissedWindows = mutableMapOf<String, String>() // pack path -> window id
    private val knownWindows = mutableMapOf<String, AppWindow>() // window id -> last parsed definition
    private var basePack: UiPack? = null
    private var relayFiles: Map<String, String> = emptyMap()
    private var relayAssets: Map<String, String> = emptyMap()
    private var relayHashes: Map<String, String> = emptyMap()
    private var relayHash: String = ""
    private var packAssets: Map<String, String> = emptyMap()
    private var packAssetHashes: Map<String, String> = emptyMap()
    private val imageCache = mutableStateMapOf<String, ImageBitmap>()
    private val imageLoading = mutableSetOf<String>()
    private val formValues = mutableStateMapOf<String, String>() // "windowId/fieldId" -> value

    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var showModelPicker by mutableStateOf(false)
        private set

    private var nextId = 1
    private var lastStateRequestId = ""
    private var lastMessagesRequestId = ""
    private var lastMessagesRequestAt = 0L
    // True until this connection has loaded the transcript it should show.
    private var needsHistory = false
    private var historyHealTried = false
    private var lastCommandsAt = 0L
    private var modelSwitchAccepted = false
    private val pendingRpcSources = mutableMapOf<String, String>()
    private var currentAssistant: String? = null
    private val toolMsgIds = mutableMapOf<String, String>()

    private var foreground = false
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var lastPongAt = 0L
    private var desiredSessionPath: String? = null
    private var restorePending = false
    private var restoreChecked = false
    private val liveBuffer = StringBuilder()
    private var liveActive = false
    private var liveFlushScheduled = false
    private val pendingToolPartials = mutableMapOf<String, String>()

    private fun builtinCommands(pack: UiPack = ui.uiPack): List<UiCommand> = listOf(
        UiCommand("compact", pack.strings.get("commands.compact", "压缩上下文")),
        UiCommand("new", pack.strings.get("commands.new", "新建会话")),
        UiCommand("name", pack.strings.get("commands.name", "重命名会话：/name 名称")),
        UiCommand(
            "thinking",
            pack.strings.get("commands.thinking", "思考档位：/thinking off|low|medium|high"),
        ),
        UiCommand("model", pack.strings.get("commands.model", "选择模型")),
        UiCommand("stop", pack.strings.get("commands.stop", "中止当前输出")),
    )

    init {
        current = this
        ui = ui.copy(
            commands = builtinCommands(),
            avatarShadow = if (prefs.contains("avatarShadow")) {
                prefs.getBoolean("avatarShadow", false)
            } else {
                false
            },
            bubbleShadow = if (prefs.contains("bubbleShadow")) {
                prefs.getBoolean("bubbleShadow", true)
            } else {
                true
            },
            skinId = prefs.getString("skinId", "").orEmpty(),
            activePackId = prefs.getString("activePackId", "").orEmpty(),
            contextOverrides = loadContextOverrides(),
        )
        refreshLocalPacks()
        // Restore an imported pack without waiting for the relay push.
        ui.activePackId.takeIf { it.isNotBlank() }?.let { id ->
            val dir = File(packsDir, id)
            if (dir.isDirectory) {
                val loaded = runCatching { readPackDir(dir) }.getOrNull()
                if (loaded != null && loaded.first.isNotEmpty()) {
                    applyPack(loaded.first, loaded.second, loaded.third, "local:$id", announce = false)
                }
            }
        }
        viewModelScope.launch {
            client.connected.collect { connected ->
                ui = ui.copy(connected = connected)
                if (connected) lastPongAt = System.currentTimeMillis()
            }
        }
        viewModelScope.launch {
            client.events.collect { event ->
                handle(event)
            }
        }
        // Loopback TCP has no keepalive: after the phone sleeps or the process
        // is frozen the socket can look alive while the relay is gone. Ping
        // periodically and drop the connection when the pongs stop.
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                if (!client.connected.value) continue
                val now = System.currentTimeMillis()
                if (lastPongAt > 0 && now - lastPongAt > 12_000) {
                    lastPongAt = 0
                    client.close()
                    if (foreground) startAutoReconnect()
                    continue
                }
                client.send(JSONObject().put("type", "relay_ping").put("id", "ping-${nextId++}"))
            }
        }
    }

    fun connect() {
        val ctx = getApplication<Application>()
        if (!RelayLauncher.hasPermission(ctx)) {
            ui = ui.copy(
                error = str(
                    "error.noPermission",
                    "没有 Termux RUN_COMMAND 权限。请在系统设置里允许本应用调用 Termux，然后点重连。",
                ),
            )
            return
        }
        val launchError = RelayLauncher.launch(ctx, port, token, cwd)
        if (launchError != null) {
            ui = ui.copy(
                error = str("error.relayLaunch", "无法启动 pig-relay：{error}")
                    .replace("{error}", launchError ?: ""),
                setup = true,
                setupHint = launchError,
            )
            return
        }
        ui = ui.copy(error = null)
        client.close()
        client.connect()
        viewModelScope.launch {
            // A leftover relay from an older install may still hold the port
            // and reject our token; it exits on the bad auth. Retry the launch
            // so the fresh relay can bind.
            repeat(3) {
                delay(1500)
                if (!client.connected.value) {
                    RelayLauncher.launch(ctx, port, token, cwd)
                }
            }
        }
    }

    /** Called from Activity.onResume: resume the relay/session after a lock or process freeze. */
    fun onForeground() {
        foreground = true
        if (liveActive) {
            val id = currentAssistant
            if (id != null) update(id) { it.copy(text = liveBuffer.toString()) }
        }
        val ctx = getApplication<Application>()
        if (prefs.getBoolean("keepalive", true)) {
            KeepAliveService.start(ctx)
        } else {
            KeepAliveService.stop(ctx)
        }
        if (!RelayLauncher.hasPermission(ctx)) {
            ui = ui.copy(
                error = str(
                    "error.noPermission",
                    "没有 Termux RUN_COMMAND 权限。请在系统设置里允许本应用调用 Termux，然后点重连。",
                ),
            )
            return
        }
        if (client.connected.value) {
            lastPongAt = System.currentTimeMillis()
            client.send(JSONObject().put("type", "relay_ping").put("id", "ping-${nextId++}"))
            return
        }
        startAutoReconnect()
    }

    /** Called from Activity.onPause: stop retrying while the app is in the background. */
    fun onBackground() {
        foreground = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    private fun startAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob = viewModelScope.launch {
            var nextLaunch = 0L
            while (foreground && !client.connected.value) {
                val ctx = getApplication<Application>()
                if (!RelayLauncher.hasPermission(ctx)) break
                if (!client.isConnecting) connect()
                val now = System.currentTimeMillis()
                if (now >= nextLaunch) {
                    // The relay may have been killed with the app process; keep
                    // asking Termux to run it until the socket comes back.
                    RelayLauncher.launch(ctx, port, token, cwd)
                    nextLaunch = now + 8_000
                }
                delay(1_000)
            }
        }
    }

    private fun savedSession(): String? =
        prefs.getString("lastSessionFile", null)?.takeIf { it.isNotBlank() }

    private fun rememberSession(path: String) {
        if (path.isBlank()) return
        if (savedSession() != path) prefs.edit().putString("lastSessionFile", path).apply()
        markVisited(path)
    }

    private fun clearLive() {
        liveActive = false
        liveBuffer.setLength(0)
    }

    private fun markVisited(path: String) {
        if (path.isBlank()) return
        val current = prefs.getStringSet("visitedSessions", emptySet()) ?: emptySet()
        if (path in current) return
        prefs.edit().putStringSet("visitedSessions", current + path).apply()
    }

    fun reconnect() {
        client.close()
        connect()
    }

    fun bootstrap() {
        val error = RelayLauncher.bootstrap(getApplication(), cwd)
        ui = ui.copy(
            setup = error == null,
            setupHint = error,
            toast = error ?: str("toast.setupStarted", "已在 Termux 里开始安装，完成后点「重试」"),
        )
    }

    fun dismissSetup() {
        ui = ui.copy(setup = false)
    }

    fun openTermux() {
        if (!RelayLauncher.openTermux(getApplication())) {
            RelayLauncher.openTermuxInstallPage(getApplication())
        }
    }

    fun openTermuxInstallPage() {
        RelayLauncher.openTermuxInstallPage(getApplication())
    }

    fun openAppSettings() {
        RelayLauncher.openAppSettings(getApplication())
    }

    /** Long-press on a tool chip: apply that mode to every tool card. */
    fun setToolMode(mode: String) {
        if (mode !in setOf("collapsed", "summary", "expanded")) return
        val label = when (mode) {
            "collapsed" -> str("tool.collapse", "收起")
            "expanded" -> str("tool.expand", "展开")
            else -> str("tool.summary", "摘要")
        }
        ui = ui.copy(toolMode = mode, toast = str("toast.toolModeAll", "已应用到全部工具：{mode}").replace("{mode}", label))
    }

    fun copyText(text: String) {
        if (text.isBlank()) return
        val manager = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Pig", text))
        ui = ui.copy(toast = str("toast.copied", "已复制"))
    }

    fun setKeepAlive(enabled: Boolean) {
        prefs.edit().putBoolean("keepalive", enabled).apply()
        if (enabled) {
            KeepAliveService.start(getApplication())
        } else {
            KeepAliveService.stop(getApplication())
        }
        ui = ui.copy(
            keepAlive = enabled,
            toast = if (enabled) {
                str("toast.keepAliveOn", "后台保活已开启")
            } else {
                str("toast.keepAliveOff", "后台保活已关闭")
            },
        )
    }

    fun setAvatarShadow(enabled: Boolean) {
        prefs.edit().putBoolean("avatarShadow", enabled).apply()
        ui = ui.copy(avatarShadow = enabled)
    }

    fun setBubbleShadow(enabled: Boolean) {
        prefs.edit().putBoolean("bubbleShadow", enabled).apply()
        ui = ui.copy(bubbleShadow = enabled)
    }

    private val packsDir: File
        get() = File(getApplication<Application>().filesDir, "ui-packs")

    /** Rescan `files/ui-packs/` and refresh the manager list. */
    fun refreshLocalPacks() {
        val dir = packsDir
        if (!dir.isDirectory) {
            ui = ui.copy(localPacks = emptyList())
            return
        }
        val packs = dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { pack ->
                val manifest = runCatching {
                    JSONObject(File(pack, "manifest.json").readText())
                }.getOrNull() ?: return@mapNotNull null
                var count = 0
                pack.walkTopDown().forEach { if (it.isFile) count++ }
                LocalPackInfo(
                    id = pack.name,
                    name = manifest.optString("name").ifBlank { pack.name },
                    version = manifest.optInt("version", 1),
                    files = count,
                    importedAt = pack.lastModified(),
                )
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        ui = ui.copy(localPacks = packs)
        val active = prefs.getString("activePackId", "").orEmpty()
        if (active.isNotBlank() && packs.none { it.id == active }) {
            selectPack("")
        }
    }

    /** Switch the active pack: `""` = relay pack, otherwise an imported pack id. */
    fun selectPack(id: String) {
        if (id == ui.activePackId) return
        prefs.edit().putString("activePackId", id).apply()
        if (id.isBlank()) {
            ui = ui.copy(activePackId = "")
            if (relayFiles.isNotEmpty()) {
                applyPack(relayFiles, relayAssets, relayHashes, relayHash, announce = true)
            }
            return
        }
        val dir = File(packsDir, id)
        val files = runCatching { readPackDir(dir) }.getOrNull()
        if (files == null) {
            ui = ui.copy(toast = str("toast.packMissing", "皮肤包不存在：{id}").replace("{id}", id))
            refreshLocalPacks()
            return
        }
        ui = ui.copy(activePackId = id)
        applyPack(files.first, files.second, files.third, "local:$id", announce = true)
    }

    /** Delete an imported pack; if it is active, switch back to the relay pack first. */
    fun deletePack(id: String) {
        if (!Regex("^[a-zA-Z0-9._-]{1,64}$").matches(id) || id == "." || id == "..") {
            ui = ui.copy(toast = str("pack.deleteFailed", "删除失败：{error}").replace("{error}", id))
            return
        }
        val target = File(packsDir, id)
        if (!target.isDirectory) {
            refreshLocalPacks()
            ui = ui.copy(toast = str("toast.packDeleted", "已删除：{name}").replace("{name}", id))
            return
        }
        if (ui.activePackId == id) {
            selectPack("")
            if (relayFiles.isEmpty()) {
                ui = ui.copy(
                    uiPack = UiPack(),
                    uiPackHash = "",
                    uiPackFiles = 0,
                    skinId = "",
                    activePackId = "",
                )
            }
        }
        val name = ui.localPacks.firstOrNull { it.id == id }?.name ?: id
        val ok = runCatching { target.deleteRecursively() }.getOrDefault(false)
        refreshLocalPacks()
        ui = ui.copy(
            toast = if (ok) {
                str("toast.packDeleted", "已删除：{name}").replace("{name}", name)
            } else {
                str("pack.deleteFailed", "删除失败：{error}").replace("{error}", id)
            },
        )
    }

    /** Import a `.zip` exported from a UI pack directory. */
    fun importPackZip(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val staging = File(ctx.cacheDir, "pack-import-${System.nanoTime()}")
                staging.mkdirs()
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name.replace('\\', '/')
                            if (!entry.isDirectory && name.isNotBlank() &&
                                !name.startsWith("/") && !name.split('/').contains("..")
                            ) {
                                val target = File(staging, name)
                                target.parentFile?.mkdirs()
                                target.outputStream().use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: error("无法读取文件")
                // Some exports wrap everything in one top-level directory.
                var root = staging
                val kids = staging.listFiles().orEmpty()
                if (kids.size == 1 && kids[0].isDirectory &&
                    !File(kids[0], "manifest.json").exists()
                ) {
                    root = kids[0]
                } else if (kids.size == 1 && kids[0].isDirectory) {
                    root = kids[0]
                }
                require(File(root, "manifest.json").isFile) { "压缩包里没有 manifest.json" }
                installPack(root)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { id ->
                    refreshLocalPacks()
                    selectPack(id)
                    ui = ui.copy(
                        toast = str("toast.packImported", "已导入皮肤包：{id}").replace("{id}", id),
                    )
                }.onFailure {
                    ui = ui.copy(
                        toast = str("toast.packImportFailed", "导入失败：{error}")
                            .replace("{error}", it.message ?: ""),
                    )
                }
            }
        }
    }

    /** Import a directory picked with the system folder chooser. */
    fun importPackTree(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val staging = File(ctx.cacheDir, "pack-import-${System.nanoTime()}")
                staging.mkdirs()
                copyDocumentTree(ctx, uri, staging)
                var root = staging
                val kids = staging.listFiles().orEmpty()
                if (kids.size == 1 && kids[0].isDirectory) root = kids[0]
                require(File(root, "manifest.json").isFile) { "目录里没有 manifest.json" }
                installPack(root)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { id ->
                    refreshLocalPacks()
                    selectPack(id)
                    ui = ui.copy(
                        toast = str("toast.packImported", "已导入皮肤包：{id}").replace("{id}", id),
                    )
                }.onFailure {
                    ui = ui.copy(
                        toast = str("toast.packImportFailed", "导入失败：{error}")
                            .replace("{error}", it.message ?: ""),
                    )
                }
            }
        }
    }

    /** Copy a staging pack into `files/ui-packs/<slug>` and return the id. */
    private fun installPack(root: File): String {
        val manifest = runCatching {
            JSONObject(File(root, "manifest.json").readText())
        }.getOrNull()
        val base = slug(manifest?.optString("name").orEmpty().ifBlank { root.name })
        var id = base
        var counter = 2
        while (File(packsDir, id).exists()) {
            id = "$base-$counter"
            counter++
        }
        val target = File(packsDir, id)
        target.mkdirs()
        root.copyRecursively(target, overwrite = true)
        root.deleteRecursively()
        return id
    }

    private fun slug(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5._-]+"), "-")
            .trim('-', '.', '_')
        return cleaned.ifBlank { "pack-${System.currentTimeMillis()}" }.take(48)
    }

    /** Read a pack directory: text files + base64 assets + asset hashes. */
    private fun readPackDir(dir: File): Triple<Map<String, String>, Map<String, String>, Map<String, String>> {
        val files = mutableMapOf<String, String>()
        val assets = mutableMapOf<String, String>()
        val hashes = mutableMapOf<String, String>()
        if (!dir.isDirectory) return Triple(files, assets, hashes)
        dir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val rel = file.relativeTo(dir).path.replace('\\', '/')
            if (rel.split('/').any { it.startsWith(".") }) return@forEach
            if (file.length() > 512 * 1024) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (rel.startsWith("assets/")) {
                assets[rel] = Base64.encodeToString(bytes, Base64.NO_WRAP)
                hashes[rel] = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.indexOf('\u0000') < 0) files[rel] = text
            }
        }
        return Triple(files, assets, hashes)
    }

    /** Recursively copy a SAF document tree into `target`. */
    private fun copyDocumentTree(ctx: Context, treeUri: Uri, target: File) {
        val resolver = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        fun walk(docUri: Uri, dir: File) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(docUri))
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val sub = File(dir, name)
                        sub.mkdirs()
                        walk(childUri, sub)
                    } else {
                        runCatching {
                            resolver.openInputStream(childUri)?.use { input ->
                                File(dir, name).outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                }
            }
        }
        walk(rootUri, target)
    }

    /** Switch to a `skins.json` entry; null/unknown ids are ignored. */
    fun selectSkin(id: String) {
        val base = basePack ?: return
        val skin = base.skins.firstOrNull { it.id == id } ?: return
        if (id == ui.skinId) return
        prefs.edit().putString("skinId", id).apply()
        ui = ui.copy(
            skinId = id,
            uiPack = base.withSkin(id),
            avatarShadow = if (prefs.contains("avatarShadow")) {
                ui.avatarShadow
            } else {
                skin.theme.avatarShadow
            },
            bubbleShadow = if (prefs.contains("bubbleShadow")) {
                ui.bubbleShadow
            } else {
                skin.theme.bubbleShadow
            },
            toast = str("toast.skinChanged", "已切换皮肤：{name}")
                .replace("{name}", skin.name),
        )
    }

    fun setWakeLock(enabled: Boolean) {
        prefs.edit().putBoolean("wakelock", enabled).apply()
        KeepAliveService.setWakeLock(getApplication(), enabled)
        ui = ui.copy(
            wakeLock = enabled,
            toast = if (enabled) {
                str("toast.wakeOn", "唤醒锁已开启（锁屏后 CPU 不休眠）")
            } else {
                str("toast.wakeOff", "唤醒锁已关闭")
            },
        )
    }

    fun openBatterySettings() {
        val ctx = getApplication<Application>()
        if (RelayLauncher.isIgnoringBatteryOptimizations(ctx)) {
            ui = ui.copy(toast = str("toast.batteryAllowed", "已在电池优化白名单里"))
            return
        }
        ui = ui.copy(
            toast = if (RelayLauncher.requestIgnoreBatteryOptimizations(ctx)) {
                str("toast.batteryRequest", "请在系统弹窗里选择「允许」")
            } else {
                str("toast.batteryManual", "无法直接请求，请在系统设置 → 电池里手动允许")
            },
        )
    }

    /** Handle `/name args`: built-ins map to RPC commands, extensions go through prompt. */
    private fun runSlashCommand(text: String) {
        val command = text.removePrefix("/").substringBefore(' ').trim()
        val args = text.removePrefix("/$command").trim()
        if (command.isBlank()) {
            ui = ui.copy(toast = str("toast.emptyCommand", "命令不能为空"))
            return
        }
        when (command) {
            "compact", "compress" -> compact()
            "new", "new_session" -> newSession()
            "stop", "abort" -> abort()
            "model" -> openModelPicker()
            "name" -> {
                if (args.isBlank()) {
                    ui = ui.copy(toast = str("toast.usageName", "用法：/name 新名称"))
                } else {
                    append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
                    client.send(
                        JSONObject()
                            .put("id", "rename-${nextId++}")
                            .put("type", "set_session_name")
                            .put("name", args.take(80)),
                    )
                }
            }
            "thinking" -> {
                if (args.isBlank()) {
                    ui = ui.copy(toast = str("toast.usageThinking", "用法：/thinking off|low|medium|high"))
                } else {
                    append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
                    setThinkingLevel(args)
                }
            }
            else -> {
                val known = ui.commands.any { it.name == command }
                if (!known) {
                    ui = ui.copy(
                        toast = str("toast.unknownCommand", "未知命令 /{command}")
                            .replace("{command}", command),
                    )
                    return
                }
                append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
                client.send(
                    JSONObject()
                        .put("id", "cmd-${nextId++}")
                        .put("type", "prompt")
                        .put("message", text),
                )
                viewModelScope.launch {
                    delay(1_500)
                    refreshCommands(true)
                }
            }
        }
    }

    fun compact() {
        if (ui.busy) {
            ui = ui.copy(toast = str("toast.busyCompact", "当前还在输出，等结束再压缩"))
            return
        }
        append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = "/compact"))
        ui = ui.copy(status = "compacting")
        client.send(JSONObject().put("id", "compact-${nextId++}").put("type", "compact"))
    }

    /**
     * steer=true injects the message into the running turn (PiG `steer`);
     * steer=false queues it after the turn (`followUp`).
     */
    fun sendPrompt(raw: String, steer: Boolean = true) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            runSlashCommand(text)
            return
        }
        val wasBusy = ui.busy
        append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
        currentAssistant = null
        markVisited(ui.sessionFile)
        ui = ui.copy(
            readOnly = false,
            pendingMessages = if (wasBusy) ui.pendingMessages + 1 else 0,
        )
        val command = JSONObject()
            .put("id", "p${nextId++}")
            .put("type", "prompt")
            .put("message", text)
        if (wasBusy) {
            command.put("streamingBehavior", if (steer) "steer" else "followUp")
        }
        client.send(command)
        if (wasBusy) requestState()
    }

    fun abort() = client.send(JSONObject().put("type", "abort"))

    fun openModelPicker() {
        showModelPicker = true
        requestModels()
    }

    fun closeModelPicker() {
        showModelPicker = false
    }

    fun requestModels() {
        modelsLoading = true
        client.send(JSONObject().put("id", "models-${nextId++}").put("type", "get_available_models"))
        client.send(
            JSONObject().put("id", "levels-${nextId++}").put("type", "get_available_thinking_levels"),
        )
    }

    fun selectModel(model: ModelInfo) {
        modelSwitchAccepted = false
        ui = ui.copy(
            modelSwitchPending = true,
            pendingModel = "${model.provider}/${model.id}",
        )
        client.send(
            JSONObject()
                .put("id", "setmodel-${nextId++}")
                .put("type", "set_model")
                .put("provider", model.provider)
                .put("modelId", model.id),
        )
    }

    fun selectModelById(provider: String, id: String) {
        val known = models.firstOrNull { it.provider == provider && it.id == id }
        selectModel(known ?: ModelInfo(provider, id, id, 0, 0, false, false))
    }

    fun setThinkingLevel(level: String) {
        if (level.isBlank()) return
        client.send(
            JSONObject()
                .put("id", "think-${nextId++}")
                .put("type", "set_thinking_level")
                .put("level", level),
        )
    }

    /** `compaction.enabled`; PiG persists it to settings.json. */
    fun setAutoCompaction(enabled: Boolean) {
        ui = ui.copy(autoCompaction = enabled)
        client.send(
            JSONObject()
                .put("id", "autocompact-${nextId++}")
                .put("type", "set_auto_compaction")
                .put("enabled", enabled),
        )
        ui = ui.copy(
            toast = if (enabled) {
                str("toast.autoCompactionOn", "已开启自动压缩")
            } else {
                str("toast.autoCompactionOff", "已关闭自动压缩，需要时用 /compact")
            },
        )
    }

    /** Session-scoped retry toggle; PiG does not expose its state back. */
    fun setAutoRetry(enabled: Boolean) {
        ui = ui.copy(autoRetry = enabled)
        client.send(
            JSONObject()
                .put("id", "autoretry-${nextId++}")
                .put("type", "set_auto_retry")
                .put("enabled", enabled),
        )
        ui = ui.copy(
            toast = if (enabled) {
                str("toast.autoRetryOn", "已开启自动重试")
            } else {
                str("toast.autoRetryOff", "已关闭自动重试")
            },
        )
    }

    fun setQueueMode(followUp: Boolean, mode: String) {
        if (mode != "all" && mode != "one-at-a-time") return
        ui = if (followUp) ui.copy(followUpMode = mode) else ui.copy(steeringMode = mode)
        client.send(
            JSONObject()
                .put("id", "queue-${nextId++}")
                .put("type", if (followUp) "set_follow_up_mode" else "set_steering_mode")
                .put("mode", mode),
        )
    }

    fun requestRelaySettings() {
        client.send(JSONObject().put("id", "settings-${nextId++}").put("type", "relay_settings"))
    }

    /** Writes allow-listed keys into ~/.pig/agent/settings.json via the relay. */
    fun updateRelaySettings(vararg patch: Pair<String, Any>) {
        val set = JSONObject()
        patch.forEach { (key, value) -> set.put(key, value) }
        client.send(
            JSONObject()
                .put("id", "settings-set-${nextId++}")
                .put("type", "relay_settings")
                .put("set", set),
        )
        ui = ui.copy(
            compactionReserveTokens = (patch.toMap()["compaction.reserveTokens"] as? Int)
                ?: ui.compactionReserveTokens,
            compactionKeepRecentTokens =
                (patch.toMap()["compaction.keepRecentTokens"] as? Int)
                    ?: ui.compactionKeepRecentTokens,
            cacheWarming = (patch.toMap()["cacheWarming"] as? String) ?: ui.cacheWarming,
            retryMaxRetries = (patch.toMap()["retry.maxRetries"] as? Int)
                ?: ui.retryMaxRetries,
            toast = str("toast.settingsSaved", "设置已保存，新会话生效"),
        )
    }

    /**
     * Auto-compaction threshold expressed as a share of the effective window.
     * PiG only knows `compaction.reserveTokens` (tokens held back), so the
     * percentage is converted here: reserve = window * (1 - percent).
     */
    fun setCompactionThreshold(percent: Int) {
        // PiG compares against the window it knows; while a pin has not reached
        // pig yet that is still the reported one, so prefer it over the display
        // value to avoid a reserve larger than the real window.
        val window = if (ui.contextWindowActual > 0) ui.contextWindowActual else ui.contextWindow
        if (window <= 0) {
            ui = ui.copy(
                toast = str("toast.contextUnknown", "未知上下文窗口，先切到一个可用会话"),
            )
            return
        }
        val pct = percent.coerceIn(10, 99)
        val reserve = ((window.toLong() * (100 - pct)) / 100).toInt().coerceAtLeast(1)
        updateRelaySettings("compaction.reserveTokens" to reserve)
    }

    fun requestSessions() {
        ui = ui.copy(sessionsLoading = true)
        client.send(JSONObject().put("id", "sessions-${nextId++}").put("type", "relay_sessions"))
    }

    /** Ask pig for the current context usage; refreshed on connect, session switch and after each turn. */
    private fun str(key: String, fallback: String): String =
        ui.uiPack.strings.get(key, fallback)

    /**
     * PiG reads models.json (and the provider catalog) once at start, so a pin
     * only becomes real when the agent process is replaced. The relay owns that
     * process: `relay_restart` stops it and reopens the same session file, which
     * keeps the transcript. Restart right away when idle, otherwise wait for the
     * running turn to settle.
     */
    private fun applyPinLive() {
        if (restartInFlight) return
        if (ui.busy) {
            ui = ui.copy(pendingAgentRestart = true)
            return
        }
        restartAgent()
    }

    private fun restartAgent() {
        if (restartInFlight) return
        restartInFlight = true
        ui = ui.copy(status = "restoring", pendingAgentRestart = false)
        client.send(
            JSONObject()
                .put("id", "restart-${nextId++}")
                .put("type", "relay_restart"),
        )
    }

    private fun requestStats() {
        client.send(JSONObject().put("id", "stats-${nextId++}").put("type", "get_session_stats"))
    }

    private fun loadContextOverrides(): Map<String, Int> {
        val raw = prefs.getString("contextOverrides", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        json.keys().forEach { key ->
            val tokens = json.optInt(key, 0)
            if (tokens > 0) out[key] = tokens
        }
        return out
    }

    /** Context window actually used for the percentage: user override wins. */
    fun contextWindowFor(provider: String, id: String): Int =
        ui.contextOverrides["$provider/$id"] ?: 0

    private fun effectiveContextWindow(reported: Int, key: String): Int =
        ui.contextOverrides[key]?.takeIf { it > 0 } ?: reported

    /**
     * Pin the context window for a model when the provider reports a wrong or
     * missing value. `tokens <= 0` clears the override.
     */
    fun setContextWindow(provider: String, id: String, tokens: Int) {
        val key = "$provider/$id"
        val map = ui.contextOverrides.toMutableMap()
        if (tokens > 0) map[key] = tokens else map.remove(key)
        ui = ui.copy(contextOverrides = map)
        prefs.edit()
            .putString("contextOverrides", JSONObject(map as Map<*, *>).toString())
            .apply()
        val window = effectiveContextWindow(
            ui.contextWindowActual.takeIf { it > 0 } ?: ui.contextWindow,
            ui.model,
        )
        ui = ui.copy(
            contextWindow = window,
            contextPercent = if (window > 0 && ui.contextTokens > 0) {
                ui.contextTokens * 100.0 / window
            } else {
                null
            },
        )
        requestStats()
        // Make the pin real for PiG too: pig reads models.json at start, so the
        // window applies to the next spawned agent instead of being display-only.
        client.send(
            JSONObject()
                .put("id", "model-override-${nextId++}")
                .put("type", "relay_model_override")
                .put("provider", provider)
                .put("model", id)
                .put("contextWindow", tokens.coerceAtLeast(0)),
        )
        ui = ui.copy(
            toast = if (tokens > 0) {
                str("toast.contextWindowSet", "上下文长度已设为 {tokens}")
                    .replace("{tokens}", formatTokenCount(tokens))
            } else {
                str("toast.contextWindowReset", "已恢复自动检测的上下文长度")
            },
        )
    }

    private fun formatTokenCount(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1000 -> "${tokens / 1000}K"
        else -> tokens.toString()
    }

    private val readRpcs = setOf(
        "get_state",
        "get_session_stats",
        "get_commands",
        "get_available_models",
        "get_available_thinking_levels",
        "get_messages",
        "get_last_assistant_text",
        "get_entries",
        "get_tree",
        "get_extensions",
        "get_fork_messages",
    )

    private val safeActionRpcs = setOf(
        "compact",
        "new_session",
        "abort",
        "set_model",
        "set_thinking_level",
        "cycle_model",
        "cycle_thinking_level",
        "clear_queue",
        "set_auto_compaction",
        "set_auto_retry",
        "abort_retry",
        "set_session_name",
    )

    /** Ask the sources declared in `layout.dataSources` that subscribe to [trigger]. */
    private fun refreshDataSources(trigger: String) {
        if (!client.connected.value) return
        ui.uiPack.layout.dataSources.forEach { (name, source) ->
            if (trigger in source.refresh && source.rpc in readRpcs) {
                val id = "src-${nextId++}"
                pendingRpcSources[id] = name
                val request = JSONObject().put("id", id).put("type", source.rpc)
                source.params?.let { params ->
                    val keys = params.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        request.put(key, params.opt(key))
                    }
                }
                client.send(request)
            }
        }
    }

    /** Run a named `layout.actions` entry, asking for confirmation when declared. */
    fun runPackAction(name: String, windowId: String? = null) {
        val action = ui.uiPack.layout.actions[name] ?: run {
            ui = ui.copy(toast = str("pack.actionMissing", "没有这个 pack 动作：{name}").replace("{name}", name))
            return
        }
        if (action.rpc !in safeActionRpcs) {
            ui = ui.copy(
                toast = str("pack.actionDenied", "pack 不允许调用 {rpc}")
                    .replace("{rpc}", action.rpc),
            )
            return
        }
        if (action.confirm.isNotBlank()) {
            ui = ui.copy(
                pendingPackAction = PackActionRequest(
                    name = name,
                    message = action.confirm,
                    windowId = windowId,
                ),
            )
        } else {
            executePackAction(name, action, windowId)
        }
    }

    fun confirmPackAction() {
        val pending = ui.pendingPackAction ?: return
        val action = ui.uiPack.layout.actions[pending.name] ?: return
        ui = ui.copy(pendingPackAction = null)
        executePackAction(pending.name, action, pending.windowId)
    }

    fun cancelPackAction() {
        ui = ui.copy(pendingPackAction = null)
    }

    private fun executePackAction(name: String, action: UiAction, windowId: String?) {
        val params = resolveActionParams(action.params, windowId)
        when (action.rpc) {
            "compact" -> compact()
            "new_session" -> newSession()
            "abort" -> abort()
            "set_model" -> {
                val provider = params.optString("provider")
                val modelId = params.optString("modelId")
                if (provider.isBlank() || modelId.isBlank()) {
                    ui = ui.copy(toast = str("pack.actionNeedsModel", "set_model 需要 provider 和 modelId"))
                } else {
                    selectModelById(provider, modelId)
                }
            }
            "set_thinking_level" -> {
                val level = params.optString("level")
                if (level.isBlank()) {
                    ui = ui.copy(toast = str("pack.actionNeedsLevel", "set_thinking_level 需要 level"))
                } else {
                    setThinkingLevel(level)
                }
            }
            "set_session_name" -> {
                val value = params.optString("name").take(80)
                client.send(
                    JSONObject()
                        .put("id", "pack-name-${nextId++}")
                        .put("type", "set_session_name")
                        .put("name", value),
                )
            }
            else -> {
                val request = JSONObject()
                    .put("id", "pack-${nextId++}")
                    .put("type", action.rpc)
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    request.put(key, params.opt(key))
                }
                client.send(request)
                requestState()
                requestStats()
            }
        }
    }

    /** Substitute `{{form.id}}` and `{{state.path}}` in action params. */
    private fun resolveActionParams(params: JSONObject?, windowId: String?): JSONObject {
        val out = JSONObject()
        if (params == null) return out
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            var value = params.optString(key)
            Regex("\\{\\{form\\.([a-zA-Z0-9_-]+)\\}\\}").findAll(value).forEach { match ->
                val field = match.groupValues[1]
                val form = if (windowId != null) {
                    formValues["$windowId/$field"].orEmpty()
                } else {
                    ""
                }
                value = value.replace(match.value, form)
            }
            Regex("\\{\\{state\\.([a-zA-Z0-9_.]+)\\}\\}").findAll(value).forEach { match ->
                val path = "state.${match.groupValues[1]}"
                val resolved = resolveRpcPath(ui.rpcData, path)?.toString().orEmpty()
                value = value.replace(match.value, resolved)
            }
            out.put(key, value)
        }
        return out
    }

    private fun storeRpcData(command: String, sourceName: String?, data: JSONObject) {
        var stored = data
        if (command == "get_session_stats") {
            stored = JSONObject(data.toString())
            val totals = stored.optJSONObject("tokens")
            if (totals != null) {
                val denominator = totals.optLong("input") +
                    totals.optLong("cacheRead") + totals.optLong("cacheWrite")
                if (denominator > 0) {
                    stored.put("cacheHitRate", totals.optLong("cacheRead") * 100.0 / denominator)
                }
            }
        }
        val alias = rpcAlias(command)
        var next = ui.rpcData
        if (alias != null) next = next + (alias to stored)
        if (sourceName != null) next = next + (sourceName to stored)
        if (alias != null || sourceName != null) ui = ui.copy(rpcData = next)
    }

    private fun rpcAlias(command: String): String? = when (command) {
        "get_state" -> "state"
        "get_session_stats" -> "stats"
        "get_commands" -> "commands"
        "get_available_models" -> "models"
        "get_available_thinking_levels" -> "thinkingLevels"
        "get_last_assistant_text" -> "lastAssistantText"
        "get_entries" -> "entries"
        "get_tree" -> "tree"
        "get_extensions" -> "extensions"
        "get_fork_messages" -> "forkMessages"
        else -> null
    }

    /** Refresh built-ins + extension commands; throttled unless forced. */
    fun refreshCommands(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCommandsAt < 2_000) return
        lastCommandsAt = now
        client.send(JSONObject().put("id", "commands-${nextId++}").put("type", "get_commands"))
    }

    private fun requestState() {
        val id = "state-${nextId++}"
        lastStateRequestId = id
        client.send(JSONObject().put("id", id).put("type", "get_state"))
    }

    private fun requestMessages() {
        val id = "msgs-${nextId++}"
        lastMessagesRequestId = id
        lastMessagesRequestAt = System.currentTimeMillis()
        client.send(JSONObject().put("id", id).put("type", "get_messages"))
    }

    fun switchSession(path: String) {
        if (path == ui.sessionFile) {
            ui = ui.copy(toast = str("toast.sameSession", "已经是当前会话"))
            return
        }
        doSwitch(path)
    }

    fun confirmSwitch() {
        val session = ui.pendingSwitch ?: return
        ui = ui.copy(pendingSwitch = null)
        doSwitch(session.path)
    }

    fun cancelSwitch() {
        ui = ui.copy(pendingSwitch = null)
    }

    private fun doSwitch(path: String) {
        if (path == ui.sessionFile) return
        markVisited(path)
        desiredSessionPath = path
        restorePending = false
        ui = ui.copy(status = "switching", extensionStatuses = emptyMap())
        // The relay parks the current pig and attaches/reads the target
        // session, so switching during a running turn is safe.
        client.send(
            JSONObject()
                .put("id", "switch-${nextId++}")
                .put("type", "switch_session")
                .put("sessionPath", path),
        )
    }

    fun newSession() {
        currentAssistant = null
        ui = ui.copy(readOnly = false, extensionStatuses = emptyMap())
        client.send(JSONObject().put("type", "new_session"))
    }

    /** Rename a session: the live pig renames the current one, the relay appends
     *  a session_info entry for any other file. */
    fun renameSession(session: SessionInfo, name: String) {
        val clean = name.trim().replace(Regex("[\\r\\n]+"), " ").take(80)
        val current = session.path == ui.sessionFile
        if (current && client.connected.value) {
            client.send(
                JSONObject()
                    .put("id", "rename-${nextId++}")
                    .put("type", "set_session_name")
                    .put("name", clean),
            )
        } else {
            client.send(
                JSONObject()
                    .put("id", "rename-${nextId++}")
                    .put("type", "relay_session_rename")
                    .put("path", session.path)
                    .put("name", clean),
            )
        }
        ui = ui.copy(sessions = ui.sessions.map { if (it.path == session.path) it.copy(name = clean) else it })
    }

    fun deleteSession(session: SessionInfo) {
        if (session.path == ui.sessionFile) {
            ui = ui.copy(toast = str("toast.currentNoDelete", "当前会话不能删除，先切换或新建会话"))
            return
        }
        client.send(
            JSONObject()
                .put("id", "delete-${nextId++}")
                .put("type", "relay_session_delete")
                .put("path", session.path),
        )
    }

    fun clearLocal() {
        ui = ui.copy(messages = emptyList(), extensionStatuses = emptyMap())
    }

    fun injectWindow(text: String) {
        val result = AppUiParser.parseWindow(text)
        val window = result.window
        if (window == null) {
            ui = ui.copy(
                windowError = result.warnings.firstOrNull()
                    ?: str("error.invalidWindow", "窗口无效"),
            )
            return
        }
        val existing = ui.windows.any { it.id == window.id }
        val windows = if (existing) {
            ui.windows.map { if (it.id == window.id) window else it }
        } else {
            ui.windows + window
        }
        ui = ui.copy(windows = windows, windowError = result.warnings.firstOrNull())
    }

    fun closeWindow(id: String) {
        val window = ui.windows.firstOrNull { it.id == id }
        if (window != null && window.source != "debug") {
            dismissedWindows[window.source] = window.id
        }
        windowPositions.remove(id)
        ui = ui.copy(windows = ui.windows.filterNot { it.id == id })
    }

    fun closeTopWindow() {
        ui.windows.lastOrNull()?.let { closeWindow(it.id) }
    }

    fun focusWindow(id: String) {
        val window = ui.windows.firstOrNull { it.id == id } ?: return
        if (ui.windows.lastOrNull()?.id == id) return
        ui = ui.copy(windows = ui.windows.filterNot { it.id == id } + window)
    }

    fun positionOf(id: String): WindowPos {
        windowPositions[id]?.let { return it }
        val index = ui.windows.indexOfFirst { it.id == id }.coerceAtLeast(0)
        return WindowPos(16f + (index % 5) * 14f, 96f + (index % 5) * 18f)
    }

    fun moveWindow(id: String, dx: Float, dy: Float, maxX: Float, maxY: Float) {
        val current = positionOf(id)
        windowPositions[id] = WindowPos(
            (current.x + dx).coerceIn(0f, maxX),
            (current.y + dy).coerceIn(0f, maxY),
        )
    }

    fun handleWindowAction(windowId: String, action: String) {
        if (action.startsWith("pack:")) {
            runPackAction(action.removePrefix("pack:"), windowId)
            return
        }
        when {
            action == "builtin:close" -> closeWindow(windowId)
            action.startsWith("builtin:copy:") -> {
                val value = action.removePrefix("builtin:copy:")
                val clipboard = getApplication<Application>()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pig", value))
                ui = ui.copy(toast = str("toast.copied", "已复制"))
            }
            action.startsWith("ext:") -> {
                val command = action.removePrefix("ext:").trim()
                if (command.isEmpty()) {
                    ui = ui.copy(toast = str("toast.extMissingCommand", "ext 动作缺少命令名"))
                    return
                }
                val payload = formPayload(windowId)
                val message = if (payload == null) "/$command" else "/$command $payload"
                client.send(
                    JSONObject()
                        .put("id", "act-${nextId++}")
                        .put("type", "prompt")
                        .put("message", message),
                )
                ui = ui.copy(
                    toast = str("toast.commandSent", "已发送 /{command}")
                        .replace("{command}", command),
                )
            }
            action.isBlank() -> Unit
            else -> ui = ui.copy(
                toast = str("toast.unknownAction", "未知动作: {action}")
                    .replace("{action}", action),
            )
        }
    }

    /**
     * Opens a drawer entry's window. The last parsed definition is cached, so a
     * window the user closed can be reopened without waiting for a pack push;
     * the command is still sent to refresh its contents from the extension.
     */
    /** Opens a cached pack window by id, regardless of autoOpen/nav. */
    fun openWindowById(id: String) {
        if (id.isBlank()) return
        refreshDataSources("window_open")
        dismissedWindows.entries.removeAll { it.value == id }
        val existing = ui.windows.firstOrNull { it.id == id }
        if (existing != null) {
            focusWindow(id)
            return
        }
        val cached = knownWindows[id] ?: run {
            ui = ui.copy(
                toast = str("toast.noWindow", "还没有窗口 {id}")
                    .replace("{id}", id),
            )
            return
        }
        ui = ui.copy(windows = ui.windows + cached)
    }

    fun openNavItem(item: NavItem) {
        if (item.action.startsWith("pack:")) {
            runPackAction(item.action.removePrefix("pack:"), null)
            refreshDataSources("window_open")
            return
        }
        dismissedWindows.entries.removeAll { it.value == item.window }
        refreshDataSources("window_open")
        if (ui.windows.none { it.id == item.window }) {
            knownWindows[item.window]?.let { cached ->
                windowPositions.remove(item.window)
                ui = ui.copy(windows = ui.windows + cached)
            }
        }
        if (item.command.isNotBlank()) {
            if (!ui.connected) {
                ui = ui.copy(
                    toast = str("toast.notConnectedRefresh", "未连接 relay，无法刷新 {label}")
                        .replace("{label}", item.label),
                )
                return
            }
            client.send(
                JSONObject()
                    .put("id", "nav-${nextId++}")
                    .put("type", "prompt")
                    .put("message", "/${item.command}"),
            )
            ui = ui.copy(
                toast = str("toast.openedWindow", "已打开 {label}")
                    .replace("{label}", item.label),
            )
        } else if (knownWindows[item.window] == null) {
            ui = ui.copy(
                toast = str("toast.noWindowFor", "还没有 {label} 窗口")
                    .replace("{label}", item.label),
            )
        }
    }

    fun dismissError() {
        ui = ui.copy(error = null)
    }

    fun consumeToast() {
        ui = ui.copy(toast = null)
    }

    fun answer(value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val pending = ui.pending ?: return
        val response = JSONObject().put("type", "extension_ui_response").put("id", pending.id)
        when {
            cancelled -> response.put("cancelled", true)
            confirmed != null -> response.put("confirmed", confirmed)
            else -> response.put("value", value ?: "")
        }
        client.send(response)
        ui = ui.copy(pending = null)
    }

    fun handleDebugEvent(text: String) {
        val event = runCatching { JSONObject(text) }.getOrNull()
        if (event == null) {
            ui = ui.copy(toast = "debug event 不是 JSON")
            return
        }
        handle(event)
    }

    private fun modelInfo(json: JSONObject): ModelInfo {
        val input = json.optJSONArray("input")
        val images = input != null && (0 until input.length()).any { input.optString(it) == "image" }
        return ModelInfo(
            provider = json.optString("provider"),
            id = json.optString("id"),
            name = json.optString("name", json.optString("id")),
            contextWindow = json.optInt("contextWindow", 0),
            maxTokens = json.optInt("maxTokens", 0),
            reasoning = json.optBoolean("reasoning", false),
            images = images,
        )
    }

    private fun handleWidget(event: JSONObject) {
        val key = event.optString("widgetKey", "widget").ifBlank { "widget" }
        val lines = event.optJSONArray("widgetLines")
        val placement = if (event.optString("widgetPlacement", "aboveEditor") == "belowEditor") {
            "belowEditor"
        } else {
            "aboveEditor"
        }
        val next = ui.widgets.filterNot { it.key == key }.toMutableList()
        if (lines != null) {
            next.add(
                AppWidget(
                    key = key,
                    lines = (0 until lines.length()).map { lines.optString(it) },
                    placement = placement,
                ),
            )
        }
        ui = ui.copy(widgets = next)
    }

    fun assetHash(path: String): String = packAssetHashes[path] ?: ""

    fun cachedImage(path: String, hash: String): ImageBitmap? = imageCache["$path@$hash"]

    fun loadImage(path: String, hash: String) {
        if (path.isBlank()) return
        val key = "$path@$hash"
        if (imageCache.containsKey(key) || !imageLoading.add(key)) return
        val encoded = packAssets[path]
        if (encoded == null) {
            imageLoading.remove(key)
            return
        }
        viewModelScope.launch {
            val image = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            imageLoading.remove(key)
            if (image != null) imageCache[key] = image
        }
    }

    fun formValue(windowId: String, fieldId: String, fallback: String): String =
        formValues["$windowId/$fieldId"] ?: fallback

    fun setFormValue(windowId: String, fieldId: String, value: String) {
        if (fieldId.isBlank()) return
        formValues["$windowId/$fieldId"] = value
    }

    fun formPayload(windowId: String): String? {
        val window = ui.windows.firstOrNull { it.id == windowId } ?: return null
        val fields = mutableListOf<UiNode>()
        collectFields(window.body, fields)
        if (fields.isEmpty()) return null
        val values = JSONObject()
        fields.forEach { node ->
            val stored = formValues["$windowId/${node.fieldId}"]
            val value = stored ?: when (node.type) {
                "toggle" -> node.checked.toString()
                else -> node.initialValue
            }
            values.put(node.fieldId, value)
        }
        return values.toString()
    }

    private fun collectFields(node: UiNode, out: MutableList<UiNode>) {
        if (node.type == "textfield" || node.type == "select" || node.type == "toggle") out.add(node)
        node.children.forEach { collectFields(it, out) }
    }

    private fun append(message: ChatMsg) {
        ui = ui.copy(messages = ui.messages + message)
    }

    /** True between `relay_restart` and its `relay_restarted` reply. */
    private var restartInFlight = false

    /** Errors written by the app itself (relay/transport), kept per session. */
    private val noticesDir: File
        get() = File(getApplication<Application>().filesDir, "notices")

    private fun noticeKey(): String? {
        val session = ui.sessionFile.ifBlank { ui.sessionId }
        if (session.isBlank()) return null
        return session.substringAfterLast('/').removeSuffix(".jsonl").ifBlank { null }
    }

    /**
     * A failure is part of the transcript, not a toast: append it as a notice
     * and keep it on disk so a restart or a session switch still shows it.
     */
    private fun appendNotice(text: String) {
        if (text.isBlank()) return
        append(ChatMsg(id = "n${nextId++}", role = Role.NOTICE, text = text, isError = true))
        val key = noticeKey() ?: return
        runCatching {
            noticesDir.mkdirs()
            File(noticesDir, "$key.jsonl").appendText(
                JSONObject()
                    .put("text", text)
                    .put("ts", System.currentTimeMillis())
                    .toString() + "\n",
            )
        }
    }

    /**
     * Drop a notice that stopped being true (e.g. an old relay rejected the
     * model-override frame and the notice is now stale). Removes it from the
     * transcript and from the on-disk log so it stops replaying.
     */
    private fun resolveNotice(text: String) {
        if (text.isBlank()) return
        ui = ui.copy(messages = ui.messages.filterNot { it.role == Role.NOTICE && it.text == text })
        val key = noticeKey() ?: return
        val file = File(noticesDir, "$key.jsonl")
        if (!file.isFile) return
        runCatching {
            val kept = file.readLines().filterNot { line ->
                runCatching { JSONObject(line).optString("text") }.getOrDefault("") == text
            }
            if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    /** Notices written earlier for the current session, oldest first. */
    private fun readNotices(): List<ChatMsg> {
        val key = noticeKey() ?: return emptyList()
        val file = File(noticesDir, "$key.jsonl")
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapIndexedNotNull { index, line ->
                    val item = runCatching { JSONObject(line) }.getOrNull()
                        ?: return@mapIndexedNotNull null
                    val text = item.optString("text")
                    if (text.isBlank()) {
                        null
                    } else {
                        ChatMsg(
                            id = "n${item.optLong("ts")}-$index",
                            role = Role.NOTICE,
                            text = text,
                            isError = true,
                        )
                    }
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun update(id: String, block: (ChatMsg) -> ChatMsg) {
        ui = ui.copy(messages = ui.messages.map { if (it.id == id) block(it) else it })
    }

    private fun remove(id: String) {
        ui = ui.copy(messages = ui.messages.filterNot { it.id == id })
    }

    private fun handle(event: JSONObject) {
        when (val type = event.optString("type")) {
            "relay_hello" -> {
                ui = ui.copy(error = null, setup = false, setupHint = null)
                lastPongAt = System.currentTimeMillis()
                restoreChecked = false
                restorePending = false
                desiredSessionPath = null
                // A freshly restarted relay has no session attached yet, so it
                // answers get_messages with an empty history. Wait for
                // get_state to settle the session (and restore it if needed)
                // before asking for messages, otherwise that empty answer
                // would wipe the transcript we are showing.
                needsHistory = true
                historyHealTried = false
                restartInFlight = false
                clearLive()
                ui = ui.copy(streamingId = null, pendingMessages = 0)
                pendingToolPartials.clear()
                // The relay may have kept the previous pig alive (mid-turn
                // reconnect). Ask it what it is doing before switching sessions.
                requestState()
                requestStats()
                requestSessions()
                requestRelaySettings()
                refreshCommands(true)
                refreshDataSources("connect")
                client.send(JSONObject().put("type", "relay_ui"))
            }
            "relay_pong" -> lastPongAt = System.currentTimeMillis()
            "relay_resume" -> handleResumeText(event.optString("text", ""))
            "relay_tool" -> handleRelayTool(event)
            "relay_client_error" -> {
                val message = event.optString("message")
                ui = ui.copy(error = message, setup = true, setupHint = message)
            }
            "relay_error" -> {
                val message = event.optString("message", "relay error")
                if (message.contains("relay_restart")) {
                    restartInFlight = false
                    ui = ui.copy(status = "")
                    appendNotice(
                        str("toast.restartUnsupported", "relay 版本过旧，无法重启 pig；上下文长度在重启后生效"),
                    )
                } else if (message.contains("relay_settings")) {
                    // Relay older than the settings frame: keep the tuning
                    // section read-only instead of surfacing a connection error.
                    ui = ui.copy(settingsRemote = false)
                } else if (message.contains("relay_model_override")) {
                    // Older relay: the pin stays display-only; say so once, in
                    // the transcript.
                    appendNotice(str("toast.contextWindowLocalOnly", "relay 版本过旧，上下文长度仅在本机生效"))
                } else {
                    ui = ui.copy(error = message, setup = true, setupHint = message)
                }
            }
            "relay_exit" -> {
                ui = ui.copy(
                    busy = false,
                    status = "",
                    error = str("error.relayExited", "pig 已退出（code {code}），正在重连…")
                        .replace("{code}", event.optInt("code").toString()),
                    streamingId = null,
                    pendingMessages = 0,
                )
                lastPongAt = 0
                clearLive()
                pendingToolPartials.clear()
                client.close()
                if (foreground) startAutoReconnect()
            }
            "relay_ui_pack" -> handleUiPack(event)
            "relay_settings" -> {
                val settings = event.optJSONObject("settings") ?: JSONObject()
                val compaction = settings.optJSONObject("compaction") ?: JSONObject()
                val retry = settings.optJSONObject("retry") ?: JSONObject()
                ui = ui.copy(
                    settingsRemote = true,
                    compactionReserveTokens = compaction.optInt("reserveTokens", 0),
                    compactionKeepRecentTokens = compaction.optInt("keepRecentTokens", 0),
                    cacheWarming = settings.optString("cacheWarming", ""),
                    retryMaxRetries = retry.optInt("maxRetries", 0),
                )
            }
            "relay_settings_error" -> {
                ui = ui.copy(
                    settingsRemote = false,
                    toast = str("toast.settingsUnsupported", "relay 版本过旧，无法读写 settings.json"),
                )
            }
            "relay_model_override" -> {
                // The optimistic toast from setContextWindow already covered it,
                // and the relay proved it understands the frame: retire any
                // "relay 版本过旧" notice left over from an older relay.
                resolveNotice(str("toast.contextWindowLocalOnly", "relay 版本过旧，上下文长度仅在本机生效"))
                applyPinLive()
            }
            "relay_restarted" -> {
                // Pig was restarted by us (or by the relay's own button): it now
                // reads models.json, so a pinned window is live.
                restartInFlight = false
                ui = ui.copy(
                    status = "",
                    busy = false,
                    pendingAgentRestart = false,
                    toast = str("toast.agentRestarted", "已重启 pig，新的上下文长度生效"),
                )
                requestState()
                requestStats()
                refreshCommands()
            }
            "relay_model_override_error" -> {
                val message = event.optString("message").takeIf { it.isNotBlank() }
                    ?: str("toast.contextWindowLocalOnly", "relay 版本过旧，上下文长度仅在本机生效")
                appendNotice(message)
            }
            "relay_sessions" -> handleSessions(event)
            "relay_session_renamed" -> {
                val path = event.optString("path")
                val name = event.optString("name")
                ui = ui.copy(
                    sessions = ui.sessions.map { if (it.path == path) it.copy(name = name) else it },
                    toast = str("toast.renamed", "会话已重命名"),
                )
                requestSessions()
            }
            "relay_session_deleted" -> {
                val path = event.optString("path")
                if (savedSession() == path) prefs.edit().remove("lastSessionFile").apply()
                ui = ui.copy(
                    sessions = ui.sessions.filterNot { it.path == path },
                    toast = str("toast.deleted", "会话已删除"),
                )
                requestSessions()
            }
            "relay_session_error" ->
                ui = ui.copy(toast = event.optString("message", str("toast.sessionError", "会话操作失败")))
            "response" -> handleResponse(event)
            "agent_start" -> ui = ui.copy(busy = true, status = "thinking")
            "turn_start" -> ui = ui.copy(status = "working")
            "agent_end", "agent_settled" -> {
                ui = ui.copy(busy = false, status = "", streamingId = null, pendingMessages = 0)
                clearLive()
                pendingToolPartials.clear()
                requestStats()
                refreshCommands()
                refreshDataSources("agent_end")
                if (ui.pendingAgentRestart) restartAgent()
            }
            "message_start" -> handleMessageStart(event.optJSONObject("message"))
            "message_update" -> handleMessageUpdate(event)
            "message_end" -> handleMessageEnd(event.optJSONObject("message"))
            "tool_execution_start" -> handleToolStart(event)
            "tool_execution_update" -> handleToolUpdate(event)
            "tool_execution_end" -> handleToolEnd(event)
            "extension_ui_request" -> handleUiRequest(event)
            "compaction_start" -> ui = ui.copy(status = "compacting")
            "compaction_end" -> {
                ui = ui.copy(status = "", contextCompacted = true)
                requestStats()
                refreshDataSources("compaction_end")
            }
            "error" -> {
                val message = event.optString("message", event.optString("error", "unknown error"))
                appendNotice(message)
                ui = ui.copy(busy = false, status = "")
            }
            else -> if (type.startsWith("auto_retry")) ui = ui.copy(status = "retrying")
        }
    }

    private fun handleResponse(event: JSONObject) {
        val responseCommand = event.optString("command")
        val responseId = event.optString("id")
        val isSourceRequest = pendingRpcSources.containsKey(responseId)
        if (!isSourceRequest && responseCommand == "get_state" &&
            responseId.isNotBlank() && responseId != lastStateRequestId
        ) {
            return
        }
        if (!isSourceRequest && responseCommand == "get_messages" &&
            responseId.isNotBlank() && responseId != lastMessagesRequestId
        ) {
            return
        }
        val taggedPath = event.optString("sessionPath")
        if (taggedPath.isNotBlank() && responseCommand in setOf("get_messages", "get_session_stats")) {
            val expected = desiredSessionPath ?: ui.sessionFile
            if (expected.isNotBlank() && taggedPath != expected) return
        }
        if (!event.optBoolean("success", true)) {
            val command = event.optString("command")
            if (command == "set_model") {
                modelSwitchAccepted = false
                ui = ui.copy(
                    modelSwitchPending = false,
                    pendingModel = "",
                    toast = str("toast.modelSwitchFailed", "模型切换失败"),
                )
            }
            if (command == "switch_session" && restorePending) {
                // The remembered session was deleted; fall back to a fresh one.
                restorePending = false
                desiredSessionPath = null
                prefs.edit().remove("lastSessionFile").apply()
                ui = ui.copy(status = "")
                requestState()
                requestStats()
                append(
                    ChatMsg(
                        id = "n${nextId++}",
                        role = Role.NOTICE,
                        text = str("toast.previousMissing", "上次会话已不存在，已开始新会话"),
                    ),
                )
                return
            }
            if (command == "switch_session") {
                desiredSessionPath = null
                ui = ui.copy(status = "")
                // Never leave the transcript empty after a failed switch:
                // reload whatever the pig is still working on.
                requestState()
                requestMessages()
                requestStats()
            }
            appendNotice("${event.optString("command")} failed: ${event.optString("error")}")
            return
        }
        val command = event.optString("command")
        // These commands carry no data but still change UI state.
        if (command == "compact" && !event.optBoolean("success", true)) {
            ui = ui.copy(status = "", toast = str("toast.compactFailed", "压缩失败"))
            return
        }
        if (command == "set_thinking_level") {
            val level = event.optJSONObject("data")?.optString("level").orEmpty()
            requestState()
            if (level.isNotBlank()) {
                ui = ui.copy(
                    toast = str("toast.thinkingChanged", "思考档位：{level}")
                        .replace("{level}", level),
                )
            }
            return
        }
        if (command == "set_session_name") {
            requestSessions()
            return
        }
        if (command == "switch_session" || command == "new_session") {
            if (command == "new_session") {
                // A new session supersedes the remembered one; the next
                // get_state response remembers its file instead.
                prefs.edit().remove("lastSessionFile").apply()
                desiredSessionPath = null
                restorePending = false
            }
            ui = ui.copy(
                messages = emptyList(),
                status = "",
                streamingId = null,
                pendingMessages = 0,
                contextPercent = null,
                contextTokens = 0,
                contextWindowActual = 0,
                contextCompacted = false,
                cacheHitRate = null,
                cacheReadTokens = 0,
                cacheInputTokens = 0,
            )
            clearLive()
            pendingToolPartials.clear()
            needsHistory = false
            historyHealTried = false
            requestState()
            requestMessages()
            requestStats()
            requestSessions()
            refreshDataSources("session_switch")
            return
        }
        val data = event.optJSONObject("data") ?: return
        storeRpcData(command, pendingRpcSources.remove(responseId), data)
        when (command) {
            "get_state" -> {
                val wasModelSwitch = ui.modelSwitchPending && modelSwitchAccepted
                val pendingModel = ui.pendingModel
                val model = data.optJSONObject("model")
                val label = listOfNotNull(
                    model?.optString("provider")?.takeIf { it.isNotBlank() },
                    model?.optString("id")?.takeIf { it.isNotBlank() },
                ).joinToString("/")
                val sessionId = data.optString("sessionId", "")
                val sessionFile = data.optString("sessionFile", ui.sessionFile)
                val streaming = data.optBoolean("isStreaming", false)
                val live = data.optBoolean("live", true)
                val switched = sessionId.isNotBlank() &&
                    ui.sessionId.isNotBlank() &&
                    sessionId != ui.sessionId
                if (switched) currentAssistant = null
                var status = ui.status
                var toast = ui.toast

                if (!restoreChecked) {
                    restoreChecked = true
                    val saved = savedSession()
                    if (saved != null && (sessionFile.isBlank() || sessionFile != saved)) {
                        if (streaming) {
                            // The pig is still producing on another session;
                            // attach to the live turn instead of switching.
                            toast = str("toast.attached", "已接上正在运行的会话")
                        } else {
                            restorePending = true
                            desiredSessionPath = saved
                            ui = ui.copy(status = "restoring")
                            client.send(
                                JSONObject()
                                    .put("id", "switch-${nextId++}")
                                    .put("type", "switch_session")
                                    .put("sessionPath", saved),
                            )
                            return
                        }
                    } else if (saved != null && sessionFile == saved) {
                        toast = str("toast.restored", "已恢复上次会话")
                    }
                }

                if (restorePending && sessionFile.isNotBlank() && sessionFile == desiredSessionPath) {
                    restorePending = false
                    desiredSessionPath = null
                    status = ""
                    toast = str("toast.restored", "已恢复上次会话")
                }
                if (!restorePending && sessionFile.isNotBlank()) {
                    rememberSession(sessionFile)
                    if (sessionFile == desiredSessionPath) desiredSessionPath = null
                }
                if (needsHistory && !restorePending && sessionFile.isNotBlank()) {
                    needsHistory = false
                    requestMessages()
                }
                // Recovery: the relay reports a session with messages but the
                // transcript is empty (a lost or dropped history reply).
                if (!historyHealTried && !restorePending && ui.messages.isEmpty() &&
                    data.optInt("messageCount", -1) > 0 &&
                    System.currentTimeMillis() - lastMessagesRequestAt > 2500
                ) {
                    historyHealTried = true
                    requestMessages()
                }

                if (!live) {
                    // Shown by the static read-only banner; the status bar is
                    // reserved for real activity (and its spinner).
                    status = ""
                }
                ui = ui.copy(
                    model = label,
                    sessionId = sessionId,
                    sessionFile = sessionFile,
                    busy = streaming,
                    readOnly = !live,
                    messages = if (switched) emptyList() else ui.messages,
                    contextWindow = effectiveContextWindow(
                        model?.optInt("contextWindow", ui.contextWindow) ?: ui.contextWindow,
                        label,
                    ),
                    // A relay without an attached pig still describes the same
                    // session; keep the last known usage instead of showing 0.
                    contextPercent = if (
                        label.isNotBlank() && ui.contextOverrides[label] != null &&
                        ui.contextTokens > 0 && effectiveContextWindow(0, label) > 0
                    ) {
                        // A pinned window must also drive the percentage, even
                        // when get_session_stats arrived before we knew the model.
                        ui.contextTokens * 100.0 / effectiveContextWindow(0, label)
                    } else {
                        ui.contextPercent
                    },
                    contextTokens = ui.contextTokens,
                    thinkingLevel = data.optString("thinkingLevel", ui.thinkingLevel),
                    pendingMessages = data.optInt("pendingMessageCount", ui.pendingMessages),
                    autoCompaction = data.optBoolean(
                        "autoCompactionEnabled",
                        ui.autoCompaction,
                    ),
                    // Retry is session-scoped and not reported by get_state.
                    autoRetry = if (switched) true else ui.autoRetry,
                    steeringMode = data.optString("steeringMode", ui.steeringMode),
                    followUpMode = data.optString("followUpMode", ui.followUpMode),
                    status = status,
                    toast = toast,
                )
                if (wasModelSwitch) {
                    val actual = listOfNotNull(
                        model?.optString("provider")?.takeIf { it.isNotBlank() },
                        model?.optString("id")?.takeIf { it.isNotBlank() },
                    ).joinToString("/")
                    ui = ui.copy(
                        modelSwitchPending = false,
                        pendingModel = "",
                        toast = if (actual.isNotBlank() && actual == pendingModel) {
                            str("toast.modelSwitched", "模型已切换：{model}")
                                .replace("{model}", actual)
                        } else {
                            str("toast.modelSwitchedActual", "已切换，实际模型：{model}")
                                .replace("{model}", actual.ifBlank { ui.model })
                        },
                    )
                }
                if (streaming) bindResumedAssistant()
            }
            "get_session_stats" -> {
                if (ui.readOnly) return
                val usage = data.optJSONObject("contextUsage")
                val rawPercent = usage?.opt("percent")
                val hasContext = usage != null &&
                    rawPercent != null && rawPercent != JSONObject.NULL &&
                    usage.opt("tokens") != null && usage.opt("tokens") != JSONObject.NULL
                ui = if (hasContext) {
                    val tokens = usage!!.optInt("tokens")
                    val actual = usage.optInt("contextWindow", ui.contextWindowActual)
                    val window = effectiveContextWindow(actual, ui.model)
                    val override = ui.contextOverrides[ui.model]?.takeIf { it > 0 }
                    ui.copy(
                        contextPercent = if (override != null && window > 0) {
                            tokens * 100.0 / window
                        } else {
                            usage.optDouble("percent")
                        },
                        contextTokens = tokens,
                        contextWindow = window,
                        contextWindowActual = actual,
                        contextCompacted = false,
                    )
                } else {
                    // PiG returns null contextUsage right after compaction; don't
                    // render that as 0% or keep the stale pre-compaction token count.
                    ui.copy(
                        contextPercent = null,
                        contextTokens = 0,
                        contextCompacted = ui.contextCompacted,
                    )
                }
                val totals = data.optJSONObject("tokens")
                if (totals != null) {
                    val input = totals.optLong("input")
                    val cacheRead = totals.optLong("cacheRead")
                    val cacheWrite = totals.optLong("cacheWrite")
                    val denominator = input + cacheRead + cacheWrite
                    ui = ui.copy(
                        cacheReadTokens = cacheRead,
                        cacheInputTokens = input + cacheWrite,
                        cacheHitRate = if (denominator > 0) {
                            cacheRead * 100.0 / denominator
                        } else {
                            null
                        },
                    )
                }
            }
            "get_messages" -> data.optJSONArray("messages")?.let { history ->
                val foreignEmpty = history.length() == 0 && ui.messages.isNotEmpty() &&
                    (taggedPath.isBlank() || taggedPath == ui.sessionFile)
                if (foreignEmpty) {
                    // The relay has no session attached yet; keep what is on
                    // screen and retry once get_state tells us the session.
                    needsHistory = true
                } else {
                    needsHistory = false
                    historyHealTried = true
                    renderHistory(history)
                }
            }
            "get_commands" -> {
                val array = data.optJSONArray("commands")
                val list = buildList {
                    addAll(builtinCommands())
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val name = item.optString("name").trim()
                            if (name.isBlank()) continue
                            add(UiCommand(name, item.optString("description").take(200)))
                        }
                    }
                }.distinctBy { it.name }
                ui = ui.copy(commands = list)
            }
            "get_available_models" -> {
                val array = data.optJSONArray("models")
                val list = buildList {
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)?.let { add(modelInfo(it)) }
                        }
                    }
                }
                models = list.sortedWith(compareBy({ it.provider }, { it.id }))
                modelsLoading = false
                ui = ui.copy(availableModelCount = models.size)
            }
            "get_available_thinking_levels" -> {
                val levels = data.optJSONArray("levels")
                ui = ui.copy(
                    thinkingLevels = if (levels == null) {
                        emptyList()
                    } else {
                        (0 until levels.length()).map { levels.optString(it) }
                    },
                )
            }
            "set_model" -> {
                modelSwitchAccepted = true
                val provider = data.optString("provider", ui.model.substringBefore('/'))
                val id = data.optString("id", ui.model.substringAfter('/'))
                ui = ui.copy(
                    model = "$provider/$id",
                    contextWindow = effectiveContextWindow(
                        data.optInt("contextWindow", ui.contextWindow),
                        "$provider/$id",
                    ),
                )
                client.send(
                    JSONObject().put("id", "levels-${nextId++}")
                        .put("type", "get_available_thinking_levels"),
                )
                requestState()
                requestStats()
            }
        }
    }

    /**
     * Derives pack-backed windows from `windows/<id>.json` and reconciles them with
     * the current list: add, update in place (position/z preserved), remove when
     * the file disappears, keep the last good window when a file fails to parse,
     * and honor the dismissed set.
     */
    private fun reconcileWindows(files: Map<String, String>, onDemand: Set<String> = emptySet()) {
        val windowFiles = files.filterKeys { it.startsWith("windows/") && it.endsWith(".json") }
        val parsed = mutableListOf<AppWindow>()
        val failedPaths = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        var duplicate: String? = null

        windowFiles.toSortedMap().forEach { (path, text) ->
            val result = AppUiParser.parseWindow(text, source = path)
            val window = result.window
            if (window == null) {
                failedPaths.add(path)
                warnings.add("$path: ${result.warnings.firstOrNull() ?: "invalid"}")
            } else {
                parsed.add(window)
            }
        }

        val byId = LinkedHashMap<String, AppWindow>()
        parsed.forEach { window ->
            if (byId.containsKey(window.id)) duplicate = "重复窗口 id: ${window.id}"
            byId[window.id] = window
        }

        val next = mutableListOf<AppWindow>()
        val placed = mutableSetOf<String>()

        ui.windows.forEach { current ->
            if (current.source == "debug") {
                if (!byId.containsKey(current.id)) {
                    next.add(current)
                    placed.add(current.id)
                }
                return@forEach
            }
            if (current.source !in windowFiles) return@forEach
            val updated = byId[current.id]
            when {
                updated != null -> {
                    next.add(updated)
                    placed.add(updated.id)
                }
                current.source in failedPaths -> {
                    next.add(current)
                    placed.add(current.id)
                }
            }
        }

        parsed.forEach { window ->
            if (window.id in placed) return@forEach
            if (!window.autoOpen) return@forEach
            if (window.id in onDemand) return@forEach
            if (dismissedWindows[window.source] == window.id) return@forEach
            dismissedWindows.remove(window.source)
            next.add(window)
            placed.add(window.id)
        }

        dismissedWindows.keys.retainAll(windowFiles.keys)
        knownWindows.entries.removeAll { it.value.source !in windowFiles.keys }
        parsed.forEach { knownWindows[it.id] = it }
        val ids = next.map { it.id }.toSet()
        windowPositions.keys.toList().forEach { if (it !in ids) windowPositions.remove(it) }

        ui = ui.copy(windows = next, windowError = warnings.firstOrNull() ?: duplicate)
    }

    private fun handleSessions(event: JSONObject) {
        val visited = prefs.getStringSet("visitedSessions", emptySet()) ?: emptySet()
        val array = event.optJSONArray("sessions")
        val sessions = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    add(
                        SessionInfo(
                            path = path,
                            id = item.optString("id"),
                            cwd = item.optString("cwd"),
                            name = item.optString("name"),
                            preview = item.optString("preview"),
                            updated = item.optLong("updated"),
                            size = item.optLong("size"),
                            live = item.optBoolean("live", false),
                            busy = item.optBoolean("busy", false),
                            visited = visited.contains(path),
                        ),
                    )
                }
            }
        }
        ui = ui.copy(sessions = sessions, sessionsLoading = false)
    }

    private fun renderHistory(messages: org.json.JSONArray) {
        val out = mutableListOf<ChatMsg>()
        val toolIds = mutableMapOf<String, String>()
        val lastInputRole = if (messages.length() > 0) {
            messages.optJSONObject(messages.length() - 1)?.optString("role")
        } else {
            null
        }
        toolMsgIds.clear()
        currentAssistant = null
        var counter = 1
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "user" -> {
                    val text = contentText(message)
                    if (text.isNotBlank()) out.add(ChatMsg("h${counter++}", Role.USER, text = text))
                }
                "assistant" -> {
                    val text = contentText(message)
                    if (text.isNotBlank()) out.add(ChatMsg("h${counter++}", Role.ASSISTANT, text = text))
                    // Pig stores failed turns as an assistant message with
                    // errorMessage; without this they vanish on reload.
                    val failure = message.optString("errorMessage")
                    if (failure.isNotBlank()) {
                        out.add(ChatMsg("h${counter++}", Role.NOTICE, text = failure, isError = true))
                    }
                    val blocks = message.optJSONArray("content") ?: continue
                    for (blockIndex in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(blockIndex) ?: continue
                        if (block.optString("type") != "toolCall") continue
                        val callId = block.optString("id")
                        val id = "h${counter++}"
                        if (callId.isNotBlank()) {
                            toolIds[callId] = id
                            // Let live tool_execution_update/end events find the
                            // card after a reconnect.
                            toolMsgIds[callId] = id
                        }
                        val pending = if (callId.isBlank()) null else pendingToolPartials.remove(callId)
                        out.add(
                            ChatMsg(
                                id = id,
                                role = Role.TOOL,
                                toolName = block.optString("name", "tool"),
                                toolArgs = block.optJSONObject("arguments")?.toString(2) ?: "",
                                toolResult = pending ?: "",
                                running = pending != null,
                            ),
                        )
                    }
                }
                "toolResult" -> {
                    val text = contentText(message)
                    val id = toolIds[message.optString("toolCallId")]
                    val position = out.indexOfFirst { it.id == id }
                    val meta = JSONObject()
                    message.optJSONObject("details")?.let { meta.put("details", it) }
                    message.optJSONObject("structuredContent")?.let { meta.put("structured", it) }
                    if (position >= 0) {
                        out[position] = out[position].copy(
                            running = false,
                            toolResult = text,
                            toolDetails = meta.toString(),
                            isError = message.optBoolean("isError", false),
                        )
                    } else {
                        out.add(
                            ChatMsg(
                                id = "h${counter++}",
                                role = Role.TOOL,
                                toolName = message.optString("toolName", "tool"),
                                toolResult = text,
                                isError = message.optBoolean("isError", false),
                            ),
                        )
                    }
                }
            }
        }
        // If the relay replayed an in-flight assistant message, put it back
        // after the history rebuild. Only the last input message can be the
        // partial assistant, so never overwrite an older completed bubble.
        val live = if (liveActive) liveBuffer.toString() else null
        if (!live.isNullOrEmpty() && lastInputRole == "assistant") {
            val last = out.lastOrNull()
            if (last?.role == Role.ASSISTANT) {
                if (last.text.length > live.length) {
                    // History got further than the relay snapshot; continue
                    // appending from the longer text.
                    liveBuffer.setLength(0)
                    liveBuffer.append(last.text)
                }
                out[out.lastIndex] = last.copy(text = if (last.text.length < live.length) live else last.text)
                currentAssistant = out.last().id
            } else {
                val id = "h${counter++}"
                out.add(ChatMsg(id = id, role = Role.ASSISTANT, text = live))
                currentAssistant = id
            }
        }
        // App-recorded failures (relay/transport) are not in pig's file, so
        // replay them from the per-session log; nothing above is duplicated.
        val known = out.filter { it.role == Role.NOTICE }.mapTo(HashSet()) { it.text }
        out += readNotices().filterNot { it.text in known }
        ui = ui.copy(messages = out)
        bindResumedAssistant()
    }

    private fun handleUiPack(event: JSONObject) {
        val filesObject = event.optJSONObject("files") ?: JSONObject()
        val files = buildMap {
            val keys = filesObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, filesObject.optString(key, ""))
            }
        }
        val assetsObject = event.optJSONObject("assets") ?: JSONObject()
        val assets = buildMap {
            val keys = assetsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, assetsObject.optString(key, ""))
            }
        }
        val hashesObject = event.optJSONObject("assetHashes") ?: JSONObject()
        val hashes = buildMap {
            val keys = hashesObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, hashesObject.optString(key, ""))
            }
        }
        val hash = event.optString("hash")
        relayFiles = files
        relayAssets = assets
        relayHashes = hashes
        relayHash = hash
        val relayName = runCatching {
            JSONObject(files["manifest.json"].orEmpty()).optString("name")
        }.getOrNull().orEmpty()
        ui = ui.copy(relayPackName = relayName.ifBlank { ui.relayPackName })
        // An imported pack takes precedence until the user switches back.
        if (ui.activePackId.isNotBlank()) return
        applyPack(files, assets, hashes, hash, announce = true)
    }

    /** Decode pack fonts under `assets/` into the app cache and map asset -> file. */
    private fun materializeFonts(assets: Map<String, String>): Map<String, String> {
        if (assets.isEmpty()) return emptyMap()
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "ui-fonts").apply { mkdirs() }
        val out = mutableMapOf<String, String>()
        assets.forEach { (path, encoded) ->
            val lower = path.lowercase()
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) {
                return@forEach
            }
            val bytes = runCatching {
                Base64.decode(encoded, Base64.NO_WRAP)
            }.getOrNull() ?: return@forEach
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val name = path.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val file = File(dir, "$digest-$name")
            if (!file.exists()) runCatching { file.writeBytes(bytes) }
            out[path] = file.absolutePath
        }
        return out
    }

    /** Parse and apply a pack (relay push or an imported local pack). */
    private fun applyPack(
        files: Map<String, String>,
        assets: Map<String, String>,
        hashes: Map<String, String>,
        hash: String,
        announce: Boolean,
    ) {
        packAssets = assets
        packAssetHashes = hashes
        val validImages = hashes.map { (path, hash) -> "$path@$hash" }.toSet()
        imageCache.keys.toList().forEach { if (it !in validImages) imageCache.remove(it) }
        imageLoading.retainAll(validImages)

        reconcileWindows(files, UiPackParser.navWindowIds(files))

        val result = UiPackParser.parse(files)
        val parsed = result.pack
        if (parsed == null) {
            val message = result.warnings.firstOrNull() ?: "unknown error"
            ui = ui.copy(
                uiPackError = message,
                toast = str("error.packNotApplied", "UI pack 未应用: {error}")
                    .replace("{error}", message),
            )
            return
        }
        val pack = parsed.copy(fontFiles = materializeFonts(assets))
        basePack = pack
        val preferredSkin = prefs.getString("skinId", "").orEmpty()
        val skinId = pack.skins.firstOrNull { it.id == preferredSkin }?.id
            ?: pack.defaultSkin.takeIf { id -> pack.skins.any { it.id == id } }
            ?: pack.skins.firstOrNull()?.id
            ?: ""
        if (skinId.isNotBlank()) prefs.edit().putString("skinId", skinId).apply()
        val resolvedPack = pack.withSkin(skinId)
        val changed = ui.uiPackHash.isNotEmpty() && hash != ui.uiPackHash
        // UI pack provides defaults; an explicit user toggle wins.
        val avatarShadow = if (prefs.contains("avatarShadow")) {
            prefs.getBoolean("avatarShadow", false)
        } else {
            resolvedPack.theme.avatarShadow
        }
        val bubbleShadow = if (prefs.contains("bubbleShadow")) {
            prefs.getBoolean("bubbleShadow", true)
        } else {
            resolvedPack.theme.bubbleShadow
        }
        ui = ui.copy(
            uiPack = resolvedPack,
            uiPackHash = hash,
            uiPackFiles = files.size,
            skinId = skinId,
            assetCount = assets.size,
            avatarShadow = avatarShadow,
            bubbleShadow = bubbleShadow,
            uiPackError = result.warnings.firstOrNull(),
            commands = builtinCommands(resolvedPack) + ui.commands.filter { command ->
                builtinCommands(resolvedPack).none { it.name == command.name }
            },
            toast = when {
                announce && changed -> str("toast.packApplied", "UI pack 已应用: {name} v{version}")
                    .replace("{name}", pack.name)
                    .replace("{version}", pack.version.toString())
                result.warnings.isNotEmpty() -> result.warnings.joinToString("; ")
                else -> ui.toast
            },
        )
        refreshDataSources("pack_load")
    }

    private fun handleMessageStart(message: JSONObject?) {
        if (message == null || message.optString("role") != "assistant") return
        val text = contentText(message)
        liveBuffer.setLength(0)
        liveBuffer.append(text)
        liveActive = true
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT, text = text))
        }
        if (text.isNotEmpty()) update(id) { it.copy(text = text) }
        ui = ui.copy(streamingId = id)
    }

    private fun handleMessageUpdate(event: JSONObject?) {
        if (event == null) return
        val update = event.optJSONObject("assistantMessageEvent") ?: return
        if (update.optString("type") != "text_delta") return
        val delta = update.optString("delta", "")
        if (delta.isEmpty()) return
        // liveText holds the text including the prefix replayed by relay_resume,
        // so replacing the bubble is safe after a mid-stream reconnect.
        liveBuffer.append(delta)
        liveActive = true
        if (currentAssistant == null) {
            currentAssistant = "a${nextId++}".also {
                append(ChatMsg(id = it, role = Role.ASSISTANT))
                ui = ui.copy(streamingId = it)
            }
        }
        scheduleLiveFlush()
    }

    /**
     * Deltas arrive at token speed; recomposing (and re-parsing Markdown) for
     * each one is what made scrolling drop frames. Coalesce to ~20 fps.
     */
    private fun scheduleLiveFlush() {
        // Compose is not drawing while the app is in the background; rebuilding
        // the message list every 50ms only allocates. liveText keeps growing and
        // onForeground flushes it once.
        if (!foreground) return
        if (liveFlushScheduled) return
        liveFlushScheduled = true
        viewModelScope.launch {
            delay(50)
            liveFlushScheduled = false
            if (!liveActive) return@launch
            val text = liveBuffer.toString()
            val id = currentAssistant ?: return@launch
            update(id) { it.copy(text = text) }
        }
    }

    /** Relay replayed the assistant text that was streamed before the reconnect. */
    private fun handleResumeText(text: String) {
        if (text.isEmpty()) return
        liveBuffer.setLength(0)
        liveBuffer.append(text)
        liveActive = true
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT))
        }
        update(id) { it.copy(text = text) }
        ui = ui.copy(streamingId = id)
    }

    /** Relay replayed a tool that is still running (arrives before get_messages). */
    private fun handleRelayTool(event: JSONObject) {
        val callId = event.optString("toolCallId")
        if (callId.isEmpty()) return
        val partial = event.optJSONObject("partialResult")?.let { contentText(it) } ?: ""
        val id = toolMsgIds[callId]
        if (id == null) {
            if (partial.isNotEmpty()) pendingToolPartials[callId] = partial
            return
        }
        update(id) {
            it.copy(
                running = true,
                toolResult = partial.ifBlank { it.toolResult },
                toolName = event.optString("toolName").ifBlank { it.toolName ?: "tool" },
            )
        }
    }

    /** Reattach a reconnecting client to the assistant message that is still streaming. */
    private fun bindResumedAssistant() {
        if (!ui.busy || currentAssistant != null) return
        val last = ui.messages.lastOrNull() ?: return
        if (last.role == Role.ASSISTANT) {
            currentAssistant = last.id
            ui = ui.copy(streamingId = last.id)
        }
    }

    private fun handleMessageEnd(message: JSONObject?) {
        if (message == null || message.optString("role") != "assistant") return
        val id = currentAssistant
        if (id == null) {
            clearLive()
            return
        }
        val text = contentText(message)
        update(id) { it.copy(text = text.ifBlank { it.text }) }
        // Tool-call-only assistant messages have no text; the tool card is the
        // visible representation, so drop the empty bubble.
        if (ui.messages.firstOrNull { it.id == id }?.text.isNullOrBlank()) remove(id)
        currentAssistant = null
        clearLive()
        ui = ui.copy(streamingId = null)
    }

    private fun handleToolStart(event: JSONObject) {
        val callId = event.optString("toolCallId")
        if (callId.isEmpty()) return
        val id = "t${nextId++}"
        toolMsgIds[callId] = id
        append(
            ChatMsg(
                id = id,
                role = Role.TOOL,
                toolName = event.optString("toolName", "tool"),
                toolArgs = event.optJSONObject("args")?.toString(2) ?: "",
                running = true,
            ),
        )
    }

    private fun handleToolUpdate(event: JSONObject) {
        val id = toolMsgIds[event.optString("toolCallId")] ?: return
        val partial = contentText(event.optJSONObject("partialResult"))
        if (partial.isNotEmpty()) update(id) { it.copy(toolResult = partial) }
    }

    private fun handleToolEnd(event: JSONObject) {
        val id = toolMsgIds.remove(event.optString("toolCallId")) ?: return
        val resultObject = event.optJSONObject("result")
        val result = contentText(resultObject)
        val meta = JSONObject()
        resultObject?.optJSONObject("details")?.let { meta.put("details", it) }
        resultObject?.optJSONObject("structuredContent")?.let { meta.put("structured", it) }
        update(id) {
            it.copy(
                running = false,
                toolResult = result.ifBlank { it.toolResult },
                toolDetails = meta.toString(),
                isError = event.optBoolean("isError", false),
            )
        }
    }

    private fun handleUiRequest(event: JSONObject) {
        val method = event.optString("method")
        when (method) {
            "confirm", "select", "input", "editor" -> {
                val options = event.optJSONArray("options")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                } ?: emptyList()
                ui = ui.copy(
                    pending = UiRequest(
                        id = event.optString("id"),
                        method = method,
                        title = event.optString("title", ""),
                        message = event.optString("message", "").takeIf { it.isNotBlank() },
                        options = options,
                        placeholder = event.optString("placeholder", "").takeIf { it.isNotBlank() },
                        prefill = event.optString("prefill", ""),
                    ),
                )
            }
            "notify" -> ui = ui.copy(toast = event.optString("message", ""))
            "setStatus" -> {
                // Protocol: a status line is keyed, and an empty text clears
                // only that key. Keeping them apart from the activity status
                // lets "工作中" and an extension's rate coexist.
                val key = event.optString("statusKey", "status").ifBlank { "status" }
                val text = event.optString("statusText", "")
                val next = ui.extensionStatuses.toMutableMap()
                if (text.isBlank()) next.remove(key) else next[key] = text
                ui = ui.copy(extensionStatuses = next)
            }
            "setTitle" -> ui = ui.copy(
                titleOverride = event.optString("title", "").takeIf { it.isNotBlank() },
            )
            "setWidget" -> handleWidget(event)
            else -> Unit // setWidget/setTitle/set_editor_text are not rendered in M0.
        }
    }

    private fun contentText(object0: JSONObject?): String {
        if (object0 == null) return ""
        val blocks = object0.optJSONArray("content") ?: return object0.optString("text", "")
        val builder = StringBuilder()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") builder.append(block.optString("text", ""))
        }
        return builder.toString()
    }

    override fun onCleared() {
        if (current === this) current = null
        windowPositions.clear()
        formValues.clear()
        // The Activity may be destroyed while the keep-alive service keeps the
        // process alive. Close the socket so a later ViewModel cannot end up
        // with a second relay connection; onResume reconnects and restores the
        // session file.
        client.close()
        KeepAliveService.stop(getApplication())
        super.onCleared()
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        @Volatile
        var current: AppViewModel? = null
            private set

        /** UI-pack string lookup for services / launchers outside a composition. */
        fun packString(key: String, fallback: String): String =
            current?.ui?.uiPack?.strings?.values?.get(key) ?: fallback
    }
}
