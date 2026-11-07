package app.memento.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject

private const val APP_WEB_VERSION = "35"

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var rootView: FrameLayout
    private lateinit var banner: TextView
    private lateinit var bannerScroll: ScrollView
    private lateinit var importPanel: FrameLayout
    private lateinit var importText: TextView
    private lateinit var importBar: ProgressBar
    private val importUiHandler = Handler(Looper.getMainLooper())
    private val hideImportPanel = Runnable { if (::importPanel.isInitialized) importPanel.visibility = android.view.View.GONE }
    private val bridge by lazy { Bridge(this) }
    private var pendingAudioCallback: String? = null
    private var pendingBatchAudioCallback: String? = null
    private var pendingFolderCallback: String? = null
    private var pendingSystemCallback: String? = null
    private var pendingMicCallback: String? = null
    @Volatile private var pageReady = false
    private val appRoot by lazy { File(filesDir, "memento_app") }
    private val assetServer by lazy {
        DiskPathHandler(appRoot, listOf(filesDir, cacheDir, getExternalFilesDir(null) ?: filesDir))
    }
    private val diag = StringBuilder()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUI()
            MementoApp.models.events.clear()
            MementoApp.models.events.add { e, p ->
                fireEvent(e, p)
                if (e == "gigaam-ready") bridge.resumePendingProcessing()
            }
            requestNotifAndMic()
            val prevCrash = MementoApp.takeLastCrash()
            if (prevCrash != null) note("CRASH on previous launch:\n" + prevCrash)
            bridge.resumePendingProcessing()
            start()
        } catch (e: Throwable) { note("FATAL: " + stack(e)) }
    }

    private fun buildUI() {
        val root = FrameLayout(this)
        rootView = root
        root.setBackgroundColor(Color.parseColor("#0B0D12"))
        banner = TextView(this).apply {
            setTextColor(0xFFFF6B6B.toInt())
            setBackgroundColor(0xCC000000.toInt())
            textSize = 11f
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
            visibility = android.view.View.GONE
        }
        bannerScroll = ScrollView(this).apply {
            addView(banner)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(-1, dp(200), Gravity.BOTTOM)
        }
        importPanel = FrameLayout(this).apply {
            setBackgroundColor(0xFFF7F7F7.toInt())
            elevation = dp(12).toFloat()
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(-1, dp(54), Gravity.BOTTOM)
        }
        importText = TextView(this).apply {
            setTextColor(0xFF242424.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(4))
            layoutParams = FrameLayout.LayoutParams(-1, dp(50), Gravity.TOP)
        }
        importBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            val layers = LayerDrawable(arrayOf(
                ColorDrawable(0xFFD9D9D9.toInt()),
                ClipDrawable(ColorDrawable(0xFFFF963D.toInt()), Gravity.START, ClipDrawable.HORIZONTAL)
            ))
            layers.setId(0, android.R.id.background)
            layers.setId(1, android.R.id.progress)
            progressDrawable = layers
            layoutParams = FrameLayout.LayoutParams(-1, dp(5), Gravity.BOTTOM)
        }
        importPanel.addView(importText)
        importPanel.addView(importBar)
        root.addView(importPanel)
        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callbackId = pendingAudioCallback
        pendingAudioCallback = null
        if (callbackId == null) return@registerForActivityResult
        if (uri == null) {
            reply(callbackId, JSONObject().put("value", JSONObject.NULL))
            return@registerForActivityResult
        }
        Thread {
            try {
                val display = queryDisplayName(uri) ?: ("audio-" + System.currentTimeMillis())
                val dir = File(filesDir, "imports").apply { mkdirs() }
                val target = uniqueFile(dir, display)
                contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 65536) }
                }
                val audio = AudioDecoder.inspect(target)
                val info = JSONObject()
                    .put("sourcePath", target.absolutePath)
                    .put("source_path", target.absolutePath)
                    .put("path", target.absolutePath)
                    .put("filePath", target.absolutePath)
                    .put("title", target.name.substringBeforeLast('.'))
                    .put("size_bytes", target.length())
                    .put("duration_seconds", audio.durationSeconds)
                    .put("mime_type", audio.mime)
                Handler(Looper.getMainLooper()).post { reply(callbackId, JSONObject().put("value", info)) }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    reply(callbackId, JSONObject().put("__mementoError", e.message ?: "import failed"))
                }
            }
        }.start()
    }

    fun pickAudio(callbackId: String) {
        pendingAudioCallback = callbackId
        runOnUiThread { audioPicker.launch(arrayOf("audio/*", "application/ogg")) }
    }

    private val batchAudioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val callbackId = pendingBatchAudioCallback
        pendingBatchAudioCallback = null
        if (callbackId == null) return@registerForActivityResult
        if (uris.isEmpty()) {
            reply(callbackId, JSONObject().put("value", org.json.JSONArray()))
            return@registerForActivityResult
        }
        Thread {
            try {
                val result = org.json.JSONArray()
                val dir = File(filesDir, "imports").apply { mkdirs() }
                for (uri in uris) {
                    val display = queryDisplayName(uri) ?: ("audio-" + System.currentTimeMillis())
                    val target = uniqueFile(dir, display)
                    contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { out -> input.copyTo(out, 65536) }
                    }
                    val audio = AudioDecoder.inspect(target)
                    result.put(JSONObject().put("sourcePath", target.absolutePath)
                        .put("source_path", target.absolutePath).put("path", target.absolutePath)
                        .put("filePath", target.absolutePath)
                        .put("title", target.name.substringBeforeLast('.'))
                        .put("size_bytes", target.length()).put("duration_seconds", audio.durationSeconds)
                        .put("mime_type", audio.mime))
                }
                Handler(Looper.getMainLooper()).post { reply(callbackId, JSONObject().put("value", result)) }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    reply(callbackId, JSONObject().put("__mementoError", e.message ?: "Не удалось открыть аудиофайлы"))
                }
            }
        }.start()
    }

    fun pickAudioBatch(callbackId: String) {
        pendingBatchAudioCallback = callbackId
        runOnUiThread { batchAudioPicker.launch(arrayOf("audio/*", "application/ogg")) }
    }

    private val recordingFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val callbackId = pendingFolderCallback
        pendingFolderCallback = null
        if (callbackId == null) return@registerForActivityResult
        if (uri == null) {
            reply(callbackId, JSONObject().put("value", JSONObject.NULL))
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            MementoApp.prefs.set("rec_folder_uri", uri.toString())
            MementoApp.prefs.set("rec_folder_label", folderLabel(uri))
            reply(callbackId, JSONObject().put("value", MementoApp.recorder.recordingFolderLabel()))
        } catch (e: Throwable) {
            reply(callbackId, JSONObject().put("__mementoError", e.message ?: "Не удалось выбрать папку"))
        }
    }

    fun pickRecordingFolder(callbackId: String) {
        pendingFolderCallback = callbackId
        runOnUiThread { recordingFolderPicker.launch(null) }
    }

    private fun folderLabel(uri: Uri): String {
        val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val relative = id.substringAfter(':', id).trim('/')
        return if (relative.isBlank()) "Память устройства" else relative
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callbackId = pendingSystemCallback
        pendingSystemCallback = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            RecordingService.startProjection(this, result.resultCode, result.data!!, "Memento")
            Thread {
                var tries = 0
                while (MementoApp.projection == null && tries < 60) {
                    Thread.sleep(50)
                    tries++
                }
                val granted = MementoApp.projection != null
                if (!granted) note("PROJECTION: сервис не смог получить доступ к системному звуку")
                if (callbackId != null) reply(callbackId, JSONObject().put("value", granted))
            }.start()
        } else if (callbackId != null) {
            reply(callbackId, JSONObject().put("value", false))
        }
    }

    fun requestSystemAudio(callbackId: String) {
        if (MementoApp.projection != null) {
            reply(callbackId, JSONObject().put("value", true))
            return
        }
        pendingSystemCallback = callbackId
        runOnUiThread {
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            } catch (e: Throwable) {
                reply(callbackId, JSONObject().put("__mementoError", e.message ?: "projection unavailable"))
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var file = File(dir, safe)
        var index = 1
        while (file.exists()) {
            val base = safe.substringBeforeLast('.')
            val ext = safe.substringAfterLast('.', "")
            file = File(dir, base + "-" + index + (if (ext.isEmpty()) "" else "." + ext))
            index++
        }
        return file
    }

    private fun note(msg: String) {
        synchronized(diag) { diag.append(msg).append("\n---\n") }
        AppLog.error("mobile", msg)
        android.util.Log.e("MEMENTO", msg)
    }

    private fun importStage(stage: String): String = when (stage) {
        "copying" -> "Подготовка файла"
        "decoding" -> "Обработка аудио"
        "transcribing" -> "Расшифровка речи"
        "diarizing" -> "Определение участников"
        "summarizing" -> "Создание саммари"
        "complete" -> "Встреча готова"
        else -> "Обработка встречи"
    }

    fun showImportProgress(title: String, stage: String, progress: Double) {
        runOnUiThread {
            importUiHandler.removeCallbacks(hideImportPanel)
            val percent = (progress.coerceIn(0.0, 1.0) * 100).toInt()
            importText.text = "${importStage(stage)} · $percent% · ${title.take(60)}"
            importBar.progress = (progress.coerceIn(0.0, 1.0) * importBar.max).toInt()
            importPanel.visibility = android.view.View.VISIBLE
        }
    }

    fun showImportReady(title: String) {
        runOnUiThread {
            importUiHandler.removeCallbacks(hideImportPanel)
            importText.text = "Загруженная встреча готова · ${title.take(60)}"
            importBar.progress = importBar.max
            importPanel.visibility = android.view.View.VISIBLE
            importUiHandler.postDelayed(hideImportPanel, 5500)
        }
    }

    fun showImportFailure(title: String) {
        runOnUiThread {
            importUiHandler.removeCallbacks(hideImportPanel)
            importText.text = "Не удалось обработать файл · ${title.take(60)}"
            importBar.progress = 0
            importPanel.visibility = android.view.View.VISIBLE
            importUiHandler.postDelayed(hideImportPanel, 6500)
        }
    }

    fun feedbackLogBundle(): String {
        AppLog.info("feedback", "Feedback log bundle requested")
        return AppLog.feedbackBundle()
    }

    private fun stack(e: Throwable): String {
        val sb = StringBuilder().append(e.javaClass.name).append(": ").append(e.message).append("\n")
        for (st in e.stackTrace.take(12)) sb.append("  at ").append(st).append("\n")
        return sb.toString()
    }

    private fun start() {
        Thread {
            try {
                extractIfNeeded()
                Handler(Looper.getMainLooper()).post { setupWebView() }
            } catch (e: Throwable) { note("EXTRACT FAILED: " + stack(e)) }
        }.start()
    }

    private fun extractIfNeeded() {
        val marker = File(appRoot, ".extracted")
        val files = appRoot.listFiles()
        if (marker.exists() && marker.readText().trim() == APP_WEB_VERSION && files != null && files.size > 50) return
        if (appRoot.exists() && !appRoot.deleteRecursively()) {
            throw IllegalStateException("cannot clear ${appRoot.absolutePath}")
        }
        if (!appRoot.mkdirs() && !appRoot.isDirectory) {
            throw IllegalStateException("cannot create ${appRoot.absolutePath}")
        }
        assets.open("app.zip").use { a ->
            val zip = java.util.zip.ZipInputStream(a)
            var entry: java.util.zip.ZipEntry?
            var count = 0
            while (true) {
                entry = zip.nextEntry ?: break
                val rawName = entry.name.replace("\\", "/")
                if (rawName.contains("..")) { zip.closeEntry(); continue }
                val parts = rawName.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.isEmpty()) { zip.closeEntry(); continue }
                var dir = appRoot
                for (i in 0 until parts.size - 1) {
                    dir = File(dir, parts[i])
                    if (!dir.isDirectory) {
                        if (dir.exists() && !dir.deleteRecursively()) {
                            throw IllegalStateException("cannot replace ${dir.absolutePath}")
                        }
                        if (!dir.mkdirs() && !dir.isDirectory) {
                            throw IllegalStateException("cannot create ${dir.absolutePath}")
                        }
                    }
                }
                val f = File(dir, parts.last())
                val isDirectory = entry.isDirectory || rawName.endsWith("/")
                if (isDirectory) {
                    if (!f.isDirectory) {
                        if (f.exists() && !f.deleteRecursively()) {
                            throw IllegalStateException("cannot replace ${f.absolutePath}")
                        }
                        if (!f.mkdirs() && !f.isDirectory) {
                            throw IllegalStateException("cannot create ${f.absolutePath}")
                        }
                    }
                } else {
                    if (f.isDirectory && !f.deleteRecursively()) {
                        throw IllegalStateException("cannot replace ${f.absolutePath}")
                    }
                    f.outputStream().use { out -> zip.copyTo(out, 65536) }
                    count++
                }
                zip.closeEntry()
            }
            if (count < 300) throw IllegalStateException("extracted only $count files")
        }
        marker.writeText(APP_WEB_VERSION)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.addJavascriptInterface(JS(), "MementoBridge")
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                val level = cm.messageLevel()
                val source = cm.sourceId()
                val lineNum = cm.lineNumber()
                val message = cm.message()
                if (level == ConsoleMessage.MessageLevel.ERROR) {
                    note("JS $source:$lineNum -> $message")
                } else {
                    if (level == ConsoleMessage.MessageLevel.WARNING) AppLog.warn("frontend", message)
                    android.util.Log.d("MEMENTO", "JS $level: $message")
                }
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != "memento.app") return null
                return try {
                    assetServer.respond(url, request.requestHeaders)
                } catch (e: Throwable) {
                    note("ASSET: " + stack(e))
                    null
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    note("PAGE ERROR ${error?.errorCode}: ${error?.description} url=" + request?.url)
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                AppLog.info("frontend", "Page finished: ${url?.substringBefore('?')}")
                android.util.Log.i("MEMENTO", "page finished: $url")
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                note("WEBVIEW RENDER PROCESS GONE crashed=" + detail?.didCrash() + " url=" + view?.url)
                return true
            }
        }
        rootView.addView(web, 0, ViewGroup.LayoutParams(-1, -1))
        val index = File(appRoot, "index.html")
        if (!index.isFile) {
            note("index.html не найден: " + index.absolutePath)
        } else {
            val html = try {
                index.readText()
            } catch (e: Throwable) {
                note("READ index.html: " + stack(e))
                ""
            }
            if (html.isNotEmpty()) {
                web.loadDataWithBaseURL("https://memento.app/", html, "text/html", "utf-8", null)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!pageReady) {
                note(
                    "WebView не отрисовал интерфейс за 18 с.\nurl=" + web.url +
                        "\nwebview=" + android.webkit.WebView.getCurrentWebViewPackage()?.versionName
                )
            }
        }, 18000)
    }

    inner class JS {
        @JavascriptInterface
        fun ready() {
            pageReady = true
        }

        @JavascriptInterface
        fun post(msg: String) {
            try { bridge.handle(msg) }
            catch (e: Throwable) { note("BRIDGE: " + stack(e)) }
        }
    }

    fun reply(callbackId: String, content: JSONObject) {
        val sb = StringBuilder("window.__mementoEmit&&window.__mementoEmit({callbackId:")
            .append(JSONObject.quote(callbackId))
            .append(",content:")
            .append(content.toString())
            .append("})")
        runOnUiThread {
            if (::web.isInitialized && !isFinishing && !isDestroyed) {
                web.evaluateJavascript(sb.toString(), null)
            }
        }
    }

    fun fireEvent(event: String, payload: JSONObject) {
        val sb = StringBuilder("window.__mementoFire&&window.__mementoFire(")
            .append(JSONObject.quote(event))
            .append(",")
            .append(payload.toString())
            .append(",null)")
        runOnUiThread {
            if (::web.isInitialized && !isFinishing && !isDestroyed) {
                web.evaluateJavascript(sb.toString(), null)
            }
        }
    }

    fun openUrl(u: String?) {
        if (u.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {}
    }

    fun showAnalyticsReport(file: File) {
        if (!file.isFile) return
        val contents = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { "Не удалось прочитать отчёт: ${it.message.orEmpty()}" }
        runOnUiThread {
            val text = TextView(this).apply {
                setTextColor(0xFF202124.toInt())
                setTextIsSelectable(true)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(20), dp(12), dp(20), dp(20))
                text = contents
            }
            val scroll = ScrollView(this).apply { addView(text) }
            AlertDialog.Builder(this)
                .setTitle("Аналитика встречи")
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .show()
        }
    }

    fun showDownloadComplete() {
        runOnUiThread {
            Toast.makeText(this, "Аналитика сохранена в папку «Загрузки»", Toast.LENGTH_LONG).show()
        }
    }

    fun openFileLocation(file: File) {
        runOnUiThread {
            val tree = MementoApp.prefs.get("rec_folder_uri")?.takeIf { it.isNotBlank() }
            val intent = if (tree != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(tree)).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            } else {
                Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            }
            runCatching { startActivity(intent) }.onFailure {
                runCatching { startActivity(Intent(Intent.ACTION_GET_CONTENT).setType("audio/*")) }
            }
        }
    }

    fun requestMic(callbackId: String? = null) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (callbackId != null) reply(callbackId, JSONObject().put("value", true))
            return
        }
        pendingMicCallback = callbackId
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val cb = pendingMicCallback
            pendingMicCallback = null
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (cb != null) reply(cb, JSONObject().put("value", granted))
        }
    }

    private fun requestNotifAndMic() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.RECORD_AUDIO)
        }
        if (req.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, req.toTypedArray(), 2)
        }
    }

    override fun onBackPressed() {
        if (!::web.isInitialized) {
            super.onBackPressed()
            return
        }
        web.evaluateJavascript("window.__mementoBack?window.__mementoBack():false") { result ->
            if (result != "true") {
                moveTaskToBack(true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::web.isInitialized) web.destroy()
    }

    override fun onStart() {
        super.onStart()
        appForeground = true
    }

    override fun onStop() {
        appForeground = false
        super.onStop()
    }

    companion object {
        @Volatile private var appForeground = false
        fun isAppForeground(): Boolean = appForeground
    }
}
