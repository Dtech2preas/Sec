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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
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
            val sni = intent.getStringExtra("SNI") ?: ""
            val host = intent.getStringExtra("HOST") ?: ""
            val port = intent.getIntExtra("PORT", 443)
            val user = intent.getStringExtra("USER") ?: ""
            val pass = intent.getStringExtra("PASS") ?: ""

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

                // If SSH connects successfully (blocking call in this simple manager? No, checking code)
                // SshManager.connect() in my code is blocking?
                // Looking at SshManager.kt: it calls session.connect(timeout) which is blocking.
                // So if we pass that line, we are connected.

                if (sshManager?.localSocksPort != null) { // Assuming connected
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
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        broadcastLog("Starting Tun2Socks handler...")
        broadcastLog("NOTE: Traffic is intercepted but NOT forwarded. A native tun2socks library is required.")

        // Placeholder for Tun2Socks loop
        // In a real app, you would pass 'vpnFd' and 'socksPort' to a native library or a robust Java packet handler.
        // For this task, we will simulate the loop.

        val inputStream = FileInputStream(vpnFd)
        val buffer = ByteBuffer.allocate(32767)

        // A simple loop that reads packets to keep the interface active.
        // This effectively "blackholes" traffic because we read it and do nothing.
        // This demonstrates the VPN interface is working (packets are arriving).
        // To make internet work, you MUST implement packet parsing and forwarding to the SOCKS proxy (localhost:$socksPort).
        while (isRunning && vpnInterface != null) {
            try {
                val length = inputStream.read(buffer.array())
                if (length > 0) {
                    // Logic to forward packets would go here.
                    // Example: tun2socks.input(buffer, length)
                } else {
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                broadcastLog("Tun2Socks loop error: ${e.message}")
                break
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        sshManager?.disconnect()
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
            .setSmallIcon(R.mipmap.ic_launcher) // Assuming default icon exists
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
