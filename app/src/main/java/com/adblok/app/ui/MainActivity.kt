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
import com.adblok.app.vpn.AdVpnService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AdVpnService.ACTION_STATE_CHANGED)
            addAction(AdVpnService.ACTION_STATS_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        refresh()
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
