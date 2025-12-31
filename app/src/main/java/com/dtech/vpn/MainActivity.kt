package com.dtech.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etSniHost: TextInputEditText
    private lateinit var etSshHost: TextInputEditText
    private lateinit var etSshPort: TextInputEditText
    private lateinit var etSshUser: TextInputEditText
    private lateinit var etSshPass: TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var tvLogs: TextView

    private var isConnected = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DTechVpnService.ACTION_LOG) {
                val message = intent.getStringExtra(DTechVpnService.EXTRA_LOG_MESSAGE)
                if (message != null) {
                    log(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        etSniHost = findViewById(R.id.etSniHost)
        etSshHost = findViewById(R.id.etSshHost)
        etSshPort = findViewById(R.id.etSshPort)
        etSshUser = findViewById(R.id.etSshUser)
        etSshPass = findViewById(R.id.etSshPass)
        btnConnect = findViewById(R.id.btnConnect)
        tvLogs = findViewById(R.id.tvLogs)

        tvLogs.movementMethod = ScrollingMovementMethod()

        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        val filter = IntentFilter(DTechVpnService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(logReceiver)
        super.onDestroy()
    }

    private fun connect() {
        val sni = etSniHost.text.toString().trim()
        val host = etSshHost.text.toString().trim()
        val portStr = etSshPort.text.toString().trim()
        val user = etSshUser.text.toString().trim()
        val pass = etSshPass.text.toString().trim()

        if (sni.isEmpty() || host.isEmpty() || portStr.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            log("Please fill all fields.")
            return
        }

        val port = portStr.toIntOrNull() ?: 443

        log("Preparing VPN Service...")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
            onActivityResult(0, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0 && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val sni = etSniHost.text.toString().trim()
        val host = etSshHost.text.toString().trim()
        val portStr = etSshPort.text.toString().trim()
        val user = etSshUser.text.toString().trim()
        val pass = etSshPass.text.toString().trim()
        val port = portStr.toIntOrNull() ?: 443

        val intent = Intent(this, DTechVpnService::class.java).apply {
            action = DTechVpnService.ACTION_CONNECT
            putExtra("SNI", sni)
            putExtra("HOST", host)
            putExtra("PORT", port)
            putExtra("USER", user)
            putExtra("PASS", pass)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        btnConnect.text = "Disconnect"
        isConnected = true
    }

    private fun disconnect() {
        val intent = Intent(this, DTechVpnService::class.java)
        intent.action = DTechVpnService.ACTION_DISCONNECT
        startService(intent)

        log("Disconnected request sent.")
        btnConnect.text = "Connect"
        isConnected = false
    }

    private fun log(message: String) {
        runOnUiThread {
            val currentText = tvLogs.text.toString()
            val newText = "$currentText\n$message"
            tvLogs.text = newText

            // Auto scroll to bottom
            val scrollAmount = tvLogs.layout.getLineTop(tvLogs.lineCount) - tvLogs.height
            if (scrollAmount > 0)
                tvLogs.scrollTo(0, scrollAmount)
        }
    }
}
