package com.mokhtarabadi.tun2socks;

import android.content.Context;

/**
 * Interface for the native Tun2Socks library.
 * This class expects a native library "tun2socks" to be available.
 */
public class Tun2Socks {
    static {
        try {
            System.loadLibrary("tun2socks");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native library tun2socks not found: " + e.getMessage());
        }
    }

    /**
     * Starts the tun2socks packet forwarder.
     * This method is blocking and should be called from a background thread.
     *
     * @param vpnInterfaceFileDescriptor The file descriptor of the TUN interface (as int).
     * @param vpnInterfaceMtu The MTU of the TUN interface.
     * @param vpnInterfaceAddress The IP address of the TUN interface (e.g., "10.0.0.2").
     * @param vpnInterfaceNetmask The netmask of the TUN interface (e.g., "255.255.255.0").
     * @param socksServerAddress The IP address of the SOCKS5 server (e.g., "127.0.0.1").
     * @param socksServerPort The port of the SOCKS5 server.
     * @param socksServerUsername The username for SOCKS5 authentication (can be empty).
     * @param socksServerPassword The password for SOCKS5 authentication (can be empty).
     * @param dnsServerAddress The DNS server to use (e.g., "8.8.8.8").
     * @param udpRelay Whether to enable UDP relay.
     */
    public static native void start(
            int vpnInterfaceFileDescriptor,
            int vpnInterfaceMtu,
            String vpnInterfaceAddress,
            String vpnInterfaceNetmask,
            String socksServerAddress,
            int socksServerPort,
            String socksServerUsername,
            String socksServerPassword,
            String dnsServerAddress,
            boolean udpRelay
    );

    /**
     * Stops the tun2socks packet forwarder.
     */
    public static native void stop();
}
