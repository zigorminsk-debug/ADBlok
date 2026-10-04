package com.adblok.app.update

import android.content.Context
import android.util.Log
import com.adblok.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Описание доступного обновления. */
data class UpdateInfo(
    val buildNumber: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
    val pageUrl: String
)

/**
 * Проверяет GitHub Releases на наличие сборки с номером больше текущего
 * и скачивает APK. Все сборки подписаны одним постоянным ключом,
 * поэтому обновление ставится поверх установленного приложения.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    const val REPO = "zigorminsk-debug/ADBlok"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** @return информацию об обновлении или null, если установлена актуальная версия. */
    fun check(): UpdateInfo? {
        val json = runCatching { fetch(API) }.getOrElse {
            Log.w(TAG, "Проверка обновлений не удалась", it)
            return null
        }

        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null

        val tag = root.optString("tag_name")               // build-12
        val latest = tag.substringAfterLast('-').toIntOrNull() ?: return null
        if (latest <= BuildConfig.VERSION_CODE) return null

        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            return UpdateInfo(
                buildNumber = latest,
                versionName = root.optString("name").ifEmpty { tag },
                apkUrl = a.optString("browser_download_url"),
                apkSize = a.optLong("size"),
                notes = root.optString("body").trim(),
                pageUrl = root.optString("html_url")
            )
        }
        return null
    }

    /** Скачивает APK во внутренний кэш. Возвращает файл или null. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit = {}): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "ADBlok-build-${info.buildNumber}.apk")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ADBlok")
            }
            val total = if (info.apkSize > 0) info.apkSize else conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            return target
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки APK", e)
            target.delete()
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ADBlok")
        }
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
