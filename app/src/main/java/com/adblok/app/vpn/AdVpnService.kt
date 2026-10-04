package com.adblok.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.adblok.app.R
import com.adblok.app.data.BlocklistRepository
import com.adblok.app.data.Prefs
import com.adblok.app.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный VPN, который перехватывает только DNS-трафик и отвечает NXDOMAIN
 * на запросы рекламных/трекинговых доменов. Остальные запросы проксируются
 * к вышестоящему DNS-серверу.
 */
class AdVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return
        BlocklistRepository.ensureLoaded(this)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            .setMtu(MTU)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        runCatching { builder.addDisallowedApplication(packageName) }

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "Не удалось поднять VPN-интерфейс")
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, buildNotification())
        running.set(true)
        isRunning = true
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))

        worker = Thread({ loop() }, "adblok-dns").apply { isDaemon = true; start() }
    }

    private fun loop() {
        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(MTU)
        try {
            while (running.get()) {
                val len = input.read(buffer)
                if (len <= 0) continue
                val packet = buffer.copyOf(len)
                if (IpPacket.version(packet) != 4) continue
                if (IpPacket.protocol(packet) != IpPacket.PROTO_UDP) continue
                if (IpPacket.dstPort(packet) != 53) continue
                handleDns(packet, output)
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Ошибка цикла VPN", e)
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun handleDns(packet: ByteArray, output: FileOutputStream) {
        val payloadOffset = IpPacket.udpPayloadOffset(packet)
        val payloadLength = minOf(IpPacket.udpPayloadLength(packet), packet.size - payloadOffset)
        if (payloadLength <= 0) return
        val host = DnsPacket.questionName(packet, payloadOffset, payloadLength)
        val prefs = Prefs.get(this)

        if (host != null && BlocklistRepository.isBlocked(host)) {
            prefs.blockedCount = prefs.blockedCount + 1
            val answer = DnsPacket.buildNxDomain(packet, payloadOffset, payloadLength)
            val response = IpPacket.buildUdpResponse(packet, answer)
            synchronized(output) { output.write(response) }
            notifyStats()
            return
        }

        prefs.allowedCount = prefs.allowedCount + 1
        val query = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        pool.execute { forward(packet, query, output) }
    }

    private fun forward(original: ByteArray, query: ByteArray, output: FileOutputStream) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 6_000
            val upstream = InetAddress.getByName(Prefs.get(this).upstreamDns)
            socket.send(DatagramPacket(query, query.size, upstream, 53))
            val buf = ByteArray(4096)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            val answer = buf.copyOf(reply.length)
            val response = IpPacket.buildUdpResponse(original, answer)
            synchronized(output) { output.write(response) }
        } catch (e: Exception) {
            Log.d(TAG, "DNS forward: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private fun notifyStats() {
        val now = System.currentTimeMillis()
        if (now - lastStatsBroadcast > 700) {
            lastStatsBroadcast = now
            sendBroadcast(Intent(ACTION_STATS_UPDATED).setPackage(packageName))
        }
    }

    private var lastStatsBroadcast = 0L

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ADBlok", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Статус блокировщика рекламы"
            nm.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AdVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_active, BlocklistRepository.size))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    getString(R.string.stop),
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        worker?.interrupt()
        worker = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        pool.shutdownNow()
        runCatching { vpnInterface?.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdVpnService"
        private const val CHANNEL_ID = "adblok_status"
        private const val NOTIF_ID = 42
        private const val MTU = 1500
        private const val VPN_ADDRESS = "10.215.173.1"
        private const val VPN_DNS = "10.215.173.2"

        const val ACTION_STOP = "com.adblok.app.STOP"
        const val ACTION_STATE_CHANGED = "com.adblok.app.STATE_CHANGED"
        const val ACTION_STATS_UPDATED = "com.adblok.app.STATS_UPDATED"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, AdVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AdVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
