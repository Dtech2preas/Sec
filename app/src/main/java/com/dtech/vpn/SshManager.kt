package com.dtech.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.ChannelDirectTCPIP
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.ServerSocket
import java.net.InetAddress
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
    private var socksServerSocket: ServerSocket? = null
    private var isSocksRunning = false

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
                startSocks5Proxy(localSocksPort)
            }

        } catch (e: Exception) {
            logCallback("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }

    fun disconnect() {
        isSocksRunning = false
        try {
            socksServerSocket?.close()
        } catch (e: Exception) {}

        if (session != null && session!!.isConnected) {
            session?.disconnect()
            logCallback("Disconnected.")
        }
    }

    private fun startSocks5Proxy(port: Int) {
        if (isSocksRunning) return
        isSocksRunning = true

        Thread {
            try {
                socksServerSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                logCallback("SOCKS5 Proxy started on 127.0.0.1:$port")

                while (isSocksRunning && session?.isConnected == true && socksServerSocket?.isClosed == false) {
                    try {
                        val clientSocket = socksServerSocket!!.accept()
                        Thread { handleSocksConnection(clientSocket) }.start()
                    } catch (e: Exception) {
                        if (isSocksRunning) logCallback("Accept Error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logCallback("SOCKS Proxy Error: ${e.message}")
            }
        }.start()
    }

    private fun handleSocksConnection(socket: Socket) {
        try {
            val baseIn = socket.getInputStream()
            val baseOut = socket.getOutputStream()
            val din = java.io.DataInputStream(baseIn)
            val dout = java.io.DataOutputStream(baseOut)

            // Handshake
            if (din.readByte() != 0x05.toByte()) {
                socket.close()
                return
            }
            val nmethods = din.readUnsignedByte()
            val methods = ByteArray(nmethods)
            din.readFully(methods)

            // We only support NO AUTH (0x00)
            dout.write(byteArrayOf(0x05, 0x00))
            dout.flush()

            // Request
            if (din.readByte() != 0x05.toByte()) { // Ver
                socket.close()
                return
            }
            val cmd = din.readByte() // Cmd
            if (cmd != 0x01.toByte()) { // CONNECT
                // Send command not supported for BIND/UDP
                // But we act as if we failed if not CONNECT
                dout.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                dout.flush()
                socket.close()
                return
            }
            din.readByte() // RSV
            val atyp = din.readByte()

            val targetHost: String

            when (atyp) {
                0x01.toByte() -> { // IPv4
                    val ip = ByteArray(4)
                    din.readFully(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress
                }
                0x03.toByte() -> { // Domain name
                    val len = din.readUnsignedByte()
                    val hostBytes = ByteArray(len)
                    din.readFully(hostBytes)
                    targetHost = String(hostBytes)
                }
                0x04.toByte() -> { // IPv6
                     val ip = ByteArray(16)
                     din.readFully(ip)
                     targetHost = InetAddress.getByAddress(ip).hostAddress
                }
                else -> {
                    socket.close()
                    return
                }
            }
            val targetPort = din.readUnsignedShort()

            // Connect to SSH
            val channel = session?.openChannel("direct-tcpip") as? ChannelDirectTCPIP
            if (channel == null) {
                socket.close()
                return
            }

            channel.setHost(targetHost)
            channel.setPort(targetPort)

            val sshIn = channel.inputStream
            val sshOut = channel.outputStream

            channel.connect(10000) // 10s timeout

            // Send success reply
            dout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            dout.flush()

            // Pipe streams
            // We need two threads
            val t1 = Thread {
                try {
                    val buf = ByteArray(8192)
                    var len: Int
                    while (true) {
                        len = baseIn.read(buf)
                        if (len == -1) break
                        if (channel.isConnected) {
                            sshOut.write(buf, 0, len)
                            sshOut.flush()
                        } else break
                    }
                } catch (e: Exception) {}
                finally {
                    try { socket.close() } catch(e:Exception){}
                    channel.disconnect()
                }
            }
            t1.start()

            try {
                val buf = ByteArray(8192)
                var len: Int
                while (true) {
                    len = sshIn.read(buf)
                    if (len == -1) break
                    if (!socket.isClosed) {
                        baseOut.write(buf, 0, len)
                        baseOut.flush()
                    } else break
                }
            } catch (e: Exception) {}
            finally {
                try { socket.close() } catch(e:Exception){}
                channel.disconnect()
            }

        } catch (e: Exception) {
            // logCallback("SOCKS Handler Error: ${e.message}")
            try { socket.close() } catch(_:Exception){}
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
