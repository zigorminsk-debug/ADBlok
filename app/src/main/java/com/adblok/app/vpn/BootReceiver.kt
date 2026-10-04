package com.adblok.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.adblok.app.data.BlocklistRepository
import com.adblok.app.data.Prefs

/**
 * Включает защиту автоматически после загрузки устройства
 * (а также после обновления самого приложения).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }

        val prefs = Prefs.get(context)
        if (!prefs.autoStart) {
            Log.i(TAG, "Автозапуск отключён в настройках")
            return
        }
        if (AdVpnService.isRunning) return

        // Разрешение на VPN выдаётся один раз и сохраняется:
        // если prepare() вернул null, согласие уже есть и сервис можно стартовать без UI.
        val needsConsent = runCatching { VpnService.prepare(context) }.getOrNull() != null
        if (needsConsent) {
            Log.w(TAG, "Нет разрешения на VPN — автозапуск пропущен, откройте приложение")
            return
        }

        val pending = goAsync()
        Thread {
            try {
                BlocklistRepository.ensureLoaded(context)
                AdVpnService.start(context)
                Log.i(TAG, "Защита запущена после события ${intent.action}")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось запустить сервис при загрузке", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
