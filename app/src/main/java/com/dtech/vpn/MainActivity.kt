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

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: TextInputEditText
    private lateinit var etPortRange: TextInputEditText
    private lateinit var etAuth: TextInputEditText
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
        etHost = findViewById(R.id.etHost)
        etPortRange = findViewById(R.id.etPortRange)
        etAuth = findViewById(R.id.etAuth)
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
        val host = etHost.text.toString().trim()
        val portRange = etPortRange.text.toString().trim()
        val auth = etAuth.text.toString().trim()

        if (host.isEmpty() || portRange.isEmpty() || auth.isEmpty()) {
            log("Please fill all fields.")
            return
        }

        log("Preparing UDP VPN...")

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
        val host = etHost.text.toString().trim()
        val portRange = etPortRange.text.toString().trim()
        val auth = etAuth.text.toString().trim()

        val intent = Intent(this, DTechVpnService::class.java).apply {
            action = DTechVpnService.ACTION_CONNECT
            putExtra("HOST", host)
            putExtra("PORT_RANGE", portRange)
            putExtra("AUTH", auth)
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

            val scrollAmount = tvLogs.layout.getLineTop(tvLogs.lineCount) - tvLogs.height
            if (scrollAmount > 0)
                tvLogs.scrollTo(0, scrollAmount)
        }
    }
}
