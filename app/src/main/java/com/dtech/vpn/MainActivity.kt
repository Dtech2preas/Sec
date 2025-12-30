package com.dtech.vpn

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

    private var sshManager: SshManager? = null
    private var isConnected = false

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

        log("Starting connection...")
        btnConnect.isEnabled = false

        sshManager = SshManager(sni, host, port, user, pass) { message ->
            log(message)
        }

        // Run network operation in background
        CoroutineScope(Dispatchers.IO).launch {
            sshManager?.connect()

            withContext(Dispatchers.Main) {
                btnConnect.isEnabled = true
                // We don't accurately track 'isConnected' state from the manager in this simple demo
                // but we can toggle the button text if we assume success or failure logic.
                // For now, let's just leave it as "Connect" / "Disconnect" manual toggle
                // or update based on log messages if we wanted to be fancy.
                btnConnect.text = "Disconnect / Retry"
                isConnected = true
            }
        }
    }

    private fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            sshManager?.disconnect()
            withContext(Dispatchers.Main) {
                log("Disconnected by user.")
                btnConnect.text = "Connect"
                isConnected = false
            }
        }
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
