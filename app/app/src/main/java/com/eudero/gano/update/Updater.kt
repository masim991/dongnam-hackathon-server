package com.eudero.gano.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.eudero.gano.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 앱 실행 시 서버 version.json 확인 -> 더 높은 versionCode면 APK 다운로드 후 설치 유도. */
object Updater {
    fun check(a: Activity, manual: Boolean = false) {
        thread {
            try {
                val json = JSONObject(get(BuildConfig.VERSION_URL))
                val code = json.getInt("versionCode")
                if (code > BuildConfig.VERSION_CODE) {
                    val name = json.optString("versionName", code.toString())
                    val apk = json.optString("apkUrl", BuildConfig.VERSION_URL.substringBeforeLast('/') + "/eudero-gano.apk")
                    val notes = json.optString("notes", "")
                    a.runOnUiThread {
                        AlertDialog.Builder(a).setTitle("새 버전 $name")
                            .setMessage("지금 ${BuildConfig.VERSION_NAME} → $name\n$notes")
                            .setPositiveButton("업데이트") { _, _ -> download(a, apk) }
                            .setNegativeButton("나중에", null).show()
                    }
                } else if (manual) a.runOnUiThread { Toast.makeText(a, "이미 최신 버전이에요", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                if (manual) a.runOnUiThread { Toast.makeText(a, "업데이트를 확인하지 못했어요. 인터넷을 확인해줘", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(a: Activity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "설치 허용을 켠 뒤 다시 업데이트를 눌러줘", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        val dlg = AlertDialog.Builder(a).setTitle("다운로드 중").setMessage("0%").setCancelable(false).show()
        thread {
            try {
                val f = File(a.cacheDir, "apk").apply { mkdirs() }.let { File(it, "update.apk") }
                val c = URL(url).openConnection() as HttpURLConnection
                val total = c.contentLength.toLong()
                c.inputStream.use { i -> f.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024); var done = 0L; var n: Int
                    while (i.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n); done += n
                        if (total > 0) a.runOnUiThread { dlg.setMessage("${done * 100 / total}%") }
                    }
                } }
                a.runOnUiThread {
                    dlg.dismiss()
                    val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
                    a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                a.runOnUiThread { dlg.dismiss(); Toast.makeText(a, "내려받지 못했어요. 다시 시도해줘", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
