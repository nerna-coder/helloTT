package com.hellott.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var downloadModBtn: Button
    private lateinit var downloadPluginBtn: Button
    private lateinit var downloadBothBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var logText: TextView

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // apkw.ru base
    private val APKW_MOD = "https://apkw.ru/en/download/tik-tok-mod/"
    private val APKW_PLUGIN = "https://apkw.ru/en/download/tik-tok-plugin/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        versionText = findViewById(R.id.versionText)
        downloadModBtn = findViewById(R.id.downloadModBtn)
        downloadPluginBtn = findViewById(R.id.downloadPluginBtn)
        downloadBothBtn = findViewById(R.id.downloadBothBtn)
        progressBar = findViewById(R.id.progressBar)
        logText = findViewById(R.id.logText)

        downloadModBtn.setOnClickListener { startDownload("mod") }
        downloadPluginBtn.setOnClickListener { startDownload("plugin") }
        downloadBothBtn.setOnClickListener {
            startDownload("mod")
            startDownload("plugin")
        }

        log("🐆 helloTT готов к работе")
        checkVersions()
    }

    private fun checkVersions() {
        scope.launch {
            log("Проверяю версии...")
            try {
                val modVersion = withContext(Dispatchers.IO) { fetchVersion(APKW_MOD) }
                val pluginVersion = withContext(Dispatchers.IO) { fetchVersion(APKW_PLUGIN) }
                versionText.text = "Мод: $modVersion | Плагин: $pluginVersion"
                log("✓ Версии получены")
            } catch (e: Exception) {
                log("⚠ Не удалось получить версии: ${e.message}")
            }
        }
    }

    private fun fetchVersion(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
            .build()
        val body = client.newCall(req).execute().body?.string() ?: return "?"
        // Extract version number from page title or filename
        val versionRegex = Regex("""(\d+\.\d+[\d.]+)""")
        return versionRegex.find(body)?.value ?: "?"
    }

    private fun startDownload(type: String) {
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        log("🐆 Начинаю загрузку: $type")

        scope.launch {
            try {
                val pageUrl = if (type == "mod") APKW_MOD else APKW_PLUGIN
                val downloadUrl = withContext(Dispatchers.IO) { resolveDownloadUrl(pageUrl) }
                if (downloadUrl == null) {
                    log("❌ Не удалось найти ссылку для $type")
                    return@launch
                }
                log("✓ Ссылка найдена, загружаю...")
                enqueueDownload(downloadUrl, type)
            } catch (e: Exception) {
                log("❌ Ошибка: ${e.message}")
            } finally {
                progressBar.visibility = View.GONE
                setButtonsEnabled(true)
            }
        }
    }

    private fun resolveDownloadUrl(startUrl: String): String? {
        // Step 1: get the main page, find the universal APK link
        val req1 = Request.Builder()
            .url(startUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
            .build()
        val body1 = client.newCall(req1).execute().body?.string() ?: return null

        // Find href to gate page (contains "universal" or "plugin" text)
        val hrefRegex = Regex("""href="(https?://[^"]+apkw\.ru[^"]+)"""")
        val gateUrl = hrefRegex.findAll(body1)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("download") } ?: return null

        // Step 2: follow gate → fylio redirect
        val req2 = Request.Builder()
            .url(gateUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
            .header("Referer", startUrl)
            .build()
        val resp2 = client.newCall(req2).execute()
        val finalUrl = resp2.request.url.toString()
        val body2 = resp2.body?.string() ?: return null

        // Step 3: from fylio page, find noreferrer link (actual APK mirror)
        val noreferrerRegex = Regex("""rel="noreferrer"[^>]*href="([^"]+)"""")
        val apkUrl = noreferrerRegex.find(body2)?.groupValues?.get(1)

        return apkUrl ?: finalUrl
    }

    private fun enqueueDownload(url: String, type: String) {
        val fileName = "helloTT_${type}_${System.currentTimeMillis()}.apk"
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("helloTT — $type")
            .setDescription("Загрузка TikTok $type...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")

        val id = dm.enqueue(request)
        log("📥 Загрузка запущена: $fileName")
        log("Смотри уведомления ↑")

        // Listen for completion
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId == id) {
                    unregisterReceiver(this)
                    runOnUiThread {
                        log("✅ $type загружен в Downloads!")
                        offerInstall(fileName)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun offerInstall(fileName: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun log(msg: String) {
        val current = logText.text.toString()
        val lines = current.split("\n").takeLast(20)
        logText.text = (lines + msg).joinToString("\n")
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        downloadModBtn.isEnabled = enabled
        downloadPluginBtn.isEnabled = enabled
        downloadBothBtn.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
