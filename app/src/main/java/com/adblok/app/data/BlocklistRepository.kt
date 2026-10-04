package com.adblok.app.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Хранит и обновляет списки блокировки.
 * Поддерживаются форматы: hosts (0.0.0.0 domain.com) и "просто домены" в строке.
 */
object BlocklistRepository {

    private const val TAG = "BlocklistRepo"
    private const val CACHE_FILE = "blocklist.txt"

    private val domains = AtomicReference<Set<String>>(emptySet())
    private val whitelist = AtomicReference<Set<String>>(emptySet())

    @Volatile
    private var loaded = false

    val size: Int get() = domains.get().size

    /** Загружает список в память (из кэша либо из встроенного списка). */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val cache = File(context.filesDir, CACHE_FILE)
        val set = HashSet<String>(100_000)
        if (cache.exists()) {
            cache.bufferedReader().useLines { lines -> lines.forEach { parseLine(it)?.let(set::add) } }
        } else {
            runCatching {
                context.assets.open("default_blocklist.txt").bufferedReader().useLines { lines ->
                    lines.forEach { parseLine(it)?.let(set::add) }
                }
            }.onFailure { Log.w(TAG, "Нет встроенного списка", it) }
        }
        applyUserRules(context, set)
        domains.set(set)
        whitelist.set(Prefs.get(context).whitelist.toSet())
        Prefs.get(context).rulesCount = set.size
        loaded = true
        Log.i(TAG, "Загружено правил: ${set.size}")
    }

    /** Перечитывает пользовательские правила без скачивания списков. */
    fun reloadUserRules(context: Context) {
        val set = HashSet(domains.get())
        applyUserRules(context, set)
        domains.set(set)
        whitelist.set(Prefs.get(context).whitelist.toSet())
    }

    private fun applyUserRules(context: Context, set: MutableSet<String>) {
        val prefs = Prefs.get(context)
        prefs.userBlocklist.forEach { set.add(it) }
        prefs.whitelist.forEach { set.remove(it) }
    }

    /**
     * Скачивает все источники и сохраняет объединённый список.
     * @return количество правил
     */
    fun update(context: Context, onProgress: (String) -> Unit = {}): Int {
        val prefs = Prefs.get(context)
        val set = HashSet<String>(200_000)
        for (url in prefs.sources) {
            onProgress(url)
            try {
                download(url) { line -> parseLine(line)?.let(set::add) }
            } catch (e: Exception) {
                Log.w(TAG, "Источник недоступен: $url", e)
            }
        }
        if (set.isEmpty()) return domains.get().size

        File(context.filesDir, CACHE_FILE).bufferedWriter().use { w ->
            set.forEach { w.write(it); w.newLine() }
        }
        applyUserRules(context, set)
        domains.set(set)
        whitelist.set(prefs.whitelist.toSet())
        prefs.rulesCount = set.size
        prefs.lastUpdate = System.currentTimeMillis()
        loaded = true
        return set.size
    }

    private fun download(url: String, consumer: (String) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ADBlok/1.0")
        }
        try {
            conn.inputStream.bufferedReader().use { r: BufferedReader ->
                r.lineSequence().forEach(consumer)
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Разбирает строку hosts-файла, "голого" домена или правила Adblock-синтаксиса.
     * Поддерживается: "0.0.0.0 domain", "domain", "||domain^", "||domain^$third-party".
     * Косметические правила (##, #@#, #?#) и исключения (@@) игнорируются —
     * DNS-фильтр их применить не может.
     */
    private fun parseLine(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return null
        if (line.startsWith("@@")) return null
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#\$#")) return null

        if (line.startsWith("||")) {
            // ||ads.example.com^$third-party  ->  ads.example.com
            line = line.removePrefix("||")
            line = line.substringBefore('^').substringBefore('$').substringBefore('/')
            if (line.contains('*')) return null
            return normalize(line)
        }
        if (line.startsWith("|") || line.startsWith("/") || line.contains('*')) return null

        val hash = line.indexOf('#')
        if (hash > 0) line = line.substring(0, hash).trim()
        val parts = line.split(Regex("\\s+"))
        val domain = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1") -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        return normalize(domain)
    }

    private fun normalize(input: String): String? {
        val domain = input.lowercase().removeSuffix(".").removePrefix("www.")
        if (domain.isEmpty() || domain == "localhost" || domain == "localhost.localdomain" ||
            domain == "broadcasthost" || domain == "0.0.0.0" || !domain.contains('.')
        ) return null
        if (!domain.matches(Regex("^[a-z0-9._\\-]+$"))) return null
        return domain
    }

    /** Блокируется ли домен (с учётом поддоменов и белого списка). */
    fun isBlocked(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        if (h.isEmpty()) return false
        val wl = whitelist.get()
        val set = domains.get()
        if (set.isEmpty()) return false
        var current = h
        while (true) {
            if (wl.contains(current)) return false
            if (set.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
            if (!current.contains('.')) return false
        }
    }
}
