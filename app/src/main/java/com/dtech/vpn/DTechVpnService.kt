package com.dtech.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import engine.Engine
import engine.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer

class DTechVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.dtech.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.dtech.vpn.DISCONNECT"
        const val ACTION_LOG = "com.dtech.vpn.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val CHANNEL_ID = "DTechVpnChannel"
    }

    private var sshManager: SshManager? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_CONNECT) {
            val sni = intent?.getStringExtra("SNI") ?: ""
            val host = intent?.getStringExtra("HOST") ?: ""
            val port = intent?.getIntExtra("PORT", 443) ?: 443
            val user = intent?.getStringExtra("USER") ?: ""
            val pass = intent?.getStringExtra("PASS") ?: ""

            startForeground(1, createNotification("Connecting..."))
            startVpn(sni, host, port, user, pass)
        }

        return START_STICKY
    }

    private fun startVpn(sni: String, host: String, port: Int, user: String, pass: String) {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            broadcastLog("Starting VPN Service...")

            sshManager = SshManager(
                sniHost = sni,
                sshHost = host,
                sshPort = port,
                sshUser = user,
                sshPass = pass,
                protectCallback = { socket ->
                    // Protect the socket so it bypasses the VPN
                    if (!protect(socket)) {
                        broadcastLog("Failed to protect socket!")
                    }
                },
                logCallback = { msg ->
                    broadcastLog(msg)
                }
            )

            try {
                sshManager?.connect()

                if (sshManager?.localSocksPort != null) {
                     broadcastLog("SSH Connected. Establishing VPN Interface...")
                     establishVpnInterface()
                     startTun2Socks(sshManager!!.localSocksPort)
                }

            } catch (e: Exception) {
                broadcastLog("Error: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun establishVpnInterface() {
        val builder = Builder()
        builder.setSession("DTechVPN")
        builder.addAddress("10.0.0.2", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.setMtu(1500)

        // Add DNS (Google DNS for example)
        builder.addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()
        broadcastLog("VPN Interface Established. File Descriptor: ${vpnInterface?.fd}")
        updateNotification("Connected")
    }

    private fun startTun2Socks(socksPort: Int) {
        val vpnFd = vpnInterface ?: return
        broadcastLog("Starting Tun2Socks handler...")

        try {
            // Using com.ooimi.library:tun2socks which wraps a Go-based tun2socks
            val key = Key()
            key.mark = 0
            key.mtu = 1500
            // We pass the file descriptor using the fd:// syntax standard for many tun2socks implementations
            key.device = "fd://${vpnFd.fd}"
            key.proxy = "socks5://127.0.0.1:$socksPort"
            key.logLevel = "info"

            Engine.insert(key)
            Engine.start()

            broadcastLog("Tun2Socks started successfully.")
        } catch (e: Exception) {
            broadcastLog("Tun2Socks Error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            Engine.stop()
        } catch (e: Exception) {
            // Ignore if library not loaded or error
        }

        sshManager?.disconnect()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null

        stopForeground(true)
        stopSelf()
        broadcastLog("VPN Service Stopped.")
    }

    private fun broadcastLog(message: String) {
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_LOG_MESSAGE, message)
        sendBroadcast(intent)
    }

    private fun createNotification(status: String): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("DTech VPN")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
