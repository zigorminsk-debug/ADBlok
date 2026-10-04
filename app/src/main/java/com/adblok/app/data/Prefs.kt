package com.adblok.app.data

import android.content.Context
import android.content.SharedPreferences

/** Простое хранилище настроек и счётчиков. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("adblok", Context.MODE_PRIVATE)

    var upstreamDns: String
        get() = sp.getString(KEY_DNS, "1.1.1.1") ?: "1.1.1.1"
        set(value) = sp.edit().putString(KEY_DNS, value).apply()

    /** Список источников hosts-файлов (по одному URL в строке). */
    var sources: List<String>
        get() = (sp.getString(KEY_SOURCES, null) ?: DEFAULT_SOURCES.joinToString("\n"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_SOURCES, value.joinToString("\n")).apply()

    /** Пользовательские исключения (белый список). */
    var whitelist: List<String>
        get() = (sp.getString(KEY_WHITELIST, "") ?: "")
            .lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_WHITELIST, value.joinToString("\n")).apply()

    /** Пользовательские правила блокировки. */
    var userBlocklist: List<String>
        get() = (sp.getString(KEY_USERBLOCK, "") ?: "")
            .lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_USERBLOCK, value.joinToString("\n")).apply()

    /** Включать защиту автоматически после перезагрузки устройства. */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, true)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** Автоматически проверять и предлагать новые сборки приложения. */
    var autoUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTOUPDATE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTOUPDATE, value).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK, value).apply()

    var blockedCount: Long
        get() = sp.getLong(KEY_BLOCKED, 0L)
        set(value) = sp.edit().putLong(KEY_BLOCKED, value).apply()

    var allowedCount: Long
        get() = sp.getLong(KEY_ALLOWED, 0L)
        set(value) = sp.edit().putLong(KEY_ALLOWED, value).apply()

    var rulesCount: Int
        get() = sp.getInt(KEY_RULES, 0)
        set(value) = sp.edit().putInt(KEY_RULES, value).apply()

    var lastUpdate: Long
        get() = sp.getLong(KEY_LAST_UPDATE, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE, value).apply()

    fun resetStats() {
        sp.edit().putLong(KEY_BLOCKED, 0L).putLong(KEY_ALLOWED, 0L).apply()
    }

    companion object {
        private const val KEY_DNS = "upstream_dns"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_AUTOUPDATE = "auto_update"
        private const val KEY_LAST_CHECK = "last_update_check"
        private const val KEY_SOURCES = "sources"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_USERBLOCK = "user_blocklist"
        private const val KEY_BLOCKED = "blocked_count"
        private const val KEY_ALLOWED = "allowed_count"
        private const val KEY_RULES = "rules_count"
        private const val KEY_LAST_UPDATE = "last_update"

        val DEFAULT_SOURCES = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://adaway.org/hosts.txt",
            "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_trackers_justdomains.txt",
            // Фильтр AdGuard DNS (Adblock-синтаксис, берутся только правила ||domain^)
            "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/gh-pages/Filters/filter.txt",
            // RU AdList: RU/UA/KZ рекламные сети, попапы и видео-виджеты
            "https://easylist-downloads.adblockplus.org/ruadlist.txt"
        )

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }
    }
}
