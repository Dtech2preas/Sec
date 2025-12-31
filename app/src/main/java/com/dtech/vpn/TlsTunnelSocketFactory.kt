package com.dtech.vpn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A custom SocketFactory that wraps a plain TCP socket with TLS,
 * but specifically sets the SNI Hostname to the spoofed host.
 */
class TlsTunnelSocketFactory(
    private val sniHost: String,
    private val protectCallback: ((Socket) -> Unit)? = null
) : SocketFactory() {

    private val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    override fun createSocket(host: String, port: Int): Socket {
        // 1. Establish the TCP connection to the destination (SSH Server)
        // Note: In a real "Spoofing" scenario, we might connect to the ISP's Zero-Rated IP
        // if that's how they filter, but usually we connect to OUR server IP,
        // but we tell the ISP "Hey, I want to talk to whatsapp.com" in the handshake.
        val socket = Socket(host, port)
        protectCallback?.invoke(socket)
        return upgradeToSsl(socket, host, port)
    }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket {
        val socket = Socket(host, port, localHost, localPort)
        protectCallback?.invoke(socket)
        return upgradeToSsl(socket, host, port)
    }

    override fun createSocket(address: java.net.InetAddress?, port: Int): Socket {
        throw UnsupportedOperationException("This factory requires a hostname for SNI.")
    }

    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket {
        throw UnsupportedOperationException("This factory requires a hostname for SNI.")
    }

    // This method is called by JSch if we use it as a Proxy, but JSch uses createSocket(host, port) mostly.

    private fun upgradeToSsl(plainSocket: Socket, peerHost: String, peerPort: Int): Socket {
        // 2. Overlay TLS on the existing TCP socket.
        // Important: We use 'sniHost' for the SNI configuration, but 'peerHost' (our VPS IP) for the connection?
        // Actually, the SSLSocket needs to know the target peer to verify certs if we care about that.
        // But the critical part for free internet is the SNI extension.

        val sslSocket = sslFactory.createSocket(plainSocket, peerHost, peerPort, true) as SSLSocket

        // 3. Configure SNI
        val sslParams = SSLParameters()
        sslParams.serverNames = listOf(SNIHostName(sniHost))
        sslSocket.sslParameters = sslParams

        // 4. Start Handshake
        sslSocket.startHandshake()

        return sslSocket
    }
}
