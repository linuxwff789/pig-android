package com.linuxwff.pigapp

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("pig", Context.MODE_PRIVATE)
    private val notifications by lazy { SessionNotifications(getApplication()) }

    /** Version name of this APK, for the relay version check. */
    val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull().orEmpty()
    /**
     * Debug/CI builds carry 0.1.0 or a dev tag; comparing them with a release
     * relay would warn forever, so only release builds take part.
     */
    private val versionChecksEnabled =
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 &&
            Regex("""^\d+(\.\d+)*$""").matches(appVersion)
    private var lastRelayWarnedVersion: String? = null
    /** A relay update is running; restart the relay when the installer returns. */
    private var relayUpdatePending = false

    val port: Int = prefs.getInt("port", 8791)
    var token: String = prefs.getString("token", null) ?: newToken().also {
        prefs.edit().putString("token", it).apply()
    }
        private set
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
    /**
     * How many messages the current transcript asks for. History is a suffix of
     * the session: the first page is small, pulling at the top doubles it.
     */
    private var historyWindow = HISTORY_PAGE
    private var lastCommandsAt = 0L
    private var modelSwitchAccepted = false
    /** A `new_session` is in flight; the banner stays until it is ready. */
    private var awaitingNewSession = false
    /** Session the user left when `new_session` was requested. pig swaps its
     *  runtime in place, so a `get_state` reply that still reports this one comes
     *  from the runtime being replaced, not from the new conversation. */
    private var newSessionFromFile = ""
    private var newSessionFromId = ""
    private var newSessionAt = 0L
    /** Prompts typed while the new session was still starting, in send order. */
    private val pendingNewSessionPrompts = mutableListOf<JSONObject>()
    /** Composer text per session, so switching away never loses a draft. */
    private val drafts = LinkedHashMap<String, String>()
    private var draftsFlushedAt = 0L
    /** Last cards each session showed, keyed by session file. setWidget is a
     *  push and no RPC asks for widgets again, so a client that was away for the
     *  push (or for the relay that cached it) would start with no card at all. */
    private val widgetSnapshots = LinkedHashMap<String, JSONObject>()
    /** Session file the cards in [ui] belong to; "" while no frame arrived. */
    private var widgetsFile = ""
    /** Thinking level to restore once the pending set_model is confirmed. */
    private var pendingThinkingRestore: String? = null
    /** `provider/model` -> last thinking level the user picked for it. */
    private var thinkingByModel: Map<String, String> = emptyMap()
    private val pendingRpcSources = mutableMapOf<String, String>()
    private var currentAssistant: String? = null
    private val toolMsgIds = mutableMapOf<String, String>()

    private var foreground = false
    /** True once the user picked a pack by hand, so relay pushes stop winning. */
    private var packPinnedByUser = false
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var lastPongAt = 0L
    private var desiredSessionPath: String? = null
    private var restorePending = false
    private var restoreChecked = false
    private val liveBuffer = StringBuilder()
    private val liveThinking = StringBuilder()
    // Rebuilding a multi-KB reasoning string 20×/s while only the answer text
    // streams is pure garbage: keep the last materialised value.
    private var liveThinkingText = ""
    private var liveThinkingDirty = false
    private val toolPartialRaw = mutableMapOf<String, JSONObject>()
    private var toolFlushScheduled = false
    private var liveActive = false
    private var liveFlushScheduled = false
    private val pendingToolPartials = mutableMapOf<String, String>()

    private fun builtinCommands(pack: UiPack = ui.uiPack): List<UiCommand> = listOf(
        UiCommand("compact", pack.strings.get("commands.compact", "压缩上下文")),
        UiCommand("new", pack.strings.get("commands.new", "新建会话")),
        UiCommand("name", pack.strings.get("commands.name", "重命名会话：/name 名称")),
        UiCommand(
            "thinking",
            pack.strings.get("commands.thinking", "思考档位：/thinking <级别>"),
        ),
        UiCommand("model", pack.strings.get("commands.model", "选择模型")),
        UiCommand("stop", pack.strings.get("commands.stop", "中止当前输出")),
    )

    init {
        current = this
        AppLog.attach(getApplication<Application>())
        // Re-apply the periodic WebDAV job from the stored config: an updated
        // APK may change its constraints/initial delay, and the old request
        // (enqueued by a version that connected alongside the app) must not
        // keep running with its previous shape.
        runCatching { WebDavScheduler.apply(getApplication(), WebDavConfig.from(prefs)) }
        ui = ui.copy(webdavReady = WebDavConfig.from(prefs).ready)
        AppLog.fact("relay", "127.0.0.1:$port")
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
            activePackId = initialPackId(),
            contextOverrides = loadContextOverrides(),
            collapsedWidgets = prefs.getStringSet("collapsedWidgets", emptySet())
                ?.toSet() ?: emptySet(),
            notifyDone = prefs.getBoolean("notifyDone", true),
            notifyAttention = prefs.getBoolean("notifyAttention", true),
            notifyError = prefs.getBoolean("notifyError", true),
            notifyCompacted = prefs.getBoolean("notifyCompacted", false),
            notifySound = prefs.getString("notifySound", "system") ?: "system",
            defaultModel = prefs.getString("defaultModel", "").orEmpty(),
            settingsSize = prefs.getString("settingsSize", "standard") ?: "standard",
        )
        thinkingByModel = loadThinkingLevels()
        drafts.putAll(loadDrafts())
        widgetSnapshots.putAll(loadWidgetSnapshots())
        // Upgrades from builds without `packPinned` treated any stored id as a
        // hand-made choice; a fresh install has no `activePackId` at all.
        val pinned = if (prefs.contains("packPinned")) {
            prefs.getBoolean("packPinned", false)
        } else {
            prefs.contains("activePackId")
        }
        packPinnedByUser = pinned && ui.activePackId.isNotBlank()
        refreshLocalPacks()
        // Restore a bundled or imported pack without waiting for the relay push.
        ui.activePackId.takeIf { it.isNotBlank() }?.let { id ->
            val loaded = loadPackFiles(id)
            if (loaded != null && loaded.first.isNotEmpty()) {
                applyPack(loaded.first, loaded.second, loaded.third, "local:$id", announce = false)
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
        // Termux reports the fate of every RUN_COMMAND through the PendingIntent
        // RelayLauncher attaches; a policy violation (fresh install, no
        // allow-external-apps) must surface instead of silently doing nothing.
        viewModelScope.launch {
            RunCommandFeedback.outcomes.collect { handleRunCommandOutcome(it) }
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
        // Uninstall + data restore replaces the prefs file while this process
        // keeps the token it generated at startup. Prefs is the durable copy
        // and also what the relay was launched with, so adopt it first.
        syncTokenFromPrefs()
        if (runBlockedByTermux) {
            // Every attempt would be refused before exec and Termux posts a
            // notification for each one; wait for the user (or onForeground).
            ui = ui.copy(
                connected = false,
                setup = true,
                setupHint = str(
                    "setup.blockedHint",
                    "Termux 的 allow-external-apps 还没开启，后台命令会被拒绝。" +
                        "点「复制命令」→「打开 Termux」→ 粘贴回车；装好回到这里会自动连接。",
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
                if (!client.connected.value && !runBlockedByTermux) {
                    RelayLauncher.launch(ctx, port, token, cwd)
                }
            }
        }
    }

    /**
     * Replace the relay process with the binary on disk. Needed after updating
     * the Termux side: the running relay keeps its old code until restarted.
     * The current pig is a child of the relay, so this waits for an idle turn.
     */
    fun restartRelay() {
        AppLog.event("vm", "restart relay requested")
        if (ui.busy || ui.status == "compacting") {
            ui = ui.copy(
                toast = str("toast.relayRestartBusy", "本轮结束后再重启 relay"),
            )
            return
        }
        val ctx = getApplication<Application>()
        val error = RelayLauncher.restart(ctx, port, token, cwd)
        if (error != null) {
            ui = ui.copy(
                toast = str("toast.relayRestartFailed", "重启 relay 失败：{error}")
                    .replace("{error}", error),
            )
            return
        }
        ui = ui.copy(
            connected = false,
            status = "restoring",
            toast = str("toast.relayRestarting", "relay 已重启，正在重连…"),
        )
        client.close()
        client.connect()
    }

    // ---- Backup, restore and uninstall ----------------------------------

    /**
     * Streams a backup into Download/Pig. Returns false on Android < 10, where
     * the caller has to ask through the system file picker instead.
     */
    fun exportBackupToDownload(includeSessions: Boolean, includeUi: Boolean): Boolean {
        val target = BackupExporter.create(
            getApplication(),
            BackupExporter.suggestedName(),
        ) ?: return false
        runBackupExport(target.uri, includeSessions, includeUi, target)
        return true
    }

    /** Export to a URI picked by the user (older Android, or a chosen folder). */
    fun exportBackupToUri(uri: Uri, includeSessions: Boolean, includeUi: Boolean) =
        runBackupExport(uri, includeSessions, includeUi, null)

    private fun runBackupExport(
        uri: Uri,
        includeSessions: Boolean,
        includeUi: Boolean,
        target: BackupExporter.Target?,
    ) {
        if (ui.maintenanceBusy) {
            ui = ui.copy(toast = str("backup.busy", "已有备份任务在进行"))
            target?.finish(false)
            return
        }
        if (!client.connected.value || !ui.backupRemote) {
            ui = ui.copy(
                toast = str(
                    "backup.needRelay",
                    "需要新版 relay 才能导出备份：重装 Termux 组件后重启 relay",
                ),
            )
            target?.finish(false)
            return
        }
        AppLog.event("backup", "export start sessions=$includeSessions ui=$includeUi")
        ui = ui.copy(
            maintenanceBusy = true,
            maintenanceLabel = str("backup.exporting", "正在导出备份…"),
            maintenanceSent = 0,
            maintenanceTotal = 0,
        )
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            var lastUi = 0L
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        client.exportBackup(out, includeSessions, includeUi) { sent ->
                            // One state write per chunk would redraw the sheet
                            // hundreds of times; four per second is plenty.
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 400) {
                                lastUi = now
                                ui = ui.copy(maintenanceSent = sent)
                            }
                        }
                    } ?: error("打不开输出流")
                }
            }
            target?.finish(result.isSuccess)
            AppLog.event(
                "backup",
                "export ${if (result.isSuccess) "ok" else "failed: " +
                    (result.exceptionOrNull()?.message ?: "?")}",
            )
            ui = result.fold(
                onSuccess = { done ->
                    ui.copy(
                        maintenanceBusy = false,
                        maintenanceSent = 0,
                        maintenanceTotal = 0,
                        toast = str(
                            "backup.exported",
                            "备份完成：{entries} 个文件、{size} → {path}（含密钥与模型配置）",
                        )
                            .replace("{entries}", done.optInt("entries").toString())
                            .replace("{size}", formatSize(done.optLong("bytes")))
                            .replace(
                                "{path}",
                                target?.location ?: uri.lastPathSegment ?: uri.toString(),
                            ),
                    )
                },
                onFailure = { error ->
                    ui.copy(
                        maintenanceBusy = false,
                        maintenanceSent = 0,
                        maintenanceTotal = 0,
                        toast = str("backup.exportFailed", "导出失败：{error}")
                            .replace("{error}", error.message ?: error.toString()),
                    )
                },
            )
        }
    }

    /**
     * Restores an archive the user picked. Destructive by nature: the settings
     * dialog confirms first, and the relay makes its own automatic backup of
     * `~/.pig` before extracting anything.
     */
    fun importBackup(uri: Uri, name: String) {
        val ctx = getApplication<Application>()
        startRestore(
            name = name,
            size = {
                runCatching {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: -1L
            },
            open = {
                ctx.contentResolver.openInputStream(uri) ?: error("打不开备份文件")
            },
        )
    }

    /**
     * Restores an archive from any stream. [open] is called on the IO thread so
     * a WebDAV download and a file picker share the same path.
     */
    fun startRestore(name: String, size: () -> Long, open: () -> java.io.InputStream) {
        if (ui.maintenanceBusy) {
            ui = ui.copy(toast = str("backup.busy", "已有备份任务在进行"))
            return
        }
        if (!client.connected.value || !ui.backupRemote) {
            ui = ui.copy(
                toast = str(
                    "backup.needRelay",
                    "需要新版 relay 才能导出备份：重装 Termux 组件后重启 relay",
                ),
            )
            return
        }
        AppLog.event("backup", "restore start $name")
        ui = ui.copy(
            maintenanceBusy = true,
            maintenanceLabel = str("backup.importing", "正在恢复备份…"),
            maintenanceSent = 0,
            maintenanceTotal = 0,
        )
        viewModelScope.launch {
            var lastUi = 0L
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = runCatching { size() }.getOrDefault(-1L)
                    open().use { stream ->
                        client.restoreBackup(stream, name, bytes) { sent, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 400) {
                                lastUi = now
                                ui = ui.copy(
                                    maintenanceSent = sent,
                                    maintenanceTotal = total,
                                )
                            }
                        }
                    }
                }
            }
            AppLog.event(
                "backup",
                "restore ${if (result.isSuccess) "ok" else "failed: " +
                    (result.exceptionOrNull()?.message ?: "?")}",
            )
            result.fold(
                onSuccess = { done ->
                    val warning = done.optString("warning")
                    val fixed = done.optJSONArray("settingsFixed")?.length() ?: 0
                    val base = str(
                        "backup.imported",
                        "已恢复 {entries} 个文件；原数据备份在 {backup}",
                    )
                        .replace("{entries}", done.optInt("entries").toString())
                        .replace(
                            "{backup}",
                            done.optString("backup").ifBlank { "~/.pig/backups" },
                        )
                    val extra = when {
                        warning.isNotBlank() -> "\n$warning"
                        fixed > 0 -> str(
                            "backup.importedFixed",
                            "；已自动移除 {n} 个冲突的扩展配置（保留 relay 内置副本）",
                        ).replace("{n}", fixed.toString())
                        else -> ""
                    }
                    ui = ui.copy(
                        maintenanceBusy = false,
                        maintenanceSent = 0,
                        maintenanceTotal = 0,
                        toast = base + extra,
                    )
                    // The relay stopped its pig processes; a fresh connection
                    // loads the restored settings, models and UI pack.
                    client.close()
                    client.connect()
                },
                onFailure = { error ->
                    ui = ui.copy(
                        maintenanceBusy = false,
                        maintenanceSent = 0,
                        maintenanceTotal = 0,
                        toast = str("backup.importFailed", "恢复失败：{error}")
                            .replace("{error}", error.message ?: error.toString()),
                    )
                },
            )
        }
    }

    // ---- WebDAV backup ---------------------------------------------------

    fun webDavConfig(): WebDavConfig = WebDavConfig.from(prefs)

    fun saveWebDav(config: WebDavConfig) {
        WebDavConfig.save(prefs, config)
        WebDavScheduler.apply(getApplication(), WebDavConfig.from(prefs))
        ui = ui.copy(
            webdavBusy = false,
            webdavReady = config.ready,
            webdavMessage = str("webdav.saved", "已保存"),
            webdavMessageError = false,
            toast = str("webdav.savedToast", "WebDAV 设置已保存"),
        )
    }

    /**
     * "Sync now" outside the dialog: one upload with the saved config. Same
     * path as the dialog button, so both report through the toast.
     */
    fun syncNowWebDav() {
        val config = WebDavConfig.from(prefs)
        AppLog.event(
            "webdav",
            "sync now tapped: ready=${config.ready} relay=${client.connected.value} " +
                "backupRemote=${ui.backupRemote} busy=${ui.webdavBusy}",
        )
        if (!config.ready) {
            val text = str("webdav.notConfigured", "先在设置里配置 WebDAV 服务器")
            ui = ui.copy(toast = text, webdavMessage = text, webdavMessageError = true)
            bubble(text)
            return
        }
        backupNowWebDav(config, notify = true)
    }

    /**
     * Feedback that also shows above a dialog: the snackbar lives in the
     * activity window, so a message raised while settings is open is dimmed
     * behind it and looks like nothing happened.
     */
    private fun bubble(text: String) {
        runCatching {
            android.widget.Toast.makeText(getApplication(), text, android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }

    fun testWebDav(config: WebDavConfig) = webDavRun {
        WebDavClient.test(config)
        str("webdav.testOk", "连接成功，目录可写")
    }

    /**
     * One manual upload. Scheduled runs live in [WebDavBackupWorker]: they open
     * their own relay connection because no UI is there to own one.
     */
    fun backupNowWebDav(config: WebDavConfig, notify: Boolean = false) {
        if (!client.connected.value || !ui.backupRemote) {
            val text = str(
                "backup.needRelay",
                "需要新版 relay 才能导出备份：重装 Termux 组件后重启 relay",
            )
            AppLog.event(
                "webdav",
                "backup skipped: relay=${client.connected.value} backupRemote=${ui.backupRemote}",
            )
            ui = ui.copy(toast = text, webdavMessage = text, webdavMessageError = true)
            if (notify) bubble(text)
            return
        }
        if (ui.webdavBusy || ui.maintenanceBusy) {
            AppLog.event("webdav", "backup skipped: busy=${ui.webdavBusy} maintenance=${ui.maintenanceBusy}")
            val text = str("webdav.working", "处理中…")
            if (notify) bubble(text)
            return
        }
        AppLog.event("webdav", "backup start: ${webDavTarget(config)} dir=${config.dir} keep=${config.keep}")
        val started = str("webdav.uploading", "正在打包并上传…")
        ui = ui.copy(
            webdavBusy = true,
            webdavMessage = started,
            webdavMessageError = false,
        )
        // Instant confirmation that the tap landed, above the dialog.
        if (notify) bubble(started)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = WebDavClient.PREFIX +
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                        WebDavClient.SUFFIX
                    val file = File(ctx.cacheDir, name)
                    try {
                        file.outputStream().use { out ->
                            client.exportBackup(out, includeSessions = true, includeUi = true) { }
                        }
                        WebDavClient.upload(config, name, file)
                        val removed = runCatching { WebDavClient.prune(config, config.keep) }
                            .getOrDefault(0)
                        name to removed to file.length()
                    } finally {
                        file.delete()
                    }
                }
            }
            ui = result.fold(
                onSuccess = { (pair, bytes) ->
                    val (name, removed) = pair
                    val extra = if (removed > 0) {
                        str("webdav.pruned", "，清理 {n} 个旧备份").replace("{n}", removed.toString())
                    } else {
                        ""
                    }
                    val message = str("webdav.uploaded", "已上传 {name}（{size}）{extra}")
                        .replace("{name}", name)
                        .replace("{size}", formatSize(bytes))
                        .replace("{extra}", extra)
                    AppLog.event("webdav", "backup ok: $name ($bytes bytes), pruned $removed")
                    if (notify) bubble(message)
                    ui.copy(
                        webdavBusy = false,
                        webdavMessage = message,
                        webdavMessageError = false,
                        toast = str("webdav.uploadedToast", "WebDAV 备份完成"),
                    )
                },
                onFailure = { error ->
                    val text = error.message ?: error.toString()
                    AppLog.event(
                        "webdav",
                        "backup failed: ${error.javaClass.simpleName}: ${error.message}",
                    )
                    // The dialog is often closed when "sync now" runs: the row
                    // status and the toast (or a real toast above the dialog)
                    // have to carry the reason.
                    if (notify) bubble(text)
                    ui.copy(
                        webdavBusy = false,
                        webdavMessage = text,
                        webdavMessageError = true,
                        toast = text,
                    )
                },
            )
            runCatching { withContext(Dispatchers.IO) { WebDavClient.list(config) } }
                .onSuccess { ui = ui.copy(webdavBackups = it) }
        }
    }

    fun loadWebDavBackups(config: WebDavConfig) {
        webDavRun {
            val list = WebDavClient.list(config)
            ui = ui.copy(webdavBackups = list)
            if (list.isEmpty()) {
                str("webdav.none", "远端还没有备份")
            } else {
                str("webdav.count", "找到 {n} 个备份").replace("{n}", list.size.toString())
            }
        }
    }

    /** Downloads an archive and hands it to the normal restore path. */
    fun restoreWebDav(config: WebDavConfig, name: String) {
        if (!client.connected.value || !ui.backupRemote) {
            ui = ui.copy(
                toast = str(
                    "backup.needRelay",
                    "需要新版 relay 才能导出备份：重装 Termux 组件后重启 relay",
                ),
            )
            return
        }
        if (ui.webdavBusy || ui.maintenanceBusy) return
        ui = ui.copy(
            webdavBusy = true,
            webdavMessage = str("webdav.downloading", "正在下载 {name}…").replace("{name}", name),
            webdavMessageError = false,
        )
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val file = File(ctx.cacheDir, name)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    WebDavClient.download(config, name, file)
                    file.length()
                }
            }
            ui = ui.copy(webdavBusy = false, webdavMessage = "")
            result.fold(
                onSuccess = { size ->
                    startRestore(name = name, size = { size }, open = { file.inputStream() })
                },
                onFailure = { error ->
                    ui = ui.copy(
                        webdavMessage = error.message ?: error.toString(),
                        webdavMessageError = true,
                    )
                },
            )
        }
    }

    /**
     * A scheduled upload on the live connection. Returns null when the app is
     * not attached to the relay; the background worker then waits or starts
     * the relay instead of opening a second connection (which would fight this
     * one for the relay's single client slot).
     */
    suspend fun scheduledWebDavBackup(config: WebDavConfig): String? {
        if (!client.connected.value || !ui.backupRemote) return null
        return withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = WebDavClient.PREFIX +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                WebDavClient.SUFFIX
            val file = File(ctx.cacheDir, name)
            try {
                file.outputStream().use { out ->
                    client.exportBackup(out, includeSessions = true, includeUi = true) { }
                }
                WebDavClient.upload(config, name, file)
                runCatching { WebDavClient.prune(config, config.keep) }
                name
            } finally {
                file.delete()
            }
        }
    }

    /** Scheme + host + port only: no credentials, no path, no query. */
    private fun webDavTarget(config: WebDavConfig): String = runCatching {
        val url = java.net.URL(config.url.trim())
        val port = if (url.port > 0) ":${url.port}" else ""
        "${url.protocol}://${url.host}$port"
    }.getOrDefault("<invalid url>")

    private fun webDavRun(block: suspend () -> String) {
        if (ui.webdavBusy) {
            AppLog.event("webdav", "run skipped: busy")
            return
        }
        ui = ui.copy(
            webdavBusy = true,
            webdavMessage = str("webdav.working", "处理中…"),
            webdavMessageError = false,
        )
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            AppLog.event(
                "webdav",
                result.fold(
                    onSuccess = { "ok: $it" },
                    onFailure = { "failed: ${it.javaClass.simpleName}: ${it.message}" },
                ),
            )
            ui = result.fold(
                onSuccess = {
                    ui.copy(webdavBusy = false, webdavMessage = it, webdavMessageError = false)
                },
                onFailure = {
                    ui.copy(
                        webdavBusy = false,
                        webdavMessage = it.message ?: it.toString(),
                        webdavMessageError = true,
                    )
                },
            )
        }
    }

    /**
     * Stops pig and the relay and deletes their binaries; [purgeData] also
     * removes `~/.pig`. The Termux result arrives through RunCommandFeedback.
     */
    fun uninstallPig(purgeData: Boolean) {
        if (ui.maintenanceBusy) {
            ui = ui.copy(toast = str("backup.busy", "已有备份任务在进行"))
            return
        }
        if (ui.busy || ui.status == "compacting") {
            ui = ui.copy(toast = str("backup.uninstallBusy", "本轮结束后再卸载"))
            return
        }
        val error = RelayLauncher.uninstall(getApplication(), purgeData, cwd)
        if (error != null) {
            ui = ui.copy(
                toast = str("backup.uninstallFailed", "卸载失败：{error}")
                    .replace("{error}", error),
            )
            return
        }
        uninstallPurge = purgeData
        AppLog.event("vm", "uninstall requested purge=$purgeData")
        ui = ui.copy(
            maintenanceBusy = true,
            maintenanceLabel = str("backup.uninstalling", "正在卸载…"),
            maintenanceSent = 0,
            maintenanceTotal = 0,
        )
        // The relay is about to die with its binary: stop reconnecting, or a
        // retry would race the deletion and relaunch a half-removed install.
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        client.close()
    }

    private var uninstallPurge = false

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 ->
            "%.1f".format(bytes / (1L shl 30).toFloat()).removeSuffix(".0") + " GB"
        bytes >= 1L shl 20 ->
            "%.1f".format(bytes / (1L shl 20).toFloat()).removeSuffix(".0") + " MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Called from Activity.onResume: resume the relay/session after a lock or process freeze. */
    fun onForeground() {
        foreground = true
        // Coming back from Termux is the moment the user could have enabled
        // allow-external-apps, so allow one more launch attempt.
        runBlockedByTermux = false
        notifications.clear()
        if (liveActive) {
            val id = currentAssistant
            if (id != null) {
                update(id) { it.copy(text = liveBuffer.toString(), thinking = liveThinkingValue()) }
            }
        }
        if (toolPartialRaw.isNotEmpty()) {
            flushToolPartials()
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
        saveDrafts(force = true)
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
                if (runBlockedByTermux) break
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

    /**
     * App-data restore (uninstall + restore, adb backup) can swap the prefs
     * file under a running process, leaving [token] stale. Prefs is the copy
     * the relay was launched with, so adopt it before reconnecting.
     */
    private fun syncTokenFromPrefs() {
        val stored = prefs.getString("token", null)?.takeIf { it.isNotBlank() } ?: return
        if (stored == token) return
        token = stored
        client.token = stored
    }

    private var lastTokenRecoveryAt = 0L
    /** Set when Termux refused RUN_COMMAND; stops the retry loop from spamming it. */
    private var runBlockedByTermux = false
    private var warnedMissingExtensions = ""
    private var warnedIncompleteRelayPack = false

    private fun isCommandRegistered(command: String): Boolean {
        val name = command.trim().removePrefix("/").substringBefore(' ').trim()
        if (name.isEmpty()) return false
        return ui.commands.any { it.name == name }
    }

    /**
     * `pig install` failures are only a one-line warning in pig's own stderr
     * ("Could not install: …"), after which the backend runs with built-ins
     * only. Say it in the app, once per set of missing commands.
     */
    private fun warnMissingExtensions(missing: List<String>) {
        val key = missing.joinToString(",")
        if (key == warnedMissingExtensions) return
        warnedMissingExtensions = key
        AppLog.event("vm", "missing extension commands: ${missing.joinToString(",")}")
        val text = str(
            "error.extensionsMissing",
            "pig 的扩展没加载：{commands} 不可用。在 Termux 里运行 pig update" +
                "（缺 Go 先 pkg install golang），然后重启 relay。",
        ).replace("{commands}", missing.joinToString(" ") { "/$it" })
        ui = ui.copy(error = text, toast = text)
    }

    /**
     * `auth failed: bad token` means the relay listening on our port was
     * started with another secret (hand-run relay, or one left over from
     * before a reinstall). Try the copy on disk first; if that is already what
     * we sent, replace the relay process with one using our token.
     */
    private fun recoverFromBadToken() {
        val now = System.currentTimeMillis()
        if (now - lastTokenRecoveryAt < 10_000) return
        lastTokenRecoveryAt = now
        val before = token
        syncTokenFromPrefs()
        if (token != before) {
            ui = ui.copy(error = null, setup = false, setupHint = null)
            client.close()
            client.connect()
            return
        }
        val ctx = getApplication<Application>()
        val error = RelayLauncher.restart(ctx, port, token, cwd)
        if (error != null) {
            ui = ui.copy(
                error = str("error.relayBadToken", "relay token 不一致，且无法重启 relay：{error}")
                    .replace("{error}", error),
                setup = true,
                setupHint = error,
            )
            return
        }
        ui = ui.copy(error = null, setup = false, setupHint = null)
        client.close()
        client.connect()
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
        liveThinking.setLength(0)
        liveThinkingText = ""
        liveThinkingDirty = false
    }

    /** Debug entry point: drop session notifications without foregrounding. */
    fun clearNotifications() {
        notifications.clear()
    }

    fun setNotifySound(choice: String) {
        notifications.setSoundChoice(choice)
        ui = ui.copy(notifySound = notifications.soundChoice())
    }

    /** The channel settings page: custom sounds, vibration, importance. */
    fun openNotificationSettings() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, notifications.alertsChannelId())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun setNotifyPref(name: String, enabled: Boolean) {
        prefs.edit().putBoolean(name, enabled).apply()
        ui = when (name) {
            "notifyDone" -> ui.copy(notifyDone = enabled)
            "notifyAttention" -> ui.copy(notifyAttention = enabled)
            "notifyError" -> ui.copy(notifyError = enabled)
            "notifyCompacted" -> ui.copy(notifyCompacted = enabled)
            else -> ui
        }
    }

    private fun sessionLabel(): String =
        ui.sessions.firstOrNull { it.path == ui.sessionFile }?.name?.takeIf { it.isNotBlank() }
            ?: str("notification.session", "Pig 会话")

    /** First line of [text], clipped: a notification is not a transcript. */
    private fun preview(text: String, max: Int = 160): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length > max) line.take(max - 1) + "…" else line
    }

    private fun notifySession(kind: SessionNotifications.Kind, title: String, text: String) {
        if (foreground || text.isBlank()) return
        notifications.notify(kind, ui.sessionFile.ifBlank { ui.sessionId }, title, text)
    }

    private fun markVisited(path: String) {
        if (path.isBlank()) return
        val current = prefs.getStringSet("visitedSessions", emptySet()) ?: emptySet()
        if (path in current) return
        val next = current + path
        if (next.size <= 400 || ui.sessions.isEmpty()) {
            prefs.edit().putStringSet("visitedSessions", next).apply()
            return
        }
        // Keep only sessions the list still knows about (plus the current one);
        // otherwise the set grows for the lifetime of the install.
        val known = ui.sessions.mapTo(mutableSetOf()) { it.path }
        known.add(path)
        if (ui.sessionFile.isNotBlank()) known.add(ui.sessionFile)
        prefs.edit()
            .putStringSet("visitedSessions", next.filterTo(mutableSetOf()) { it in known })
            .apply()
    }

    fun reconnect() {
        // An explicit retry, so lift the "Termux refused RUN_COMMAND" latch: the
        // user may have just enabled allow-external-apps in Termux.
        runBlockedByTermux = false
        client.close()
        connect()
    }

    /**
     * Reinstall the relay that belongs to this APK and activate it. The
     * installer pins the app's own tag, so it upgrades an old relay (and keeps
     * a newer one thanks to the script's version check).
     */
    fun updateRelay() {
        if (ui.installRunning) {
            ui = ui.copy(toast = str("relay.updateRunning", "安装或更新已经在进行"))
            return
        }
        if (ui.busy || ui.status == "compacting") {
            ui = ui.copy(toast = str("relay.updateBusy", "本轮结束后再更新 relay"))
            return
        }
        val ctx = getApplication<Application>()
        relayUpdatePending = true
        if (!RelayLauncher.hasPermission(ctx)) {
            // No RUN_COMMAND: hand over the command. The new relay reports the
            // installer's status file on its own once it is running.
            ui = ui.copy(setup = true)
            copyInstallCommandAndOpen()
            return
        }
        AppLog.event(
            "vm",
            "relay update requested (app $appVersion, relay ${ui.relayVersion.ifBlank { "unknown" }})",
        )
        bootstrap()
    }

    /** Dotted numeric compare; null when either side is not a release version. */
    private fun compareVersions(left: String, right: String): Int? {
        fun parts(value: String): List<Int>? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() ||
                !Regex("""^\d+(\.\d+)*$""").matches(trimmed)
            ) {
                return null
            }
            return trimmed.split('.').map { it.toIntOrNull() ?: return null }
        }
        val a = parts(left) ?: return null
        val b = parts(right) ?: return null
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun bootstrap() {
        AppLog.event("vm", "install requested (bootstrap)")
        val error = RelayLauncher.bootstrap(getApplication(), cwd)
        runBlockedByTermux = false
        ui = ui.copy(
            setup = error == null,
            setupHint = error,
            installRunning = error == null,
            installStartedAt = if (error == null) System.currentTimeMillis() else ui.installStartedAt,
            toast = error ?: str("toast.setupStarted", "已在 Termux 里开始安装，完成前可以先看上面的进度"),
        )
    }

    /**
     * Fresh installs cannot use RUN_COMMAND at all, so the dialog hands the
     * install command to the user instead: copy here, paste in Termux. The
     * staged progress starts right away and ends when the relay answers.
     */
    fun copyInstallCommand() {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Pig install", RelayLauncher.installCommand(ctx)),
        )
        ui = ui.copy(
            installRunning = true,
            installStartedAt = System.currentTimeMillis(),
            setupHint = null,
            toast = str("toast.installCopied", "命令已复制：在 Termux 里粘贴并回车"),
        )
    }

    /** Copy the install one-liner, then bring Termux up so it is one paste away. */
    fun copyInstallCommandAndOpen() {
        copyInstallCommand()
        openTermux()
    }

    /**
     * Paste-in-the-terminal one-liner that only enables `allow-external-apps`.
     * Shown when Termux refused a RUN_COMMAND: until that switch is on nothing
     * else can run, and after it the app installs by itself with progress.
     */
    fun copyExternalAppsCommand() {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Pig termux", RelayLauncher.enableExternalAppsCommand()),
        )
        ui = ui.copy(toast = str("toast.externalAppsCopied", "命令已复制：在 Termux 里粘贴并回车"))
    }

    fun copyExternalAppsAndOpen() {
        copyExternalAppsCommand()
        openTermux()
    }

    /**
     * Termux's answer to a RUN_COMMAND. The only actionable failure on a fresh
     * install is `allow-external-apps=false`: Termux refuses before exec, so the
     * app used to keep retrying without ever telling the user why.
     */
    private fun handleRunCommandOutcome(outcome: RunCommandOutcome) {
        AppLog.event(
            "termux",
            "runcommand kind=${outcome.kind} exit=${outcome.exitCode} " +
                "err=${outcome.errmsg.take(300)} stderr=${outcome.stderr.take(300)}",
        )
        if (outcome.blockedByTermux) {
            runBlockedByTermux = true
            autoReconnectJob?.cancel()
            autoReconnectJob = null
            ui = ui.copy(
                connected = false,
                installRunning = false,
                termuxBlocked = true,
                setup = true,
                error = str(
                    "error.externalAppsBlocked",
                    "Termux 拦截了后台命令：需要先在 Termux 里执行一次安装命令",
                ),
                setupHint = str(
                    "setup.blockedHint",
                    "Termux 的 allow-external-apps 还没开启，后台命令会被拒绝。" +
                        "点「复制命令」→「打开 Termux」→ 粘贴回车；装好回到这里会自动连接。",
                ),
            )
            return
        }
        when (outcome.kind) {
            RunCommandReceiver.KIND_BOOTSTRAP -> {
                ui = ui.copy(termuxBlocked = false)
                if (outcome.failed) {
                    relayUpdatePending = false
                    ui = ui.copy(
                        installRunning = false,
                        setup = true,
                        setupHint = outcome.summary().ifBlank {
                            str("setup.installFailed", "安装命令失败，请到 Termux 里看输出")
                        },
                    )
                } else {
                    ui = ui.copy(
                        installRunning = false,
                        installStep = 0,
                        installTotal = 0,
                        toast = str("toast.installDone", "Termux 安装完成，正在连接…"),
                    )
                    if (relayUpdatePending) {
                        // The script already compiled the extensions with
                        // `--prepare`; replace the old relay process so the app
                        // talks to the matching version.
                        relayUpdatePending = false
                        restartRelay()
                    } else {
                        reconnect()
                    }
                }
            }
            RunCommandReceiver.KIND_RELAY, RunCommandReceiver.KIND_RESTART -> {
                // Pig ran, so the Termux switch is on (possibly just enabled).
                ui = ui.copy(termuxBlocked = false)
                // While the installer is still running the binary legitimately
                // does not exist yet: keep the progress bar, not an error.
                if (outcome.missingBinary && !ui.installRunning) {
                    ui = ui.copy(
                        installRunning = false,
                        setup = true,
                        setupHint = str(
                            "setup.relayMissing",
                            "Termux 里还没有 pig-relay：先跑一次安装命令。",
                        ),
                    )
                }
                // Any other failure here is expected while the install is still
                // running; the dialog already explains what to do.
            }
            RunCommandReceiver.KIND_UNINSTALL -> {
                ui = ui.copy(
                    maintenanceBusy = false,
                    maintenanceSent = 0,
                    maintenanceTotal = 0,
                )
                if (outcome.failed) {
                    ui = ui.copy(
                        toast = str("backup.uninstallFailed", "卸载失败：{error}")
                            .replace(
                                "{error}",
                                outcome.summary().ifBlank { "unknown error" },
                            ),
                    )
                    // The binaries are still there: let the normal reconnect
                    // path bring the relay back.
                    reconnect()
                } else {
                    val backup = outcome.stdout.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("backup=") }
                        ?.removePrefix("backup=")
                        ?.takeIf { it.isNotBlank() }
                    AppLog.event(
                        "termux",
                        "uninstall done purge=$uninstallPurge backup=${backup ?: "none"}",
                    )
                    val deleted = when {
                        !uninstallPurge -> ""
                        backup != null -> "，~/.pig 已删除（备份：${backup.substringAfterLast('/')}）"
                        else -> "，并删除了 ~/.pig 数据"
                    }
                    ui = ui.copy(
                        connected = false,
                        setup = true,
                        setupHint = str(
                            "setup.uninstalled",
                            "已从 Termux 卸载 pig 与 pig-relay{data}。" +
                                "要重新安装：点「复制命令」或「一键安装」。",
                        ).replace("{data}", deleted),
                        toast = if (backup != null) {
                            str("toast.uninstalledBackup", "已卸载；数据备份在 {name}")
                                .replace("{name}", backup.substringAfterLast('/'))
                        } else {
                            str("toast.uninstalled", "已卸载 pig 与 pig-relay")
                        },
                    )
                }
            }
        }
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

    /**
     * Pack used on a fresh install: the bundled theme, so a new APK does not
     * look plain before Termux pushes anything. It steps aside for the first
     * relay push (see [packPinnedByUser]).
     */
    private val defaultPackId get() = "${BUNDLED_PREFIX}compact"

    private fun initialPackId(): String {
        prefs.getString("activePackId", null)?.let { return it }
        val bundled = bundledPacks().any { it.id == defaultPackId }
        return if (bundled) defaultPackId else ""
    }

    private fun loadPackFiles(id: String): Triple<Map<String, String>, Map<String, String>, Map<String, String>>? =
        if (id.startsWith(BUNDLED_PREFIX)) {
            runCatching {
                readPackAssets("${BUNDLED_PACK_ROOT}/${id.removePrefix(BUNDLED_PREFIX)}")
            }.getOrNull()
        } else {
            runCatching { readPackDir(File(packsDir, id)) }.getOrNull()
        }

    /** The packs compiled into the APK, read straight out of `assets/`. */
    private fun bundledPacks(): List<LocalPackInfo> {
        val manager = getApplication<Application>().assets
        val ids = runCatching {
            manager.list(BUNDLED_PACK_ROOT)?.filter { id ->
                !id.startsWith(".") &&
                    runCatching { manager.open("${BUNDLED_PACK_ROOT}/$id/manifest.json").close() }.isSuccess
            } ?: emptyList()
        }.getOrDefault(emptyList())
        return ids.mapNotNull { id ->
            val manifest = runCatching {
                manager.open("${BUNDLED_PACK_ROOT}/$id/manifest.json").use { input ->
                    JSONObject(input.readBytes().toString(Charsets.UTF_8))
                }
            }.getOrNull() ?: return@mapNotNull null
            LocalPackInfo(
                id = "$BUNDLED_PREFIX$id",
                name = manifest.optString("name").ifBlank { id },
                version = manifest.optInt("version", 1),
                files = countAssetFiles("${BUNDLED_PACK_ROOT}/$id"),
                bundled = true,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun countAssetFiles(path: String): Int {
        val children = runCatching { getApplication<Application>().assets.list(path) }
            .getOrNull().orEmpty()
        if (children.isEmpty()) return 1
        return children.sumOf { countAssetFiles("$path/$it") }
    }

    /** Rescan bundled + `files/ui-packs/` and refresh the manager list. */
    fun refreshLocalPacks() {
        val bundled = bundledPacks()
        val dir = packsDir
        val imported = if (!dir.isDirectory) {
            emptyList()
        } else {
            dir.listFiles()
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
        }
        val packs = bundled + imported
        ui = ui.copy(localPacks = packs)
        val active = prefs.getString("activePackId", "").orEmpty()
        if (active.isNotBlank() && packs.none { it.id == active }) {
            selectPack("")
        }
    }

    /** Switch the active pack: `""` = relay pack, a bundled id, or an imported pack id. */
    fun selectPack(id: String) {
        if (id == ui.activePackId) {
            // Tapping the already-active pack is how the user pins the
            // first-launch default against later relay pushes.
            if (id.isNotBlank() && !packPinnedByUser) {
                packPinnedByUser = true
                prefs.edit().putBoolean("packPinned", true).apply()
            }
            return
        }
        packPinnedByUser = id.isNotBlank()
        prefs.edit()
            .putString("activePackId", id)
            .putBoolean("packPinned", packPinnedByUser)
            .apply()
        if (id.isBlank()) {
            ui = ui.copy(activePackId = "")
            if (relayFiles.isNotEmpty()) {
                applyPack(relayFiles, relayAssets, relayHashes, relayHash, announce = true)
            }
            return
        }
        val files = loadPackFiles(id)
        if (files == null || files.first.isEmpty()) {
            ui = ui.copy(toast = str("toast.packMissing", "皮肤包不存在：{id}").replace("{id}", id))
            refreshLocalPacks()
            return
        }
        ui = ui.copy(activePackId = id)
        applyPack(files.first, files.second, files.third, "local:$id", announce = true)
    }

    /** Delete an imported pack; if it is active, switch back to the relay pack first. */
    fun deletePack(id: String) {
        if (id.startsWith(BUNDLED_PREFIX)) {
            // Bundled packs are part of the APK; only the selection can change.
            ui = ui.copy(toast = str("pack.bundledReadOnly", "内置 pack 不能删除"))
            return
        }
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

    /** Same shape as [readPackDir], but for a pack compiled into `assets/`. */
    private fun readPackAssets(
        path: String,
    ): Triple<Map<String, String>, Map<String, String>, Map<String, String>> {
        val files = mutableMapOf<String, String>()
        val assets = mutableMapOf<String, String>()
        val hashes = mutableMapOf<String, String>()
        val manager = getApplication<Application>().assets

        fun walk(rel: String) {
            val dirPath = if (rel.isEmpty()) path else "$path/$rel"
            val children = runCatching { manager.list(dirPath) }.getOrNull()
            if (children != null && children.isNotEmpty()) {
                children.sorted().forEach { walk(if (rel.isEmpty()) it else "$rel/$it") }
                return
            }
            if (rel.isEmpty() || rel.split('/').any { it.startsWith(".") }) return
            val bytes = runCatching { manager.open("$path/$rel").use { it.readBytes() } }
                .getOrNull() ?: return
            if (bytes.size > 512 * 1024) return
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

        walk("")
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
                    // Levels are model metadata: show what this model accepts
                    // instead of a hard-coded list that drifts (max/minimal).
                    val levels = ui.thinkingLevels.joinToString("|")
                        .ifBlank { "off|low|medium|high|max" }
                    ui = ui.copy(
                        toast = str("toast.thinkingStatus", "思考档位：{current} · 可选 {levels}")
                            .replace("{current}", ui.thinkingLevel.ifBlank { "—" })
                            .replace("{levels}", levels),
                    )
                } else {
                    append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
                    setThinkingLevel(args.trim().lowercase())
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
     * Read picked images, downscale them and keep them for the next prompt.
     * Runs the decode off the main thread; a 12 MP photo is tens of MB of ARGB.
     */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size + ui.attachments.size > MAX_IMAGES_PER_MESSAGE) {
            ui = ui.copy(
                toast = str("toast.imageLimit", "每条消息最多 {n} 张图片")
                    .replace("{n}", MAX_IMAGES_PER_MESSAGE.toString()),
            )
        }
        val accepted = uris.take((MAX_IMAGES_PER_MESSAGE - ui.attachments.size).coerceAtLeast(0))
        if (accepted.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                accepted.mapNotNull { uri -> runCatching { encodeAttachment(uri) }.getOrNull() }
            }
            if (added.isEmpty()) {
                ui = ui.copy(toast = str("toast.imageUnreadable", "无法读取这张图片"))
                return@launch
            }
            ui = ui.copy(attachments = (ui.attachments + added).take(MAX_IMAGES_PER_MESSAGE))
            val current = models.firstOrNull { "${it.provider}/${it.id}" == ui.model }
            if (current != null && !current.images) {
                ui = ui.copy(
                    toast = str(
                        "toast.modelNoImages",
                        "当前模型不支持图片输入，pig 会忽略这张图",
                    ),
                )
            }
        }
    }

    fun removeAttachment(id: String) {
        ui = ui.copy(attachments = ui.attachments.filterNot { it.id == id })
    }

    fun clearAttachments() {
        ui = ui.copy(attachments = emptyList())
    }

    /**
     * Save a picture the viewer is showing. Q+ goes through MediaStore, which
     * needs no storage permission and makes it appear in the gallery under
     * Pictures/Pig. Older releases fall back to the app's external Pictures
     * directory (no WRITE_EXTERNAL_STORAGE prompt).
     */
    fun saveChatImage(image: ImageBitmap, mimeType: String) {
        viewModelScope.launch {
            val location = withContext(Dispatchers.IO) {
                runCatching { writeImage(image, mimeType) }.getOrNull()
            }
            ui = ui.copy(
                toast = if (location != null) {
                    str("toast.imageSaved", "已保存到 {path}").replace("{path}", location)
                } else {
                    str("toast.imageSaveFailed", "图片保存失败")
                },
            )
        }
    }

    private fun writeImage(image: ImageBitmap, mimeType: String): String {
        val bitmap = image.asAndroidBitmap()
        val jpeg = mimeType.contains("jpeg", true) || mimeType.contains("jpg", true)
        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val name = "pig-" + System.currentTimeMillis() + if (jpeg) ".jpg" else ".png"
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Pig",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(format, 95, out)
                } ?: error("MediaStore open failed")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return "Pictures/Pig/$name"
        }
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Pig")
        dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { out -> bitmap.compress(format, 95, out) }
        return file.absolutePath
    }

    /** Downscale + recompress a picked image so the prompt and session stay small. */
    private fun encodeAttachment(uri: Uri): PendingImage? {
        val bytes = try {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w("PigImages", "read $uri: ${e.message}")
            null
        } ?: return null
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= UPLOAD_IMAGE_MAX_DIM) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > UPLOAD_IMAGE_MAX_DIM) {
            val scale = UPLOAD_IMAGE_MAX_DIM.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val alpha = scaled.hasAlpha()
        var quality = 86
        var encoded = compressImage(scaled, alpha, quality)
        while (!alpha && encoded.size > UPLOAD_IMAGE_MAX_BYTES && quality > 50) {
            quality -= 12
            encoded = compressImage(scaled, alpha, quality)
        }
        val mime = if (alpha) "image/png" else "image/jpeg"
        return PendingImage(
            id = "img${nextId++}",
            mimeType = mime,
            base64 = Base64.encodeToString(encoded, Base64.NO_WRAP),
            bitmap = scaled.asImageBitmap(),
        )
    }

    private fun compressImage(bitmap: Bitmap, png: Boolean, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * steer=true injects the message into the running turn (PiG `steer`);
     * steer=false queues it after the turn (`followUp`).
     *
     * Picked images travel as RPC `images` blocks; PiG accepts them on
     * `prompt`, `steer` and `follow_up` alike.
     */
    fun sendPrompt(raw: String, steer: Boolean = true) {
        val text = raw.trim()
        AppLog.event("prompt", text.take(400))
        val attachments = ui.attachments
        if (text.isEmpty() && attachments.isEmpty()) return
        if (text.startsWith("/")) {
            runSlashCommand(text)
            return
        }
        // A fresh backend has no providers/models. Do not turn that into a red
        // "pig error": point at the setup banner instead.
        if (ui.modelsLoaded && ui.availableModelCount == 0 && ui.model.isBlank()) {
            ui = ui.copy(
                providerSetupDismissed = false,
                toast = str(
                    "setup.noModelsToast",
                    "还没有可用模型：先添加供应商或导入备份",
                ),
            )
            return
        }
        // First prompt in a fresh app process with no session yet: honour the
        // pinned default model before the turn starts. A restored transcript
        // keeps the model its session was using.
        if (ui.sessionId.isBlank() && !ui.busy && ui.defaultModel.isNotBlank() &&
            savedSession() == null
        ) {
            applyDefaultModel()
        }
        val wasBusy = ui.busy
        append(
            ChatMsg(
                id = "u${nextId++}",
                role = Role.USER,
                text = text,
                images = attachments.map { ChatImage(it.bitmap, it.mimeType) },
            ),
        )
        currentAssistant = null
        markVisited(ui.sessionFile)
        ui = ui.copy(
            readOnly = false,
            pendingMessages = if (wasBusy) ui.pendingMessages + 1 else 0,
            // Shown immediately; pig's queue_update replaces it with the
            // authoritative lists (and knows about messages it consumed).
            queuedMessages = if (wasBusy) {
                ui.queuedMessages + QueuedMessage(if (steer) "steer" else "followUp", text)
            } else {
                // Nothing streams, so nothing can still be waiting.
                emptyList()
            },
            attachments = emptyList(),
        )
        val command = JSONObject()
            .put("id", "p${nextId++}")
            .put("type", "prompt")
            .put("message", text)
        if (attachments.isNotEmpty()) {
            val array = JSONArray()
            attachments.forEach { image ->
                array.put(
                    JSONObject()
                        .put("type", "image")
                        .put("data", image.base64)
                        .put("mimeType", image.mimeType),
                )
            }
            command.put("images", array)
        }
        if (wasBusy) {
            command.put("streamingBehavior", if (steer) "steer" else "followUp")
        }
        if (awaitingNewSession) {
            // The session is still being created. Hold the prompt locally so it
            // cannot be processed against the old session (or dropped by a
            // relay that is not prewarmed yet); get_state flushes it in order.
            pendingNewSessionPrompts += command
        } else {
            client.send(command)
        }
        if (wasBusy) requestState()
    }

    fun abort() = client.send(JSONObject().put("type", "abort"))

    /**
     * Remove one queued message. pig only offers `clear_queue` (everything), so
     * the survivors are re-sent in order with `steer` / `follow_up`, which queue
     * them again without echoing them into the transcript.
     */
    fun removeQueued(target: QueuedMessage) {
        val rest = ui.queuedMessages.toMutableList()
        if (!rest.remove(target)) return
        rewriteQueue(rest)
        ui = ui.copy(queuedMessages = rest, pendingMessages = rest.size)
    }

    /** Drop every queued steering and follow-up message. */
    fun clearQueued() {
        client.send(JSONObject().put("id", "q${nextId++}").put("type", "clear_queue"))
        ui = ui.copy(queuedMessages = emptyList(), pendingMessages = 0)
    }

    private fun rewriteQueue(messages: List<QueuedMessage>) {
        client.send(JSONObject().put("id", "q${nextId++}").put("type", "clear_queue"))
        messages.forEach { message ->
            client.send(
                JSONObject()
                    .put("id", "q${nextId++}")
                    .put("type", if (message.isSteer) "steer" else "follow_up")
                    .put("message", message.text),
            )
        }
    }

    fun openModelPicker() {
        showModelPicker = true
        requestModels()
    }

    fun closeModelPicker() {
        showModelPicker = false
    }

    // ---- Native provider manager ----------------------------------------

    /** Opened by the UI pack's nav entry (`window: native:providers`). */
    fun openProviderManager() {
        ui = ui.copy(
            providerManagerOpen = true,
            providerMessage = "",
            providerMessageError = false,
        )
        requestProviders()
    }

    /** Hide the "no models yet" banner until models disappear again. */
    fun dismissProviderSetup() {
        ui = ui.copy(providerSetupDismissed = true)
    }

    fun closeProviderManager() {
        ui = ui.copy(
            providerManagerOpen = false,
            providerBusy = false,
            providerMessage = "",
        )
    }

    fun requestProviders() {
        if (!client.connected.value) {
            ui = ui.copy(
                providersLoading = false,
                providerMessage = str("provider.offline", "未连接 relay，无法读取供应商"),
                providerMessageError = true,
            )
            return
        }
        ui = ui.copy(providersLoading = true)
        client.send(
            JSONObject().put("id", "providers-${nextId++}").put("type", "relay_providers"),
        )
    }

    /**
     * Fetches `{baseUrl}/models` without saving. The relay expands `$ENV` /
     * `!command` keys and sends the same headers the session would.
     */
    fun refreshProviderModels(draft: ProviderInfo, apiKey: String) {
        if (ui.providerBusy) return
        if (draft.baseUrl.isBlank()) {
            ui = ui.copy(
                providerMessage = str("provider.baseUrlRequired", "先填 Base URL"),
                providerMessageError = true,
            )
            return
        }
        val pid = draft.id.trim().lowercase()
        ui = ui.copy(
            providerBusy = true,
            providerModels = emptyList(),
            providerModelsFor = pid,
            providerModelsAt = 0,
            providerMessage = str("provider.fetching", "正在从 {url} 获取模型列表…")
                .replace("{url}", draft.baseUrl),
            providerMessageError = false,
        )
        client.send(
            JSONObject()
                .put("id", "provmodels-${nextId++}")
                .put("type", "relay_provider_models")
                .put(
                    "entry",
                    providerEntry(
                        draft = draft.copy(id = pid),
                        apiKey = apiKey,
                        clearKey = false,
                        all = draft.models,
                        selected = draft.models,
                        includeCache = false,
                    ),
                ),
        )
    }

    /**
     * Writes the provider and (for auto mode) caches [allModels]. [selected] is
     * the subset the user ticked; an empty selection is rejected for manual
     * mode before we touch the file.
     */
    fun saveProvider(
        draft: ProviderInfo,
        apiKey: String,
        clearKey: Boolean,
        allModels: List<ProviderModel>,
        selected: List<ProviderModel>,
    ) {
        if (ui.providerBusy) return
        val id = draft.id.trim().lowercase()
        if (id.isBlank() || draft.baseUrl.isBlank()) {
            ui = ui.copy(
                providerMessage = str("provider.idRequired", "ID 与 Base URL 必填"),
                providerMessageError = true,
            )
            return
        }
        if (!draft.auto && selected.isEmpty()) {
            ui = ui.copy(
                providerMessage = str("provider.selectRequired", "手动模式下至少选择一个模型"),
                providerMessageError = true,
            )
            return
        }
        ui = ui.copy(
            providerBusy = true,
            providerMessage = str("provider.saving", "正在保存…"),
            providerMessageError = false,
        )
        client.send(
            JSONObject()
                .put("id", "provsave-${nextId++}")
                .put("type", "relay_provider_save")
                .put(
                    "entry",
                    providerEntry(
                        draft = draft.copy(id = id),
                        apiKey = apiKey,
                        clearKey = clearKey,
                        all = allModels,
                        selected = selected,
                        includeCache = true,
                    ),
                ),
        )
    }

    fun deleteProvider(id: String) {
        if (ui.providerBusy || id.isBlank()) return
        ui = ui.copy(
            providerBusy = true,
            providerMessage = str("provider.deleting", "正在删除 {id}…").replace("{id}", id),
            providerMessageError = false,
        )
        client.send(
            JSONObject()
                .put("id", "provdel-${nextId++}")
                .put("type", "relay_provider_delete")
                .put("entry", JSONObject().put("id", id)),
        )
    }

    private fun providerEntry(
        draft: ProviderInfo,
        apiKey: String,
        clearKey: Boolean,
        all: List<ProviderModel>,
        selected: List<ProviderModel>,
        includeCache: Boolean,
    ): JSONObject {
        val entry = JSONObject()
            .put("id", draft.id.trim().lowercase())
            .put("name", draft.name.trim())
            .put("baseUrl", draft.baseUrl.trim())
            .put("api", draft.api.ifBlank { "openai-completions" })
            .put("modelsUrl", draft.modelsUrl.trim())
            .put("auto", draft.auto)
            .put("models", providerModelsJson(if (draft.auto) all else selected))
        if (apiKey.isNotBlank()) entry.put("apiKey", apiKey)
        if (clearKey) entry.put("clearKey", true)
        if (includeCache) entry.put("cacheModels", providerModelsJson(all))
        return entry
    }

    private fun providerModelsJson(models: List<ProviderModel>): JSONArray {
        val array = JSONArray()
        models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("name", model.name.ifBlank { model.id })
                    .put("contextWindow", model.contextWindow)
                    .put("maxTokens", model.maxTokens),
            )
        }
        return array
    }

    private fun parseProviderModels(array: JSONArray?): List<ProviderModel> {
        if (array == null) return emptyList()
        val out = mutableListOf<ProviderModel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("name") }
            if (id.isBlank()) continue
            out += ProviderModel(
                id = id,
                name = item.optString("name").ifBlank { id },
                contextWindow = item.optInt("contextWindow"),
                maxTokens = item.optInt("maxTokens"),
            )
        }
        return out
    }

    private fun parseProvider(json: JSONObject): ProviderInfo = ProviderInfo(
        id = json.optString("id"),
        name = json.optString("name").ifBlank { json.optString("id") },
        baseUrl = json.optString("baseUrl"),
        api = json.optString("api").ifBlank { "openai-completions" },
        hasKey = json.optBoolean("hasKey"),
        auto = json.optBoolean("auto", true),
        modelsUrl = json.optString("modelsUrl"),
        models = parseProviderModels(json.optJSONArray("models")),
        cachedModels = parseProviderModels(json.optJSONArray("cachedModels")),
        fetchedAt = json.optLong("fetchedAt"),
    )

    fun requestModels() {
        modelsLoading = true
        client.send(JSONObject().put("id", "models-${nextId++}").put("type", "get_available_models"))
        client.send(
            JSONObject().put("id", "levels-${nextId++}").put("type", "get_available_thinking_levels"),
        )
    }

    fun selectModel(model: ModelInfo) {
        AppLog.event("model", "switch to ${model.provider}/${model.id}")
        modelSwitchAccepted = false
        // Restore the level this model was last used with. The set_model reply
        // sends it after the switch is confirmed, so it cannot be applied to
        // the previous model by mistake.
        pendingThinkingRestore = thinkingByModel["${model.provider}/${model.id}"]
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

    /**
     * Mark a model as the one every new session starts with. Tapping the star
     * on the current default clears it; the running session is left alone.
     */
    fun toggleDefaultModel(model: ModelInfo) {
        val key = "${model.provider}/${model.id}"
        val next = if (ui.defaultModel == key) "" else key
        setDefaultModelKey(next)
    }

    /** Clear the pinned default without touching the running session. */
    fun clearDefaultModel() = setDefaultModelKey("")

    private fun setDefaultModelKey(key: String) {
        ui = ui.copy(
            defaultModel = key,
            toast = if (key.isBlank()) {
                str("toast.defaultModelCleared", "已取消默认模型")
            } else {
                str("toast.defaultModelSet", "默认模型：{model}（新会话使用）")
                    .replace("{model}", key)
            },
        )
        prefs.edit().putString("defaultModel", key).apply()
    }

    /** Applied right after new_session so the fresh session uses the pinned model. */
    private fun applyDefaultModel() {
        val key = ui.defaultModel.trim()
        if (key.isBlank()) return
        val provider = key.substringBefore('/')
        val id = key.substringAfter('/', "")
        if (provider.isBlank() || id.isBlank()) return
        selectModelById(provider, id)
    }

    fun setSettingsSize(size: String) {
        if (size == ui.settingsSize) return
        prefs.edit().putString("settingsSize", size).apply()
        ui = ui.copy(settingsSize = size)
    }

    fun setThinkingLevel(level: String) {
        if (level.isBlank()) return
        val key = ui.model
        if (key.isNotBlank() && key != "/") {
            thinkingByModel = thinkingByModel + (key to level)
            prefs.edit()
                .putString("thinkingByModel", JSONObject(thinkingByModel as Map<*, *>).toString())
                .apply()
        }
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

    /** Show/hide reasoning blocks. Display-only, so no "new session" toast. */
    fun setHideThinkingBlock(hide: Boolean) {
        ui = ui.copy(hideThinkingBlock = hide)
        client.send(
            JSONObject()
                .put("id", "settings-set-${nextId++}")
                .put("type", "relay_settings")
                .put("set", JSONObject().put("hideThinkingBlock", hide)),
        )
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
            hideThinkingBlock = (patch.toMap()["hideThinkingBlock"] as? Boolean)
                ?: ui.hideThinkingBlock,
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
        // A running turn or a running compaction both write the session file:
        // wait for it to settle instead of pulling the process out from under it.
        if (ui.busy || ui.status == "compacting") {
            ui = ui.copy(pendingAgentRestart = true)
            return
        }
        restartAgent()
    }

    /**
     * Restart pig on demand. Installing or updating an extension only changes
     * settings.json; the running agent keeps its old set (`/reload` reuses the
     * startup inputs), so a restart is what makes new commands appear.
     */
    fun restartAgentNow() {
        if (ui.busy || ui.status == "compacting") {
            ui = ui.copy(
                pendingAgentRestart = true,
                toast = str("toast.restartQueued", "本轮结束后重启 pig"),
            )
            return
        }
        restartAgent()
    }

    private fun restartAgent() {
        if (restartInFlight) return
        if (!client.connected.value) {
            // Nothing to restart; the pin is still written to models.json and
            // will be picked up by the next pig that starts.
            ui = ui.copy(
                pendingAgentRestart = false,
                toast = str("toast.restartOffline", "relay 未连接，上下文长度在下次连接后生效"),
            )
            return
        }
        restartInFlight = true
        ui = ui.copy(status = "restoring", pendingAgentRestart = false)
        client.send(
            JSONObject()
                .put("id", "restart-${nextId++}")
                .put("type", "relay_restart"),
        )
        // A relay that dies between the request and the reply must not wedge the
        // flag forever: without this every later pin would stay display-only.
        viewModelScope.launch {
            delay(8000)
            if (restartInFlight) {
                restartInFlight = false
                if (ui.status == "restoring") ui = ui.copy(status = "")
            }
        }
    }

    /** Public refresh for the statistics dialog. */
    fun refreshStats() = requestStats()

    private fun requestStats() {
        client.send(JSONObject().put("id", "stats-${nextId++}").put("type", "get_session_stats"))
    }

    /** Load `provider/model -> thinking level` choices made in the picker. */
    private fun loadThinkingLevels(): Map<String, String> {
        val raw = prefs.getString("thinkingByModel", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            val level = json.optString(key, "")
            if (key.isNotBlank() && level.isNotBlank()) out[key] = level
        }
        return out
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
        val sent = client.connected.value
        if (sent) {
            client.send(
                JSONObject()
                    .put("id", "model-override-${nextId++}")
                    .put("type", "relay_model_override")
                    .put("provider", provider)
                    .put("model", id)
                    .put("contextWindow", tokens.coerceAtLeast(0)),
            )
        }
        ui = ui.copy(
            toast = when {
                !sent -> str(
                    "toast.contextWindowOffline",
                    "relay 未连接，上下文长度只在本机生效",
                )
                tokens > 0 -> str("toast.contextWindowSet", "上下文长度已设为 {tokens}")
                    .replace("{tokens}", formatTokenCount(tokens))
                else -> str("toast.contextWindowReset", "已恢复自动检测的上下文长度")
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
    fun refreshCommands(force: Boolean = false) {        val now = System.currentTimeMillis()
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
        ui = ui.copy(historyLoading = true)
        client.send(
            JSONObject()
                .put("id", id)
                .put("type", "get_messages")
                // The relay answers with the newest `limit` messages plus
                // historyStart/historyTotal, so a 16 MB transcript arrives as
                // the one or two screens the reader actually needs.
                .put("limit", historyWindow),
        )
    }

    /** Pull the next older page; the transcript stays a suffix of the session. */
    fun loadOlderHistory() {
        if (ui.historyLoading || !ui.historyHasOlder) return
        if (historyWindow >= HISTORY_MAX) return
        historyWindow = (historyWindow * 2).coerceAtMost(HISTORY_MAX)
        ui = ui.copy(historyLoadingOlder = true)
        requestMessages()
    }

    /** Retry button on the "reading history" screen. */
    fun reloadHistory() {
        ui = ui.copy(historyLoading = false, historyLoadingOlder = false)
        requestMessages()
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
        // An explicit switch wins over a new session still being created; the
        // prompts typed into that session belonged to it, not to this one.
        awaitingNewSession = false
        pendingNewSessionPrompts.clear()
        drafts.remove(pendingDraftKey)
        saveDrafts(force = true)
        toolPartialRaw.clear()
        ui = ui.copy(
            status = "switching",
            // Another conversation: the composer loads that session's draft.
            draftEpoch = ui.draftEpoch + 1,
            extensionStatuses = emptyMap(),
            pendingAgentRestart = false,
            // Widgets belong to the agent of the session that wrote them.
            widgets = emptyList(),
            dismissedWidgets = emptyMap(),
            usage = SessionUsage(),
        )
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
        // A get_state reply that still reports this session describes the runtime
        // pig is replacing; the draft and the queued prompt must not follow it.
        newSessionFromFile = ui.sessionFile
        newSessionFromId = ui.sessionId
        newSessionAt = System.currentTimeMillis()
        // The new conversation starts empty. Text kept under the placeholder key
        // belongs to a conversation that never got a file, so it has no key of
        // its own to live under and would leak into this one.
        drafts.remove(pendingDraftKey)
        saveDrafts(force = true)
        currentAssistant = null
        toolPartialRaw.clear()
        clearLive()
        pendingToolPartials.clear()
        prefs.edit().remove("lastSessionFile").apply()
        desiredSessionPath = null
        restorePending = false
        needsHistory = false
        historyHealTried = false
        historyWindow = HISTORY_PAGE
        // Open the new conversation right away so the user can start typing;
        // pig creates the session in the background and the queued prompt runs
        // as soon as it is ready. The banner explains the wait.
        awaitingNewSession = true
        pendingNewSessionPrompts.clear()
        ui = ui.copy(
            readOnly = false,
            busy = false,
            streamingId = null,
            status = "starting",
            pendingMessages = 0,
            messages = emptyList(),
            historyHasOlder = false,
            historyLoading = false,
            historyLoadingOlder = false,
            sessionId = "",
            sessionFile = "",
            extensionStatuses = emptyMap(),
            pendingAgentRestart = false,
            widgets = emptyList(),
            dismissedWidgets = emptyMap(),
            contextPercent = null,
            contextTokens = 0,
            // Even when the storage key stays the placeholder (a conversation
            // that never got a file), the composer must start empty.
            draftEpoch = ui.draftEpoch + 1,
            contextWindowActual = 0,
            contextCompacted = false,
            cacheHitRate = null,
            usage = SessionUsage(),
            cacheReadTokens = 0,
            cacheInputTokens = 0,
        )
        client.send(JSONObject().put("type", "new_session"))
        // New sessions always start on the model pinned in the picker. This is
        // sent behind new_session, so pig/relay applies it to the fresh session.
        applyDefaultModel()
    }

    /** Composer key for the session that is being created but has no file yet. */
    private val pendingDraftKey = "\u0000pending"

    /** Key the composer should store its text under right now. It is never
     *  empty: a conversation without a session file yet keeps its text under the
     *  placeholder key, and [adoptPendingDraft] hands it over once pig reports
     *  the file. An empty key dropped whatever the user typed. */
    fun draftKey(): String = ui.sessionFile.ifBlank { pendingDraftKey }

    /** Move text typed into a conversation that had no session file onto the file
     *  it just gained. Called before `ui.sessionFile` changes, so the composer
     *  finds the text under the new key instead of resetting to empty. */
    private fun adoptPendingDraft(target: String) {
        if (target.isBlank() || target == pendingDraftKey) return
        val pending = drafts[pendingDraftKey] ?: return
        if (drafts[target].isNullOrBlank()) drafts[target] = pending
        drafts.remove(pendingDraftKey)
        saveDrafts(force = true)
    }

    /** True once a get_state reply describes the session `new_session` created.
     *  Until then the reply may still be the old runtime's, and adopting it moved
     *  the draft (and the queued prompt) onto the conversation the user left. */
    private fun newSessionSettled(sessionId: String, sessionFile: String): Boolean {
        if (System.currentTimeMillis() - newSessionAt > NEW_SESSION_SETTLE_MS) return true
        if (sessionId.isNotBlank() && newSessionFromId.isNotBlank() && sessionId != newSessionFromId) return true
        if (sessionFile.isNotBlank() && sessionFile != newSessionFromFile) return true
        // Nothing to confuse it with (the relay had no agent yet): a blank file is
        // the new session rather than the previous one.
        return newSessionFromFile.isBlank() && newSessionFromId.isBlank() && sessionFile.isBlank()
    }

    fun draftText(key: String): String = if (key.isEmpty()) "" else drafts[key].orEmpty()

    fun setDraft(key: String, text: String) {
        if (key.isEmpty()) return
        val current = drafts[key].orEmpty()
        if (current == text) return
        if (text.isBlank()) drafts.remove(key) else drafts[key] = text
        while (drafts.size > 30) {
            drafts.remove(drafts.keys.first())
        }
        saveDrafts(force = false)
    }

    private fun saveDrafts(force: Boolean) {
        if (!force && System.currentTimeMillis() - draftsFlushedAt < 1500) return
        draftsFlushedAt = System.currentTimeMillis()
        if (drafts.isEmpty()) {
            prefs.edit().remove("drafts").apply()
            return
        }
        prefs.edit().putString("drafts", JSONObject(drafts as Map<*, *>).toString()).apply()
    }

    /** Remember the cards the session that owns [file] shows. An empty list
     *  drops the entry, so a cleared card is not resurrected later. */
    private fun rememberWidgets(file: String = ui.sessionFile) {
        if (file.isBlank()) return
        val widgets = ui.widgets
        if (widgets.isEmpty()) {
            if (widgetSnapshots.remove(file) != null) saveWidgetSnapshots()
            return
        }
        val array = JSONArray()
        widgets.forEach { widget ->
            array.put(
                JSONObject()
                    .put("key", widget.key)
                    .put("lines", JSONArray(widget.lines))
                    .put("placement", widget.placement),
            )
        }
        widgetSnapshots.remove(file)
        widgetSnapshots[file] = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("widgets", array)
        while (widgetSnapshots.size > WIDGET_SNAPSHOT_SESSIONS) {
            widgetSnapshots.remove(widgetSnapshots.keys.first())
        }
        saveWidgetSnapshots()
    }

    private fun saveWidgetSnapshots() {
        if (widgetSnapshots.isEmpty()) {
            prefs.edit().remove("widgetSnapshots").apply()
            return
        }
        val root = JSONObject()
        widgetSnapshots.forEach { (file, entry) -> root.put(file, entry) }
        prefs.edit().putString("widgetSnapshots", root.toString()).apply()
    }

    private fun loadWidgetSnapshots(): Map<String, JSONObject> {
        val raw = prefs.getString("widgetSnapshots", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, JSONObject>()
        root.keys().forEach { file ->
            val entry = root.optJSONObject(file)
            if (file.isNotBlank() && entry != null) out[file] = entry
        }
        return out
    }

    /** Cards to draw for [file] until an extension pushes again. Live frames win
     *  over the snapshot; frames that arrived before the file was known belong to
     *  the conversation that is being adopted. */
    private fun widgetsForSession(file: String): List<AppWidget> {
        if (widgetsFile == file) return ui.widgets
        val snapshot = snapshotWidgets(file)
        if (ui.widgets.isEmpty()) {
            widgetsFile = file
            return snapshot
        }
        val merged = LinkedHashMap<String, AppWidget>()
        snapshot.forEach { merged[it.key] = it }
        ui.widgets.forEach { merged[it.key] = it }
        widgetsFile = file
        return merged.values.toList()
    }

    private fun snapshotWidgets(file: String): List<AppWidget> {
        val entry = widgetSnapshots[file] ?: return emptyList()
        val at = entry.optLong("at", 0L)
        if (at > 0 && System.currentTimeMillis() - at > WIDGET_SNAPSHOT_TTL_MS) return emptyList()
        val array = entry.optJSONArray("widgets") ?: return emptyList()
        val out = ArrayList<AppWidget>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("key", "")
            val lines = item.optJSONArray("lines")
            if (key.isBlank() || lines == null || lines.length() == 0) continue
            out.add(
                AppWidget(
                    key = key,
                    lines = (0 until lines.length()).map { lines.optString(it) },
                    placement = if (item.optString("placement", "aboveEditor") == "belowEditor") {
                        "belowEditor"
                    } else {
                        "aboveEditor"
                    },
                ),
            )
        }
        return out
    }

    private fun loadDrafts(): Map<String, String> {
        val raw = prefs.getString("drafts", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { key ->
            val text = json.optString(key, "")
            if (key.isNotBlank() && text.isNotBlank()) out[key] = text
        }
        return out
    }

    /** Send prompts that were typed while the new session was starting. */
    private fun flushPendingPrompts() {
        if (pendingNewSessionPrompts.isEmpty()) return
        val queued = pendingNewSessionPrompts.toList()
        pendingNewSessionPrompts.clear()
        queued.forEach { client.send(it) }
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

    /**
     * Hide a widget until the extension sends different content. There is no
     * reply channel for widgets, so this is local-only by design: an extension
     * cannot be told that its card was dismissed.
     */
    fun dismissWidget(key: String) {
        val widget = ui.widgets.firstOrNull { it.key == key } ?: return
        ui = ui.copy(
            widgets = ui.widgets.filterNot { it.key == key },
            dismissedWidgets = ui.dismissedWidgets + (key to widget.lines.joinToString("\n")),
        )
        rememberWidgets(widgetsFile.ifBlank { ui.sessionFile })
    }

    /** Collapse/expand a widget card. Stored in prefs so it survives a restart. */
    fun toggleWidgetCollapsed(key: String) {
        if (key.isBlank()) return
        val next = if (key in ui.collapsedWidgets) {
            ui.collapsedWidgets - key
        } else {
            ui.collapsedWidgets + key
        }
        ui = ui.copy(collapsedWidgets = next)
        prefs.edit().putStringSet("collapsedWidgets", next).apply()
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
                AppLog.event("action", "window $windowId -> $command")
                if (command.isEmpty()) {
                    ui = ui.copy(toast = str("toast.extMissingCommand", "ext 动作缺少命令名"))
                    return
                }
                // Sending an unregistered slash command makes pig treat it as a
                // chat message; say what is actually wrong instead. Until the
                // first get_commands reply the list is empty, and blocking on
                // that would make a working extension look missing.
                if (ui.commands.isNotEmpty() && !isCommandRegistered(command)) {
                    warnMissingExtensions(listOf(command.substringBefore(' ')))
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
        // `native:providers` is rendered by the app; the pack owns the entry
        // (label, icon, placement), not the UI. `providers` is the value older
        // provider-ui releases wrote, kept for installs that have not synced
        // their nav.json yet.
        if (item.window == "native:providers" || item.window == "providers") {
            openProviderManager()
            return
        }
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
            if (ui.commands.isNotEmpty() && !isCommandRegistered(item.command)) {
                warnMissingExtensions(listOf(item.command.substringBefore(' ')))
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

    /** Hide the dialog without answering: the request stays pending. */
    fun hidePending() {
        if (ui.pending != null) ui = ui.copy(pendingHidden = true)
    }

    fun showPending() {
        if (ui.pending != null) ui = ui.copy(pendingHidden = false)
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
        // Frames that arrive while a switch is in flight already belong to the
        // target session, whose file the next get_state reports.
        val owner = desiredSessionPath ?: ui.sessionFile
        val next = ui.widgets.filterNot { it.key == key }.toMutableList()
        val dismissed = ui.dismissedWidgets.toMutableMap()
        // An empty list means "remove": RPC mode reports a cleared widget as an
        // empty widgetLines array rather than omitting the field.
        if (lines != null && lines.length() > 0) {
            val items = (0 until lines.length()).map { lines.optString(it) }
            val content = items.joinToString("\n")
            // A dismissed card stays hidden until the extension says something
            // new; hiding it must not swallow the next update.
            if (dismissed[key] == content) {
                ui = ui.copy(widgets = next, dismissedWidgets = dismissed)
                widgetsFile = owner
                rememberWidgets(owner)
                return
            }
            dismissed.remove(key)
            next.add(AppWidget(key = key, lines = items, placement = placement))
        } else {
            dismissed.remove(key)
        }
        ui = ui.copy(widgets = next, dismissedWidgets = dismissed)
        widgetsFile = owner
        rememberWidgets(owner)
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
    /** The next relay_restarted toast should talk about providers, not ctx pins. */
    private var providerRestartPending = false

    /** Notices retired in this process; a history render must not bring them back. */
    private val resolvedNotices = mutableSetOf<String>()

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
    /** Extension errors repeat per event; show each distinct one once. */
    private val recentExtensionErrors = ArrayDeque<String>()

    private fun showExtensionError(text: String) {
        if (recentExtensionErrors.contains(text)) return
        recentExtensionErrors.addLast(text)
        while (recentExtensionErrors.size > 50) recentExtensionErrors.removeFirst()
        AppLog.event("extension", text)
        appendNotice(text)
        ui = ui.copy(toast = text.take(300))
    }

    private var lastRelayToastAt = 0L
    private var lastRelayToastText = ""

    /** A relay/pig line: always into the log, failures also onto the screen. */
    private fun handleRelayLog(source: String, level: String, message: String, replay: Boolean) {
        if (message.isBlank()) return
        AppLog.event(if (source == "pig") "pig" else "relay", message)
        if (replay || level != "error") return
        toastRelayError(source, message)
    }

    private fun toastRelayError(source: String, message: String) {
        if (message.isBlank()) return
        val now = System.currentTimeMillis()
        if (message == lastRelayToastText || now - lastRelayToastAt < 3_000) return
        lastRelayToastAt = now
        lastRelayToastText = message
        val prefix = if (source == "pig") "pig" else "relay"
        ui = ui.copy(toast = "$prefix: ${message.take(300)}")
    }

    private fun appendNotice(text: String) {
        if (text.isBlank()) return
        AppLog.event("notice", text)
        append(ChatMsg(id = "n${nextId++}", role = Role.NOTICE, text = text, isError = true))
        noticesCache = null
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
        // The stored text was produced with whatever pack was active back then,
        // so match on the stable part rather than the current wording.
        val markers = listOf(text, "上下文长度仅在本机生效").filter { it.isNotBlank() }
        fun stale(candidate: String) = candidate.isNotBlank() && markers.any { candidate.contains(it) }
        resolvedNotices.addAll(markers)
        noticesCache = null
        ui = ui.copy(messages = ui.messages.filterNot { it.role == Role.NOTICE && stale(it.text) })
        val key = noticeKey() ?: return
        val file = File(noticesDir, "$key.jsonl")
        if (!file.isFile) return
        runCatching {
            val kept = file.readLines().filterNot { line ->
                stale(runCatching { JSONObject(line).optString("text") }.getOrDefault(""))
            }
            if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    /** Per-session cache: a history render must not hit the disk. */
    private var noticesCache: Pair<String, List<ChatMsg>>? = null

    /** Notices written earlier for the current session, oldest first. */
    private fun readNotices(): List<ChatMsg> {
        val key = noticeKey() ?: return emptyList()
        noticesCache?.let { (cachedKey, cached) -> if (cachedKey == key) return cached }
        val file = File(noticesDir, "$key.jsonl")
        if (!file.isFile) return emptyList()
        val loaded = runCatching {
            file.useLines { lines ->
                lines.mapIndexedNotNull { index, line ->
                    val item = runCatching { JSONObject(line) }.getOrNull()
                        ?: return@mapIndexedNotNull null
                    val text = item.optString("text")
                    if (text.isBlank() || text in resolvedNotices) {
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
        noticesCache = key to loaded
        return loaded
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
                // A relay from before 1.1.34 has no backup frames; hide the
                // buttons instead of letting them fail with "unknown frame".
                var backupRemote = false
                var providerRemote = false
                var installStatusRemote = false
                event.optJSONArray("features")?.let { features ->
                    for (i in 0 until features.length()) {
                        when (features.optString(i)) {
                            "backup" -> backupRemote = true
                            "providers" -> providerRemote = true
                            "install" -> installStatusRemote = true
                        }
                    }
                }
                // A new relay sends `relay_install` before this hello. While
                // the installer is running the setup dialog has to stay open;
                // an old relay cannot report stages, so its hello closes it.
                val stillInstalling = installStatusRemote &&
                    event.optBoolean("installing", ui.installRunning)
                val relayVersion = event.optString("version").trim()
                val versionCompare = if (versionChecksEnabled) {
                    compareVersions(relayVersion, appVersion)
                } else {
                    null
                }
                // No `version` at all means a relay from before 1.1.27: it is
                // certainly older than this app. A `dev-...` relay is not
                // comparable and gets no nag.
                val relayOutdated = versionChecksEnabled &&
                    (versionCompare?.let { it < 0 } ?: relayVersion.isBlank())
                val relayAhead = versionCompare != null && versionCompare > 0
                ui = ui.copy(
                    error = null,
                    setup = stillInstalling,
                    setupHint = if (stillInstalling) ui.setupHint else null,
                    installRunning = stillInstalling,
                    termuxBlocked = false,
                    backupRemote = backupRemote,
                    providerRemote = providerRemote,
                    installStatusRemote = installStatusRemote,
                    relayVersion = relayVersion,
                    relayOutdated = relayOutdated,
                    relayAhead = relayAhead,
                    modelsLoaded = false,
                )
                when {
                    relayOutdated && lastRelayWarnedVersion != relayVersion -> {
                        lastRelayWarnedVersion = relayVersion
                        ui = ui.copy(
                            toast = str(
                                "relay.outdatedToast",
                                "relay {relay} 比 App {app} 旧：到设置 → 连接点「更新 relay」",
                            )
                                .replace(
                                    "{relay}",
                                    relayVersion.ifBlank {
                                        str("relay.oldUnknown", "（旧版）")
                                    },
                                )
                                .replace("{app}", appVersion),
                        )
                    }
                    relayAhead && lastRelayWarnedVersion != relayVersion -> {
                        lastRelayWarnedVersion = relayVersion
                        ui = ui.copy(
                            toast = str(
                                "relay.aheadToast",
                                "relay {relay} 比 App {app} 新：建议更新 App（不会降级 relay）",
                            ).replace("{relay}", relayVersion)
                                .replace("{app}", appVersion),
                        )
                    }
                    !relayOutdated && !relayAhead -> {
                        // Matched (or a dev build): the update is settled.
                        lastRelayWarnedVersion = null
                        relayUpdatePending = false
                    }
                }
                warnedMissingExtensions = ""
                warnedIncompleteRelayPack = false
                AppLog.event(
                    "vm",
                    "relay hello: protocol=${event.optString("protocol")} " +
                        "version=${event.optString("version")} " +
                        "cwd=${event.optString("cwd")} ui=${event.optString("uiDir")} " +
                        "pigPid=${event.optInt("pigPid")} " +
                        "backup=$backupRemote install=$installStatusRemote",
                )
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
                // A reconnect settles whatever was pending; the new-session
                // hint must not outlive the connection that showed it. Prompts
                // typed into that session are delivered rather than lost.
                awaitingNewSession = false
                flushPendingPrompts()
                clearLive()
                ui = ui.copy(
                    streamingId = null,
                    pendingMessages = 0,
                    // Nothing on screen yet: say we are reading it rather than
                    // showing the "no messages" empty state.
                    historyLoading = ui.messages.isEmpty(),
                )
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
                ui = ui.copy(
                    error = message,
                    setup = true,
                    setupHint = message,
                    historyLoading = false,
                    historyLoadingOlder = false,
                )
            }
            "relay_error" -> {
                val message = event.optString("message", "relay error")
                // A bad token is recovered silently below; don't notify.
                if (ui.notifyError && !message.startsWith("unknown relay frame") &&
                    !message.contains("bad token")
                ) {
                    notifySession(
                        SessionNotifications.Kind.ERROR,
                        str("notification.error", "出错了") + " · " + sessionLabel(),
                        preview(message),
                    )
                }
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
                } else if (message.contains("relay_provider")) {
                    // Older relay: the native manager cannot read or write the
                    // provider file; keep the hint visible in the manager.
                    ui = ui.copy(
                        providerRemote = false,
                        providerBusy = false,
                        providerMessage = str(
                            "provider.needRelay",
                            "需要新版 relay 才能管理供应商：重装 Termux 组件后重启 relay",
                        ),
                        providerMessageError = true,
                    )
                } else if (message.contains("bad token")) {
                    recoverFromBadToken()
                } else {
                    ui = ui.copy(
                        error = message,
                        setup = true,
                        setupHint = message,
                        historyLoading = false,
                        historyLoadingOlder = false,
                    )
                }
            }
            "relay_exit" -> {
                val reason = event.optString("reason").ifBlank {
                    val tail = event.optJSONArray("stderr")
                    if (tail != null && tail.length() > 0) {
                        tail.optString(tail.length() - 1)
                    } else {
                        ""
                    }
                }
                val exitText = str("error.relayExited", "pig 已退出（code {code}），正在重连…")
                    .replace("{code}", event.optInt("code").toString()) +
                    if (reason.isBlank()) "" else "\n$reason"
                AppLog.event("relay", "pig exit code=${event.optInt("code")} reason=$reason")
                ui = ui.copy(
                    busy = false,
                    status = "",
                    error = exitText,
                    streamingId = null,
                    pendingMessages = 0,
                    historyLoading = false,
                    historyLoadingOlder = false,
                )
                lastPongAt = 0
                clearLive()
                pendingToolPartials.clear()
                client.close()
                if (foreground) startAutoReconnect()
            }
            "relay_log" -> handleRelayLog(
                event.optString("source", "relay"),
                event.optString("level", "info"),
                event.optString("message"),
                replay = false,
            )
            "relay_logs" -> {
                // Buffered lines the relay produced before this client connected
                // (a failed extension build, a pig crash at startup).
                val lines = event.optJSONArray("lines") ?: JSONArray()
                var lastError: JSONObject? = null
                for (index in 0 until lines.length()) {
                    val line = lines.optJSONObject(index) ?: continue
                    if (line.optString("level") == "error") lastError = line
                    handleRelayLog(
                        line.optString("source", "relay"),
                        line.optString("level", "info"),
                        line.optString("message"),
                        replay = true,
                    )
                }
                // One toast for the newest failure, not one per replayed line.
                lastError?.let {
                    toastRelayError(it.optString("source", "relay"), it.optString("message"))
                }
            }
            "relay_install" -> {
                val state = event.optString("state")
                val step = event.optInt("step")
                val total = event.optInt("total")
                val message = event.optString("message")
                when (state) {
                    "running" -> ui = ui.copy(
                        installRunning = true,
                        setup = true,
                        installStep = step,
                        installTotal = total,
                        installMessage = message,
                        setupHint = null,
                    )
                    "done" -> {
                        ui = ui.copy(
                            installRunning = false,
                            setup = false,
                            setupHint = null,
                            installStep = if (total > 0) total else step,
                            installTotal = total,
                            installMessage = message,
                        )
                        // Agent RPCs sent during the install were dropped by
                        // the gate; ask again now that pig may start.
                        requestState()
                        requestStats()
                        requestSessions()
                        requestModels()
                        refreshCommands(true)
                        AppLog.event("install", "relay reports done ($step/$total)")
                    }
                    "error" -> {
                        val text = message.ifBlank {
                            str("setup.installFailed", "安装命令失败，请到 Termux 里看输出")
                        }
                        ui = ui.copy(
                            installRunning = false,
                            installStep = step,
                            installTotal = total,
                            installMessage = message,
                            setup = true,
                            setupHint = text,
                            error = text,
                            toast = text,
                        )
                        AppLog.event("install", "relay reports error: $text")
                    }
                }
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
                    hideThinkingBlock = settings.optBoolean("hideThinkingBlock", false),
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
                // reads models.json, so a pinned window is live. pid 0 means the
                // relay had no agent to replace — do not claim it applied.
                val pid = event.optInt("pid", 0)
                restartInFlight = false
                ui = ui.copy(
                    status = "",
                    busy = false,
                    pendingAgentRestart = false,
                    // The replaced process took its status lines with it.
                    extensionStatuses = emptyMap(),
                    toast = when {
                        providerRestartPending ->
                            str("provider.restarted", "已重启 pig，供应商配置已生效")
                        pid > 0 -> str("toast.agentRestarted", "已重启 pig，新的上下文长度生效")
                        else -> str("toast.restartIdle", "没有正在运行的 pig，下次启动时按新长度执行")
                    },
                )
                requestState()
                requestStats()
                refreshCommands()
                // A provider save or a models.json edit also only becomes live
                // with this restart: refresh the list the picker shows.
                requestModels()
                providerRestartPending = false
            }
            "relay_model_override_error" -> {
                val message = event.optString("message").takeIf { it.isNotBlank() }
                    ?: str("toast.contextWindowLocalOnly", "relay 版本过旧，上下文长度仅在本机生效")
                appendNotice(message)
            }
            "relay_providers" -> {
                val list = mutableListOf<ProviderInfo>()
                event.optJSONArray("providers")?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { list += parseProvider(it) }
                    }
                }
                val fileIds = mutableListOf<String>()
                event.optJSONArray("modelsProviders")?.let { array ->
                    for (index in 0 until array.length()) {
                        fileIds += array.optString(index)
                    }
                }
                ui = ui.copy(
                    providers = list,
                    providersModelsFile = fileIds,
                    providersPath = event.optString("path"),
                    providersLoading = false,
                    providersOffline = event.optBoolean("offline"),
                )
            }
            "relay_provider_models" -> {
                val models = parseProviderModels(event.optJSONArray("models"))
                ui = ui.copy(
                    providerBusy = false,
                    providerModels = models,
                    providerModelsFor = event.optString("provider"),
                    providerModelsAt = event.optLong("fetchedAt"),
                    providerMessage = str("provider.fetched", "已获取 {n} 个模型")
                        .replace("{n}", models.size.toString()),
                    providerMessageError = false,
                )
            }
            "relay_provider_saved" -> {
                val provider = event.optJSONObject("provider")?.let { parseProvider(it) }
                val warning = event.optString("warning")
                val next = ui.providers.filterNot { it.id == provider?.id } +
                    listOfNotNull(provider)
                ui = ui.copy(
                    providers = next.sortedBy { it.id },
                    providerEpoch = ui.providerEpoch + 1,
                    providerBusy = false,
                    providerMessage = warning.ifBlank {
                        str("provider.saved", "已保存 {id}，重启 pig 后生效")
                            .replace("{id}", provider?.id ?: "")
                    },
                    providerMessageError = warning.isNotBlank(),
                    toast = warning.ifBlank {
                        str("provider.savedToast", "供应商已保存，正在重启 pig…")
                    },
                )
                AppLog.event("provider", "saved ${provider?.id} auto=${provider?.auto}")
                providerRestartPending = true
                restartAgentNow()
                requestProviders()
            }
            "relay_provider_deleted" -> {
                val id = event.optString("provider")
                ui = ui.copy(
                    providers = ui.providers.filterNot { it.id == id },
                    providerEpoch = ui.providerEpoch + 1,
                    providerBusy = false,
                    providerMessage = str("provider.deleted", "已删除 {id}，重启 pig 后生效")
                        .replace("{id}", id),
                    providerMessageError = false,
                    toast = str("provider.deletedToast", "已删除供应商 {id}")
                        .replace("{id}", id),
                )
                AppLog.event("provider", "deleted $id")
                providerRestartPending = true
                restartAgentNow()
                requestProviders()
            }
            "relay_provider_error" -> {
                val message = event.optString("message").ifBlank {
                    str("provider.failed", "供应商操作失败")
                }
                // A refresh can fail transiently; saving retries the fetch and
                // the extension fetches again when pig restarts, so point the
                // user at the next step instead of dead-ending.
                val hint = if (event.optString("op") == "refresh") {
                    str(
                        "provider.refreshHint",
                        "可以先保存：保存时会再抓一次，pig 启动后也会重试",
                    )
                } else {
                    ""
                }
                ui = ui.copy(
                    providerBusy = false,
                    providerMessage = if (hint.isBlank()) message else "$message\n$hint",
                    providerMessageError = true,
                    toast = message,
                )
                AppLog.event("provider", "error: ${event.optString("op")} $message")
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
            "queue_update" -> {
                // The only place pig reports *what* is queued (get_state has a
                // bare count), and the only way to tell steering from follow-up.
                val steering = event.optJSONArray("steering").stringValues()
                val followUp = event.optJSONArray("followUp").stringValues()
                ui = ui.copy(
                    queuedMessages = steering.map { QueuedMessage("steer", it) } +
                        followUp.map { QueuedMessage("followUp", it) },
                    pendingMessages = steering.size + followUp.size,
                )
            }
            "agent_start" -> {
                ui = ui.copy(busy = true, status = "thinking")
                // A just-started pig is when the model list becomes readable
                // again after the relay restarted without an agent.
                if (models.isEmpty()) requestModels()
            }
            "turn_start" -> ui = ui.copy(status = "working")
            "agent_end", "agent_settled" -> {
                ui = ui.copy(
                    busy = false,
                    status = "",
                    streamingId = null,
                    pendingMessages = 0,
                    queuedMessages = emptyList(),
                )
                clearLive()
                pendingToolPartials.clear()
                requestStats()
                refreshCommands()
                refreshDataSources("agent_end")
                if (type == "agent_settled" && !event.optBoolean("aborted", false) && ui.notifyDone) {
                    val last = ui.messages.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()
                    notifySession(
                        SessionNotifications.Kind.DONE,
                        str("notification.done", "回复完成") + " · " + sessionLabel(),
                        preview(last),
                    )
                }
                if (ui.pendingAgentRestart) {
                    // Let pig finish flushing the session file before the relay
                    // replaces the process.
                    viewModelScope.launch {
                        delay(700)
                        if (ui.pendingAgentRestart) restartAgent()
                    }
                }
            }
            "message_start" -> handleMessageStart(event.optJSONObject("message"))
            "message_update" -> handleMessageUpdate(event)
            "message_end" -> handleMessageEnd(event.optJSONObject("message"))
            "tool_execution_start" -> handleToolStart(event)
            "tool_execution_update" -> handleToolUpdate(event)
            "tool_execution_end" -> handleToolEnd(event)
            "extension_ui_request" -> handleUiRequest(event)
            "thinking_level_changed" -> {
                // pig also changes the level on its own (cycle_thinking_level,
                // extensions); without this the header keeps the old value.
                val level = event.optString("level")
                if (level.isNotBlank()) ui = ui.copy(thinkingLevel = level)
            }
            "compaction_start" -> ui = ui.copy(status = "compacting")
            "compaction_end" -> {
                ui = ui.copy(status = "", contextCompacted = true)
                requestStats()
                refreshDataSources("compaction_end")
                if (ui.notifyCompacted) {
                    notifySession(
                        SessionNotifications.Kind.COMPACTED,
                        str("notification.compacted", "上下文已压缩"),
                        sessionLabel(),
                    )
                }
                if (ui.pendingAgentRestart) {
                    viewModelScope.launch {
                        delay(700)
                        if (ui.pendingAgentRestart) restartAgent()
                    }
                }
            }
            "extension_error" -> {
                // An extension threw. pig reports it on this channel instead of
                // failing the turn, and nothing else in the app would show it.
                val message = listOf("message", "error", "text", "reason")
                    .map { event.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                    ?: event.toString().take(400)
                val source = listOf("extension", "extensionName", "name", "source")
                    .map { event.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                val text = if (source == null) {
                    str("error.extension", "扩展出错：{message}").replace("{message}", message)
                } else {
                    str("error.extensionNamed", "扩展 {name} 出错：{message}")
                        .replace("{name}", source)
                        .replace("{message}", message)
                }
                showExtensionError(text)
            }
            "session_info_changed" -> {
                // An extension renamed the session (or cleared the name).
                val name = event.optString("name")
                AppLog.event("vm", "session name changed: ${name.ifBlank { "(cleared)" }}")
                val current = ui.sessionFile.ifBlank { ui.sessionId }
                if (current.isNotBlank()) {
                    ui = ui.copy(
                        sessions = ui.sessions.map {
                            if (it.path == current || it.id == ui.sessionId) {
                                it.copy(name = name)
                            } else {
                                it
                            }
                        },
                    )
                }
                requestSessions()
            }
            "summarization_retry_scheduled", "summarization_retry_attempt_start" -> {
                AppLog.event("vm", "summarization retry ($type)")
                ui = ui.copy(status = "summarizing")
            }
            "summarization_retry_finished" -> {
                AppLog.event("vm", "summarization retry finished")
                ui = ui.copy(status = "")
            }
            "error" -> {
                val message = event.optString("message", event.optString("error", "unknown error"))
                appendNotice(message)
                ui = ui.copy(busy = false, status = "")
                if (ui.notifyError) {
                    notifySession(
                        SessionNotifications.Kind.ERROR,
                        str("notification.error", "出错了") + " · " + sessionLabel(),
                        preview(message),
                    )
                }
            }
            else -> if (type.startsWith("auto_retry")) ui = ui.copy(status = "retrying")
        }
    }

    private fun handleResponse(event: JSONObject) {
        val responseCommand = event.optString("command")
        val responseId = event.optString("id")
        // The relay loads a restored session into its prewarmed pig before the
        // first prompt; that switch is internal, not the user's switch.
        if (responseId.startsWith("standby-")) return
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
            if (command == "get_messages") {
                // Don't leave the "reading history" spinner up over a failure.
                ui = ui.copy(historyLoading = false, historyLoadingOlder = false)
            }
            if (command == "set_model") {
                modelSwitchAccepted = false
                pendingThinkingRestore = null
                ui = ui.copy(
                    modelSwitchPending = false,
                    pendingModel = "",
                    toast = str("toast.modelSwitchFailed", "模型切换失败"),
                )
            }
            if (command == "new_session") {
                // The replacement failed: the prompt the user already typed is
                // delivered to whatever session is active instead of vanishing.
                ui = ui.copy(
                    status = "",
                    toast = str("toast.newSessionFailed", "新建会话失败"),
                )
                awaitingNewSession = false
                flushPendingPrompts()
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
        if (command == "abort") {
            // Stopping is immediate for the UI even if pig's agent_end arrives
            // later; a spinner that outlives the stop is the classic symptom.
            ui = ui.copy(busy = false, status = "", streamingId = null, pendingMessages = 0)
            return
        }
        if (command == "compact" && !event.optBoolean("success", true)) {
            ui = ui.copy(status = "", toast = str("toast.compactFailed", "压缩失败"))
            return
        }
        if (command == "set_thinking_level") {
            val level = event.optJSONObject("data")?.optString("level").orEmpty()
            if (responseId.startsWith("think-auto-")) {
                // Restoring a remembered level: refresh state, but keep the
                // "model switched" toast from being overwritten.
                if (level.isNotBlank()) ui = ui.copy(thinkingLevel = level)
                requestState()
                return
            }
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
            // The old transcript stays on screen (with the "starting" banner)
            // until get_state describes the session that replaced it; switching
            // history is a file read, so it can be cleared right away.
            val switchingToFile = command == "switch_session"
            historyWindow = HISTORY_PAGE
            // Paint the transcript built last time immediately; the fresh copy
            // is requested right below and replaces it in place (stable ids, so
            // nothing visibly changes).
            val cachedShown = switchingToFile &&
                desiredSessionPath?.let { showCachedHistory(it) } == true
            ui = ui.copy(
                messages = if (switchingToFile && !cachedShown) emptyList() else ui.messages,
                historyHasOlder = if (cachedShown) ui.historyHasOlder else false,
                historyLoading = switchingToFile,
                historyLoadingOlder = false,
                status = if (switchingToFile || ui.status == "starting" ||
                    ui.status == "switching"
                ) {
                    ui.status
                } else {
                    ""
                },
                streamingId = null,
                pendingMessages = 0,
                contextPercent = if (switchingToFile) null else ui.contextPercent,
                contextTokens = if (switchingToFile) 0 else ui.contextTokens,
                contextWindowActual = if (switchingToFile) 0 else ui.contextWindowActual,
                contextCompacted = if (switchingToFile) false else ui.contextCompacted,
                cacheHitRate = if (switchingToFile) null else ui.cacheHitRate,
                usage = if (switchingToFile) SessionUsage() else ui.usage,
                cacheReadTokens = if (switchingToFile) 0 else ui.cacheReadTokens,
                cacheInputTokens = if (switchingToFile) 0 else ui.cacheInputTokens,
            )
            clearLive()
            pendingToolPartials.clear()
            historyHealTried = false
            needsHistory = false
            requestState()
            if (switchingToFile) requestMessages()
            requestStats()
            requestSessions()
            // The relay starts pig for this session, and the picker may have
            // been opened while it was still down.
            requestModels()
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
                val reportedSessionId = data.optString("sessionId", "")
                val reportedFile = data.optString("sessionFile", ui.sessionFile)
                val streaming = data.optBoolean("isStreaming", false)
                val live = data.optBoolean("live", true)
                // While a new session is being created, a reply that still
                // describes the session being replaced must not be adopted as the
                // new conversation, or its draft follows the wrong file. The same
                // reply can arrive late, after the new session settled, so a
                // straggler is ignored for as long as no switch is in flight.
                val straggler = desiredSessionPath == null && !restorePending &&
                    reportedFile.isNotBlank() && reportedFile == newSessionFromFile &&
                    ui.sessionFile.isNotBlank() && ui.sessionFile != reportedFile
                val settledNow = !straggler &&
                    (!awaitingNewSession || newSessionSettled(reportedSessionId, reportedFile))
                val sessionId = if (settledNow) reportedSessionId else ui.sessionId
                val sessionFile = if (settledNow) reportedFile else ui.sessionFile
                val switched = sessionId.isNotBlank() &&
                    ui.sessionId.isNotBlank() &&
                    sessionId != ui.sessionId
                if (switched && !liveActive) {
                    currentAssistant = null
                    // pig switched sessions on its own (a command inside the
                    // session): the transcript below is cleared, so fetch the
                    // new one instead of leaving an empty screen behind.
                    if (!ui.historyLoading) {
                        historyWindow = HISTORY_PAGE
                        requestMessages()
                    }
                }
                // The reply to the get_state issued after new_session means the
                // backend is ready: the transcript shown above is already the
                // new one (the user may have typed into it), so only the banner
                // goes away. A reply from the runtime being replaced does not
                // count, or the queued prompt would run in the old session.
                if (awaitingNewSession && settledNow) {
                    awaitingNewSession = false
                    if (!liveActive) currentAssistant = null
                    adoptPendingDraft(sessionFile)
                    flushPendingPrompts()
                }
                // A conversation that opened without any session file kept its
                // draft under the placeholder key; adopt the file as soon as pig
                // reports one, so the composer key change keeps the text.
                if (ui.sessionFile.isBlank() && sessionFile.isNotBlank()) {
                    adoptPendingDraft(sessionFile)
                }
                // A different session file is a different conversation, so the
                // composer has to load its draft. A conversation that is only now
                // getting the file it did not have keeps what is typed.
                val conversationChanged = sessionFile.isNotBlank() &&
                    ui.sessionFile.isNotBlank() && sessionFile != ui.sessionFile
                // Another conversation also brings its own cards. They are pushed,
                // never queried, so draw the snapshot taken when they last arrived
                // until the extension (or the relay's replay) says otherwise.
                val restoredWidgets = if (
                    sessionFile.isNotBlank() &&
                    (conversationChanged || ui.sessionFile.isBlank())
                ) {
                    widgetsForSession(sessionFile)
                } else {
                    ui.widgets
                }
                var status = ui.status
                var toast = ui.toast
                if ((status == "starting" && settledNow) || status == "switching") status = ""

                if (!restoreChecked && settledNow) {
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
                // pig reports a bare queue size here, never its contents.
                val pendingQueued = data.optInt("pendingMessageCount", ui.pendingMessages)
                ui = ui.copy(
                    model = label,
                    sessionId = sessionId,
                    sessionFile = sessionFile,
                    draftEpoch = if (conversationChanged) ui.draftEpoch + 1 else ui.draftEpoch,
                    widgets = restoredWidgets,
                    // No session attached: no history is coming, so don't leave
                    // the reader on the "reading history" spinner.
                    historyLoading = if (sessionFile.isBlank()) false else ui.historyLoading,
                    busy = streaming,
                    readOnly = !live,
                    // A new session already cleared the list when it opened;
                    // anything on screen here was typed into it.
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
                    pendingMessages = pendingQueued,
                    // A zero count means nothing waits; a non-zero count with an
                    // empty list is a reconnect, where the contents are unknown.
                    queuedMessages = if (pendingQueued == 0) emptyList() else ui.queuedMessages,
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
                    val usage = SessionUsage(
                        input = input,
                        output = totals.optLong("output"),
                        cacheRead = cacheRead,
                        cacheWrite = cacheWrite,
                        total = totals.optLong("total"),
                        cost = data.optDouble("cost", 0.0),
                        userMessages = data.optInt("userMessages", 0),
                        assistantMessages = data.optInt("assistantMessages", 0),
                        toolCalls = data.optInt("toolCalls", 0),
                        toolResults = data.optInt("toolResults", 0),
                        totalMessages = data.optInt("totalMessages", 0),
                    )
                    ui = ui.copy(
                        usage = usage,
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
                val historyStart = data.optInt("historyStart", 0)
                val foreignEmpty = history.length() == 0 && ui.messages.isNotEmpty() &&
                    (taggedPath.isBlank() || taggedPath == ui.sessionFile)
                if (foreignEmpty) {
                    // The relay has no session attached yet; keep what is on
                    // screen and retry once get_state tells us the session.
                    needsHistory = true
                } else {
                    needsHistory = false
                    historyHealTried = true
                    val requestId = responseId
                    val windowAtRequest = historyWindow
                    val pending = pendingToolPartials.toMap()
                    val liveAtRequest = liveActive
                    val live = if (liveAtRequest) liveBuffer.toString() else null
                    val notices = readNotices()
                    val redacted = str("chat.thinkingRedacted", "（提供方已隐藏思考内容）")
                    val startedAt = System.nanoTime()
                    viewModelScope.launch {
                        val build = withContext(Dispatchers.Default) {
                            buildHistory(history, pending, live, notices, redacted)
                        }
                        Log.i(
                            "PigPerf",
                            "history build ${(System.nanoTime() - startedAt) / 1_000_000}ms, " +
                                "${build.items.size} items, window $windowAtRequest",
                        )
                        // A newer get_messages (session switch, heal) may have
                        // been issued while this one was building.
                        if (requestId.isNotBlank() && requestId != lastMessagesRequestId) {
                            return@launch
                        }
                        // A turn started while the history was building: its
                        // messages are not in `build`, so replacing the list
                        // would drop them.
                        if (!liveAtRequest && liveActive) return@launch
                        ui = ui.copy(
                            historyHasOlder = historyStart > 0,
                            historyLoading = false,
                            historyLoadingOlder = false,
                        )
                        applyHistory(
                            build,
                            taggedPath.ifBlank { ui.sessionFile },
                            windowAtRequest,
                            historyStart > 0,
                        )
                    }
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
                AppLog.event("cmds", "${list.size}: " + list.joinToString(" ") { it.name })
                ui = ui.copy(commands = list)
                // The UI pack's own buttons (nav.json, windows) call extension
                // commands. pig degrades to built-ins silently when its packages
                // cannot be installed, and then "/provider-ui" is sent as a
                // chat message (pig answers "No API key found").
                val missing = ui.uiPack.nav
                    .map { it.command.substringBefore(' ').trim() }
                    .filter { it.isNotBlank() && list.none { c -> c.name == it } }
                    .distinct()
                if (missing.isNotEmpty()) warnMissingExtensions(missing)
            }
            "get_available_models" -> {
                val array = data.optJSONArray("models")
                // 100+ models with capabilities; parsing this in the event loop
                // is a visible stall right when the picker opens.
                viewModelScope.launch {
                    val list = withContext(Dispatchers.Default) {
                        if (array == null) {
                            emptyList()
                        } else {
                            (0 until array.length())
                                .mapNotNull { index -> array.optJSONObject(index)?.let { modelInfo(it) } }
                                .sortedWith(compareBy({ it.provider }, { it.id }))
                        }
                    }
                    models = list
                    modelsLoading = false
                    AppLog.event("models", "${list.size} available")
                    ui = ui.copy(
                        availableModelCount = list.size,
                        modelsLoaded = true,
                        // Once models exist the setup banner has done its job;
                        // if they disappear later it may show again.
                        providerSetupDismissed = if (list.isEmpty()) {
                            ui.providerSetupDismissed
                        } else {
                            false
                        },
                    )
                }
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
                // Apply this model's remembered thinking level only after the
                // switch is confirmed, so it cannot land on the old model.
                val restore = pendingThinkingRestore
                pendingThinkingRestore = null
                if (!restore.isNullOrBlank()) {
                    client.send(
                        JSONObject()
                            .put("id", "think-auto-${nextId++}")
                            .put("type", "set_thinking_level")
                            .put("level", restore),
                    )
                }
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
                            unsaved = item.optBoolean("unsaved", false),
                        ),
                    )
                }
            }
        }
        ui = ui.copy(sessions = sessions, sessionsLoading = false)
    }

    private data class HistoryBuild(
        val items: List<ChatMsg>,
        val toolMsgIds: Map<String, String>,
        /** callId -> partial text that was consumed; only removed if unchanged. */
        val consumed: Map<String, String>,
        val assistantId: String?,
        /** Text to continue from when history got further than the relay snapshot. */
        val liveText: String?,
    )

    /**
     * Pure builder for [renderHistory]. Runs off the main thread: a 2000-message
     * session is ~8 MB of JSON and walking it on the frame thread is what made
     * session switches stutter.
     */
    private fun buildHistory(
        messages: org.json.JSONArray,
        pending: Map<String, String>,
        live: String?,
        notices: List<ChatMsg>,
        redactedLabel: String,
    ): HistoryBuild {
        val out = mutableListOf<ChatMsg>()
        val toolIds = mutableMapOf<String, String>()
        val toolMsgIds = HashMap<String, String>()
        val consumed = HashMap<String, String>()
        // id -> position in `out`, so a toolResult does not scan the whole
        // transcript. A 2000-message session has ~1800 of them.
        val indexById = HashMap<String, Int>()
        val lastInputRole = if (messages.length() > 0) {
            messages.optJSONObject(messages.length() - 1)?.optString("role")
        } else {
            null
        }
        var counter = 1
        // Screenshots are decoded here (this runs on Dispatchers.Default) and
        // the pixel budget keeps a long session from holding hundreds of
        // bitmaps. The budget is spent newest-first: those are the messages on
        // screen, and loading an older page must not evict them.
        val imageBudget = ImageBudget(HISTORY_IMAGE_PIXEL_BUDGET)
        val decodedImages = HashMap<Int, DecodedImages>()
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (!hasImages(message)) continue
            decodedImages[index] = decodeChatImages(
                message,
                imageBudget,
                message.optString("timestamp").takeIf { it.isNotBlank() },
            )
        }
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            // Ids come from the message timestamp, not from a counter: paging
            // prepends items, and stable keys keep tool cards expanded and
            // screenshots cached when a window grows.
            val stamp = message.optString("timestamp").ifBlank { "i$index" }
            var slot = 0
            fun itemId(): String = "h$stamp-${slot++}"
            when (message.optString("role")) {
                "user" -> {
                    val text = contentText(message)
                    val decoded = decodedImages[index] ?: DecodedImages(emptyList(), 0)
                    if (text.isNotBlank() || decoded.images.isNotEmpty()) {
                        out.add(
                            ChatMsg(
                                itemId(),
                                Role.USER,
                                text = text,
                                images = decoded.images,
                                imagesOmitted = decoded.omitted,
                            ),
                        )
                    }
                }
                "assistant" -> {
                    val text = contentText(message)
                    val thinking = contentThinking(message, redactedLabel)
                    if (text.isNotBlank() || thinking.isNotBlank() || hasImages(message)) {
                        val decoded = decodedImages[index] ?: DecodedImages(emptyList(), 0)
                        out.add(
                            ChatMsg(
                                itemId(),
                                Role.ASSISTANT,
                                text = text,
                                thinking = thinking,
                                images = decoded.images,
                                imagesOmitted = decoded.omitted,
                            ),
                        )
                    }
                    // Pig stores failed turns as an assistant message with
                    // errorMessage; without this they vanish on reload.
                    val failure = message.optString("errorMessage")
                    if (failure.isNotBlank()) {
                        out.add(ChatMsg(itemId(), Role.NOTICE, text = failure, isError = true))
                    }
                    val blocks = message.optJSONArray("content") ?: continue
                    for (blockIndex in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(blockIndex) ?: continue
                        if (block.optString("type") != "toolCall") continue
                        val callId = block.optString("id")
                        val id = itemId()
                        if (callId.isNotBlank()) {
                            toolIds[callId] = id
                            // Let live tool_execution_update/end events find the
                            // card after a reconnect.
                            toolMsgIds[callId] = id
                        }
                        val partial = if (callId.isBlank()) null else pending[callId]
                        if (partial != null) consumed[callId] = partial
                        indexById[id] = out.size
                        out.add(
                            ChatMsg(
                                id = id,
                                role = Role.TOOL,
                                toolName = block.optString("name", "tool"),
                                // Compact here: pretty-printing ~1800 argument
                                // objects per history load is wasted work; the
                                // tool card indents the ones it actually shows.
                                toolArgs = block.optJSONObject("arguments")?.toString() ?: "",
                                toolResult = partial ?: "",
                                running = partial != null,
                            ),
                        )
                    }
                }
                "toolResult" -> {
                    val text = contentText(message)
                    val decoded = decodedImages[index] ?: DecodedImages(emptyList(), 0)
                    val id = toolIds[message.optString("toolCallId")]
                    val position = id?.let { indexById[it] } ?: -1
                    val meta = JSONObject()
                    message.optJSONObject("details")?.let { meta.put("details", it) }
                    message.optJSONObject("structuredContent")?.let { meta.put("structured", it) }
                    if (position >= 0) {
                        out[position] = out[position].copy(
                            running = false,
                            toolResult = text,
                            toolDetails = meta.toString(),
                            isError = message.optBoolean("isError", false),
                            images = decoded.images,
                            imagesOmitted = decoded.omitted,
                        )
                    } else {
                        out.add(
                            ChatMsg(
                                id = itemId(),
                                role = Role.TOOL,
                                toolName = message.optString("toolName", "tool"),
                                toolResult = text,
                                isError = message.optBoolean("isError", false),
                                images = decoded.images,
                                imagesOmitted = decoded.omitted,
                            ),
                        )
                    }
                }
            }
        }
        // If the relay replayed an in-flight assistant message, put it back
        // after the history rebuild. Only the last input message can be the
        // partial assistant, so never overwrite an older completed bubble.
        var assistantId: String? = null
        var liveText: String? = null
        if (!live.isNullOrEmpty() && lastInputRole == "assistant") {
            val last = out.lastOrNull()
            if (last?.role == Role.ASSISTANT) {
                if (last.text.length > live.length) liveText = last.text
                out[out.lastIndex] = last.copy(text = if (last.text.length < live.length) live else last.text)
                assistantId = out.last().id
            } else {
                val id = "h${counter++}"
                out.add(ChatMsg(id = id, role = Role.ASSISTANT, text = live))
                assistantId = id
            }
        }
        // App-recorded failures (relay/transport) are not in pig's file, so
        // replay them from the per-session log; nothing above is duplicated.
        val known = out.filter { it.role == Role.NOTICE }.mapTo(HashSet()) { it.text }
        out += notices.filterNot { it.text in known }
        return HistoryBuild(out, toolMsgIds, consumed, assistantId, liveText)
    }

    private fun applyHistory(
        build: HistoryBuild,
        path: String = ui.sessionFile,
        window: Int = historyWindow,
        hasOlder: Boolean = ui.historyHasOlder,
    ) {
        toolMsgIds.clear()
        toolMsgIds.putAll(build.toolMsgIds)
        build.consumed.forEach { (callId, text) ->
            // A newer partial may have arrived while the history was built.
            if (pendingToolPartials[callId] == text) pendingToolPartials.remove(callId)
        }
        build.liveText?.let {
            liveBuffer.setLength(0)
            liveBuffer.append(it)
        }
        currentAssistant = build.assistantId
        ui = ui.copy(messages = build.items)
        rememberHistory(path, window, hasOlder)
        bindResumedAssistant()
    }

    /** Built transcripts of the last few sessions, so switching back is instant. */
    private data class CachedHistory(
        val items: List<ChatMsg>,
        val window: Int,
        val hasOlder: Boolean,
    )

    /** Access-ordered LRU: newest last, and switching back to a session revives it. */
    private val historyCache = LinkedHashMap<String, CachedHistory>(8, 0.75f, true)

    private fun rememberHistory(path: String, window: Int, hasOlder: Boolean) {
        if (path.isBlank() || ui.messages.isEmpty()) return
        synchronized(historyCache) {
            historyCache[path] = CachedHistory(ui.messages, window, hasOlder)
            while (historyCache.size > HISTORY_CACHE_SESSIONS) {
                val oldest = historyCache.keys.firstOrNull() ?: break
                historyCache.remove(oldest)
            }
        }
    }

    /**
     * Puts a previously built transcript on screen while the fresh one is still
     * in flight, and restores the page size it was built with so the refresh
     * does not shrink what the reader already had.
     */
    private fun showCachedHistory(path: String): Boolean {
        if (path.isBlank() || ui.messages.isNotEmpty()) return false
        val cached = synchronized(historyCache) { historyCache[path] } ?: return false
        historyWindow = cached.window
        ui = ui.copy(messages = cached.items, historyHasOlder = cached.hasOlder)
        return true
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
        // A relay images before it seeded a default pack (or a ui dir where
        // only extensions have written something) pushes files without
        // manifest.json. Older app versions turned that into the red
        // "UI pack 未应用: manifest.json 缺失" toast on every fresh install;
        // keeping the current theme is the honest answer.
        if (files["manifest.json"].isNullOrBlank()) {
            if (files.isNotEmpty() && !warnedIncompleteRelayPack) {
                warnedIncompleteRelayPack = true
                ui = ui.copy(
                    toast = str(
                        "toast.packIncomplete",
                        "relay 没有提供 manifest.json（relay 版本过旧），继续使用当前主题",
                    ),
                )
            }
            return
        }
        val relayName = runCatching {
            JSONObject(files["manifest.json"].orEmpty()).optString("name")
        }.getOrNull().orEmpty()
        ui = ui.copy(relayPackName = relayName.ifBlank { ui.relayPackName })
        // provider-ui rewrites this window after every save/refresh; the new
        // provider is only usable once the app asks for the model list again.
        if (files.containsKey("windows/providers.json")) requestModels()
        // A pack the user picked by hand outranks the relay; the default
        // bundled theme steps aside so Termux keeps driving the look.
        if (packPinnedByUser) return
        if (ui.activePackId.isNotBlank()) {
            ui = ui.copy(activePackId = "")
            prefs.edit().putString("activePackId", "").apply()
        }
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
            AppLog.event("pack", "rejected: $message (${files.size} files)")
            ui = ui.copy(
                uiPackError = message,
                toast = str("error.packNotApplied", "UI pack 未应用: {error}")
                    .replace("{error}", message),
            )
            return
        }
        val pack = parsed.copy(fontFiles = materializeFonts(assets))
        basePack = pack
        AppLog.event(
            "pack",
            "applied ${pack.name} v${pack.version} · ${files.size} files · hash=${hash.take(12)}" +
                if (result.warnings.isEmpty()) "" else " · warnings: ${result.warnings.joinToString("; ")}",
        )
        AppLog.fact("pack", "${pack.name} v${pack.version} (${files.size} files)")
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
        val thinking = contentThinking(message)
        liveBuffer.setLength(0)
        liveBuffer.append(text)
        liveThinking.setLength(0)
        liveThinking.append(thinking)
        liveThinkingText = thinking
        liveThinkingDirty = false
        liveActive = true
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT, text = text, thinking = thinking))
        }
        update(id) { it.copy(text = text, thinking = thinking) }
        ui = ui.copy(streamingId = id)
    }

    private fun handleMessageUpdate(event: JSONObject?) {
        if (event == null) return
        val update = event.optJSONObject("assistantMessageEvent") ?: return
        when (update.optString("type")) {
            "thinking_delta" -> {
                val delta = update.optString("delta", "")
                if (delta.isEmpty()) return
                liveThinking.append(delta)
                liveThinkingDirty = true
                if (currentAssistant == null) {
                    currentAssistant = "a${nextId++}".also {
                        append(ChatMsg(id = it, role = Role.ASSISTANT))
                        ui = ui.copy(streamingId = it)
                    }
                }
                liveActive = true
                scheduleLiveFlush()
                return
            }
            "thinking_end" -> {
                // The end event carries the complete block; trust it over the
                // accumulated deltas (providers can rewrite earlier text).
                val content = update.optString("content", "")
                if (content.isNotEmpty()) {
                    liveThinking.setLength(0)
                    liveThinking.append(content)
                    liveThinkingDirty = true
                    liveActive = true
                    scheduleLiveFlush()
                }
                return
            }
            "text_delta" -> Unit
            else -> return
        }
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
            val thinking = liveThinkingValue()
            val id = currentAssistant ?: return@launch
            update(id) { it.copy(text = text, thinking = thinking) }
        }
    }

    /** Materialise the reasoning buffer only when it actually changed. */
    private fun liveThinkingValue(): String {
        if (liveThinkingDirty) {
            liveThinkingText = liveThinking.toString()
            liveThinkingDirty = false
        }
        return liveThinkingText
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
        val thinking = contentThinking(message)
        update(id) {
            it.copy(
                text = text.ifBlank { it.text },
                thinking = thinking.ifBlank { it.thinking },
            )
        }
        attachImages(id, message)
        // Some upstreams (DeepSeek "thinking" models behind proxies) answer with
        // reasoning even at level off. Remember that, so the picker can say so
        // instead of leaving the header looking wrong.
        val level = message.optString("thinkingLevel")
        if (level == "off") {
            val reasoning = message.optJSONObject("usage")?.optInt("reasoning", 0) ?: 0
            ui = ui.copy(upstreamIgnoresOff = reasoning > 0)
        } else if (level.isNotBlank()) {
            ui = ui.copy(upstreamIgnoresOff = false)
        }
        // Tool-call-only assistant messages have no text; the tool card is the
        // visible representation, so drop the empty bubble. An image-only
        // assistant message still has something to show.
        val finished = ui.messages.firstOrNull { it.id == id }
        if (finished != null && finished.text.isBlank() && finished.thinking.isBlank() &&
            finished.images.isEmpty() && !hasImages(message)
        ) {
            remove(id)
        }
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

    /**
     * Bash and long-running tools stream their output. Applying every frame
     * meant parsing the whole partial result and copying the message list at
     * event rate; keep the newest raw payload and render at ~20 fps instead.
     */
    private fun handleToolUpdate(event: JSONObject) {
        val callId = event.optString("toolCallId")
        if (callId.isEmpty()) return
        val partial = event.optJSONObject("partialResult") ?: return
        toolPartialRaw[callId] = partial
        scheduleToolFlush()
    }

    private fun scheduleToolFlush() {
        if (!foreground) return
        if (toolFlushScheduled) return
        toolFlushScheduled = true
        viewModelScope.launch {
            delay(50)
            toolFlushScheduled = false
            flushToolPartials()
        }
    }

    private fun flushToolPartials() {
        if (toolPartialRaw.isEmpty()) return
        val raw = toolPartialRaw.toMap()
        toolPartialRaw.clear()
        val byMessage = HashMap<String, String>(raw.size)
        raw.forEach { (callId, partial) ->
            val text = contentText(partial)
            if (text.isEmpty()) return@forEach
            pendingToolPartials[callId] = text
            toolMsgIds[callId]?.let { byMessage[it] = text }
        }
        if (byMessage.isEmpty()) return
        ui = ui.copy(
            messages = ui.messages.map { message ->
                byMessage[message.id]?.let { message.copy(toolResult = it) } ?: message
            },
        )
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
        // Tools such as `read` on an image, screenshots and image models
        // return image blocks next to the text summary.
        attachImages(id, resultObject)
    }

    private fun handleUiRequest(event: JSONObject) {
        val method = event.optString("method")
        when (method) {
            "confirm", "select", "input", "editor" -> {
                val options = event.optJSONArray("options")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                } ?: emptyList()
                if (ui.notifyAttention) {
                    notifySession(
                        SessionNotifications.Kind.ATTENTION,
                        str("notification.attention", "需要你的操作") + " · " + sessionLabel(),
                        preview(
                            event.optString("title").ifBlank {
                                event.optString("message")
                            }.ifBlank { str("notification.attentionBody", "扩展正在等待输入") },
                        ),
                    )
                }
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
                    // A new request always surfaces, even after the user hid
                    // the previous one by tapping the chat.
                    pendingHidden = false,
                )
            }
            "notify" -> {
                val message = event.optString("message", "")
                ui = ui.copy(toast = message)
                val kind = when (event.optString("notifyType")) {
                    "error" -> SessionNotifications.Kind.ERROR
                    "warning" -> SessionNotifications.Kind.ATTENTION
                    else -> SessionNotifications.Kind.INFO
                }
                val enabled = when (kind) {
                    SessionNotifications.Kind.ERROR -> ui.notifyError
                    SessionNotifications.Kind.ATTENTION -> ui.notifyAttention
                    else -> ui.notifyDone
                }
                if (enabled) {
                    notifySession(
                        kind,
                        sessionLabel(),
                        preview(message),
                    )
                }
            }
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

    /**
     * Reasoning blocks of an assistant message. A redacted block keeps its
     * signature only, so say so instead of rendering an empty section.
     */
    private fun contentThinking(object0: JSONObject?): String =
        contentThinking(object0, str("chat.thinkingRedacted", "（提供方已隐藏思考内容）"))

    private fun contentThinking(object0: JSONObject?, redactedLabel: String): String {
        if (object0 == null) return ""
        val blocks = object0.optJSONArray("content") ?: return ""
        val builder = StringBuilder()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") != "thinking") continue
            if (block.optBoolean("redacted", false)) {
                if (builder.isNotEmpty()) builder.append("\n\n")
                builder.append(redactedLabel)
                continue
            }
            val text = block.optString("thinking", "")
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append("\n\n")
                builder.append(text)
            }
        }
        return builder.toString()
    }

    // ─── Images ──────────────────────────────────────────────────────────

    /** Raw image blocks of a message, before decoding. */
    private fun imageBlocks(message: JSONObject?): List<Pair<String, String>> {
        val blocks = message?.optJSONArray("content") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") != "image") continue
            val data = block.optString("data")
            if (data.isEmpty()) continue
            out.add(data to block.optString("mimeType", "image/png"))
        }
        return out
    }

    private fun hasImages(message: JSONObject?): Boolean {
        val blocks = message?.optJSONArray("content") ?: return false
        for (index in 0 until blocks.length()) {
            if (blocks.optJSONObject(index)?.optString("type") == "image") return true
        }
        return false
    }

    /** Pixel budget for one history build; screenshots are ~4 bytes/pixel. */
    private class ImageBudget(var pixels: Long)

    /**
     * Screenshots already decoded, shared by rebuilds and by page growth so an
     * older page does not pay for base64 + JPEG again. Keyed by
     * `message timestamp#block index`; the size is in pixels.
     */
    private val historyImageCache =
        object : LruCache<String, ChatImage>(HISTORY_IMAGE_CACHE_PIXELS) {
            override fun sizeOf(key: String, value: ChatImage): Int =
                value.bitmap.width * value.bitmap.height
        }

    private data class DecodedImages(val images: List<ChatImage>, val omitted: Int)

    /**
     * Decode a pig image block. Images above [MAX_IMAGE_B64_CHARS] and images
     * that would blow [budget] are skipped (and reported as omitted).
     */
    private fun decodeChatImage(
        base64: String,
        mimeType: String,
        budget: ImageBudget? = null,
    ): ChatImage? {
        if (base64.isEmpty() || base64.length > MAX_IMAGE_B64_CHARS) return null
        // Once the page budget is spent, don't even decode the bytes.
        if (budget != null && budget.pixels <= 0) return null
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TRANSCRIPT_IMAGE_MAX_DIM) {
            sample *= 2
        }
        val pixels = (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample)
        if (budget != null && pixels > budget.pixels) return null
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        if (budget != null) budget.pixels -= bitmap.width.toLong() * bitmap.height
        return ChatImage(bitmap.asImageBitmap(), mimeType.ifBlank { "image/png" })
    }

    private fun decodeChatImages(
        message: JSONObject?,
        budget: ImageBudget? = null,
        cacheKey: String? = null,
    ): DecodedImages {
        val blocks = imageBlocks(message)
        var omitted = (blocks.size - MAX_IMAGES_PER_MESSAGE).coerceAtLeast(0)
        val images = ArrayList<ChatImage>()
        blocks.take(MAX_IMAGES_PER_MESSAGE).forEachIndexed { index, block ->
            val (data, mime) = block
            val key = cacheKey?.let { "$it#$index" }
            val cached = key?.let { historyImageCache.get(it) }
            if (cached != null) {
                // Already decoded and accounted for; don't charge the budget.
                images.add(cached)
                return@forEachIndexed
            }
            val image = decodeChatImage(data, mime, budget)
            if (image == null) {
                omitted++
            } else {
                images.add(image)
                if (key != null) historyImageCache.put(key, image)
            }
        }
        return DecodedImages(images, omitted)
    }

    /** Decode off the main thread, then attach to an already-rendered message. */
    private fun attachImages(id: String, message: JSONObject?) {
        if (!hasImages(message)) return
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.Default) {
                decodeChatImages(
                    message,
                    ImageBudget(HISTORY_IMAGE_PIXEL_BUDGET),
                    message?.optString("timestamp")?.takeIf { it.isNotBlank() },
                )
            }
            if (decoded.images.isEmpty() && decoded.omitted == 0) return@launch
            update(id) { it.copy(images = decoded.images, imagesOmitted = decoded.omitted) }
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

/** Imported packs live under `files/ui-packs/`; bundled ones under `assets/ui-packs/`. */
private const val BUNDLED_PREFIX = "builtin:"

/** Asset root of the packs compiled into the APK. */
private const val BUNDLED_PACK_ROOT = "ui-packs"

/** Largest base64 payload accepted from pig (~6 MiB of image bytes). */
private const val MAX_IMAGE_B64_CHARS = 8_000_000

/** Most images rendered for one message. */
private const val MAX_IMAGES_PER_MESSAGE = 8

/** Pixel budget for images decoded while rebuilding a transcript (~24 MB). */
private const val HISTORY_IMAGE_PIXEL_BUDGET = 6_000_000L

/** Decoded screenshots kept across rebuilds and page growth (~24 MB). */
private const val HISTORY_IMAGE_CACHE_PIXELS = 6_000_000

/** Messages in the first history page; pulling at the top doubles it. */
private const val HISTORY_PAGE = 40

/** How long a new session may take to replace its runtime before the app treats
 *  whatever the backend reports as the new conversation (~10 s in practice). */
private const val NEW_SESSION_SETTLE_MS = 20_000L

/** Sessions whose widget cards are kept for the next reconnect. */
private const val WIDGET_SNAPSHOT_SESSIONS = 20

/** A card snapshot older than this is not drawn: the extension may have cleared
 *  it while the app was away, and nothing would ever correct it. */
private const val WIDGET_SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000

/** Relay's cap on one history reply. */
private const val HISTORY_MAX = 2000

/** Sessions whose built transcript stays in memory (≈24 MB of images each). */
private const val HISTORY_CACHE_SESSIONS = 2

/** Long edge of transcript images; 1024 px stays readable and cheap. */
private const val TRANSCRIPT_IMAGE_MAX_DIM = 1024

/** Long edge of images sent to a model (Claude's documented limit). */
private const val UPLOAD_IMAGE_MAX_DIM = 1568

/** Target size of an encoded upload before the JPEG quality is lowered. */
private const val UPLOAD_IMAGE_MAX_BYTES = 1_400_000

/** Non-blank entries of an event array (`queue_update` carries plain text). */
private fun JSONArray?.stringValues(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }
