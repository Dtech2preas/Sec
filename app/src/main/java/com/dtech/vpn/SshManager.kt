package com.dtech.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Properties

class SshManager(
    private val sniHost: String,
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val sshPass: String,
    private val protectCallback: ((Socket) -> Unit)? = null,
    private val logCallback: (String) -> Unit
) {

    private var session: Session? = null
    val localSocksPort = 10808

    fun connect() {
        try {
            logCallback("Initializing JSch...")
            val jsch = JSch()

            // Create a session object
            session = jsch.getSession(sshUser, sshHost, sshPort)
            session?.setPassword(sshPass)

            // Configure Session to be lenient with host keys for this simple test app
            // In production, you'd want proper host key verification.
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            session?.setConfig(config)

            // SET THE CUSTOM SOCKET FACTORY
            // This is the magic. JSch will ask this factory for a socket.
            // Our factory will give it a TLS socket that spoofed the SNI.
            session?.setSocketFactory(JSchTlsSocketFactoryAdapter(sniHost, protectCallback))

            logCallback("Connecting to $sshHost:$sshPort via SNI: $sniHost...")

            // Connect with timeout
            session?.connect(30000)

            if (session?.isConnected == true) {
                logCallback("SUCCESS: SSH Authenticated and Connected!")
                // Enable Dynamic Port Forwarding (SOCKS5)
                session?.setPortForwardingD(localSocksPort)
                logCallback("SOCKS5 Proxy started on 127.0.0.1:$localSocksPort")
            }

        } catch (e: Exception) {
            logCallback("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }

    fun disconnect() {
        if (session != null && session!!.isConnected) {
            session?.disconnect()
            logCallback("Disconnected.")
        }
    }

    /**
     * JSch requires its own SocketFactory interface to be implemented.
     * We wrap our TlsTunnelSocketFactory logic here.
     */
    inner class JSchTlsSocketFactoryAdapter(
        val sni: String,
        protectCallback: ((Socket) -> Unit)?
    ) : SocketFactory {

        private val internalFactory = TlsTunnelSocketFactory(sni, protectCallback)

        override fun createSocket(host: String?, port: Int): Socket {
            return internalFactory.createSocket(host!!, port)
        }

        override fun getInputStream(socket: Socket?): InputStream {
            return socket!!.getInputStream()
        }

        override fun getOutputStream(socket: Socket?): OutputStream {
            return socket!!.getOutputStream()
        }
    }
}
