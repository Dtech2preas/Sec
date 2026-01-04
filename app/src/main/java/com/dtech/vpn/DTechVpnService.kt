package com.dtech.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import hysteria.Hysteria

class DTechVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.dtech.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.dtech.vpn.DISCONNECT"
        const val ACTION_LOG = "com.dtech.vpn.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val CHANNEL_ID = "DTechVpnChannel"
    }

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
            val host = intent.getStringExtra("HOST") ?: ""
            // "Port Range" is handled by the server config string in Hysteria usually,
            // or the server address itself. If the user inputs "1-65535", it might be part of the server string
            // or we might need to handle it. The prompt says:
            // "The account format... host:1-65535@user:pass".
            // So we will construct the server string as "host:port_range".
            val portRange = intent.getStringExtra("PORT_RANGE") ?: "443"
            val auth = intent.getStringExtra("AUTH") ?: "" // user:pass

            startForeground(1, createNotification("Connecting..."))
            startVpn(host, portRange, auth)
        }

        return START_STICKY
    }

    private fun startVpn(host: String, portRange: String, auth: String) {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            broadcastLog("Starting VPN Service (Hysteria V2)...")

            try {
                // 1. Establish VPN Interface
                establishVpnInterface()

                // 2. Construct Server String
                // Format: host:port (or range)
                val serverStr = "$host:$portRange"

                broadcastLog("Connecting to $serverStr...")

                // 3. Start Hysteria Client via Go Bridge
                // We use reflection or assume the library class "hysteria.Hysteria" exists.
                // Since the AAR is not here yet, this code would fail to compile if I referenced the class directly
                // without the library.
                // However, the user asked me to GENERATE the class.
                // So I will write the code as if the library exists.

                // Hysteria.start(fd, serverStr, auth, "")
                // Using reflection to avoid compile errors in this environment if possible,
                // OR just write the code and expect the user to provide the lib.

                // For the "Hard Reset" plan, I should write the correct code.
                // I will comment it out or wrap it in a try-catch with reflection if I want to be safe,
                // but standard practice is to write the code.

                // Note: Gomobile generated classes usually have a wrapper.
                // Let's assume the package is 'hysteria' and class is 'Mobile'.
                // Mobile.start(fd, serverStr, auth, "")

                broadcastLog("Calling Hysteria Native Core...")

                // FIX: Run in background thread to prevent crash
                Thread {
                    try {
                        // Fix: Cast Int to Long for gomobile compatibility
                        Hysteria.start(vpnInterface!!.fd.toLong(), serverStr, auth, "")
                        broadcastLog("Hysteria Core Connected Successfully!")
                    } catch (e: Exception) {
                        Log.e("DTechVPN", "Native Error", e)
                        broadcastLog("Error: " + e.message)
                        stopVpn() // Stop VPN if connection fails
                    }
                }.start()

            } catch (e: Exception) {
                broadcastLog("Error: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun establishVpnInterface() {
        val builder = Builder()
        builder.setSession("DTechVPN")
        builder.addAddress("10.0.0.2", 24) // Local IP
        builder.addRoute("0.0.0.0", 0)     // Redirect all traffic
        builder.setMtu(1280)               // **CRITICAL**: MTU 1280 as requested

        // Add DNS
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        vpnInterface = builder.establish()
        broadcastLog("VPN Interface Established. FD: ${vpnInterface?.fd}")
        updateNotification("Connected")
    }

    private fun stopVpn() {
        isRunning = false

        // Stop Hysteria
        try {
            Hysteria.stop()
        } catch (e: Exception) {}

        vpnInterface?.close()
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
}
