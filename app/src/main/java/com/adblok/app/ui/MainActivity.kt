package com.adblok.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.adblok.app.R
import com.adblok.app.data.BlocklistRepository
import com.adblok.app.data.Prefs
import com.adblok.app.databinding.ActivityMainBinding
import com.adblok.app.update.Installer
import com.adblok.app.update.UpdateChecker
import com.adblok.app.update.UpdateInfo
import com.adblok.app.update.UpdateWorker
import com.adblok.app.vpn.AdVpnService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) AdVpnService.start(this) else refresh()
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs.get(this)

        lifecycleScope.launch(Dispatchers.IO) {
            BlocklistRepository.ensureLoaded(this@MainActivity)
            withContext(Dispatchers.Main) { refresh() }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        binding.switchProtection.setOnClickListener {
            if (binding.switchProtection.isChecked) enableVpn() else AdVpnService.stop(this)
        }

        binding.switchAutoStart.isChecked = prefs.autoStart
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
            if (checked && VpnService.prepare(this) != null) {
                Snackbar.make(binding.root, R.string.autostart_need_permission, Snackbar.LENGTH_LONG).show()
            }
        }

        binding.switchAutoUpdate.isChecked = prefs.autoUpdate
        UpdateWorker.schedule(this, prefs.autoUpdate)
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            prefs.autoUpdate = checked
            UpdateWorker.schedule(this, checked)
        }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate(manual = true) }

        handleUpdateIntent(intent)
        if (prefs.autoUpdate) checkUpdate(manual = false)

        binding.btnUpdate.setOnClickListener { updateLists() }
        binding.btnWhitelist.setOnClickListener { editList(R.string.whitelist, prefs.whitelist) { prefs.whitelist = it } }
        binding.btnUserRules.setOnClickListener { editList(R.string.user_rules, prefs.userBlocklist) { prefs.userBlocklist = it } }
        binding.btnResetStats.setOnClickListener { prefs.resetStats(); refresh() }

        val dnsOptions = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.14", "77.88.8.8")
        binding.spinnerDns.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dnsOptions)
        binding.spinnerDns.setSelection(dnsOptions.indexOf(prefs.upstreamDns).coerceAtLeast(0))
        binding.spinnerDns.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.upstreamDns = dnsOptions[pos]
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        binding.textVersion.text = getString(R.string.version_fmt, getString(R.string.build_number))
    }

    companion object {
        const val ACTION_INSTALL_UPDATE = "com.adblok.app.INSTALL_UPDATE"
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_BUILD = "build"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    /** Открыли приложение по уведомлению «доступна новая версия». */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_UPDATE) return
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: return
        val build = intent.getIntExtra(EXTRA_BUILD, 0)
        val apk = File(path)
        if (!apk.exists()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, build))
            .setPositiveButton(R.string.update_dialog_install) { _, _ -> startInstall(apk) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val info = UpdateChecker.check()
            prefs.lastUpdateCheck = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                if (manual) binding.progress.visibility = android.view.View.GONE
                when {
                    info != null -> offerUpdate(info)
                    manual -> Snackbar.make(binding.root, R.string.no_update, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun offerUpdate(info: UpdateInfo) {
        val size = if (info.apkSize > 0) " · %.1f МБ".format(info.apkSize / 1024f / 1024f) else ""
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, info.buildNumber))
            .setMessage(info.notes.ifEmpty { info.versionName } + size)
            .setPositiveButton(R.string.update_dialog_install) { _, _ -> downloadAndInstall(info) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val apk = UpdateChecker.download(this@MainActivity, info) { pct ->
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.textStatus.text = getString(R.string.update_downloading, pct)
                }
            }
            withContext(Dispatchers.Main) {
                binding.progress.visibility = android.view.View.GONE
                refresh()
                if (apk == null) {
                    Snackbar.make(binding.root, R.string.update_download_failed, Snackbar.LENGTH_LONG).show()
                } else {
                    startInstall(apk)
                }
            }
        }
    }

    private fun startInstall(apk: File) {
        if (!Installer.canInstall(this)) {
            Snackbar.make(binding.root, R.string.update_need_permission, Snackbar.LENGTH_LONG).show()
            startActivity(Installer.permissionIntent(this))
            pendingApk = apk
            return
        }
        Installer.install(this, apk)
    }

    private var pendingApk: File? = null

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AdVpnService.ACTION_STATE_CHANGED)
            addAction(AdVpnService.ACTION_STATS_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        refresh()
        pendingApk?.let { apk ->
            if (Installer.canInstall(this)) {
                pendingApk = null
                if (apk.exists()) Installer.install(this, apk)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    private fun enableVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else AdVpnService.start(this)
    }

    private fun updateLists() {
        binding.btnUpdate.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val count = BlocklistRepository.update(this@MainActivity) { url ->
                lifecycleScope.launch(Dispatchers.Main) { binding.textStatus.text = getString(R.string.downloading, url) }
            }
            withContext(Dispatchers.Main) {
                binding.progress.visibility = android.view.View.GONE
                binding.btnUpdate.isEnabled = true
                Snackbar.make(binding.root, getString(R.string.updated, count), Snackbar.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun editList(titleRes: Int, current: List<String>, save: (List<String>) -> Unit) {
        val input = EditText(this).apply {
            setText(current.joinToString("\n"))
            setPadding(48, 32, 48, 32)
            hint = getString(R.string.domains_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                save(input.text.toString().lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() })
                BlocklistRepository.reloadUserRules(this)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val on = AdVpnService.isRunning
        binding.switchProtection.isChecked = on
        binding.textStatus.setText(if (on) R.string.status_on else R.string.status_off)
        binding.textBlocked.text = prefs.blockedCount.toString()
        binding.textAllowed.text = prefs.allowedCount.toString()
        binding.textRules.text = prefs.rulesCount.toString()
        binding.textUpdated.text = if (prefs.lastUpdate == 0L) getString(R.string.never)
        else DateUtils.getRelativeTimeSpanString(prefs.lastUpdate).toString()
    }
}
