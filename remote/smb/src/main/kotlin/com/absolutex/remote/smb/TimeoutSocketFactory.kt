package com.absolutex.remote.smb

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * [SocketFactory] with a bounded TCP handshake. smbj's transport calls
 * `createSocket(host, port)` and only sets `soTimeout` afterwards, so without this a NAS
 * that drops packets (powered off behind a stale route, a firewall DROP, a VPN black hole)
 * pins the calling thread for the OS TCP timeout — often past 60 s — regardless of every
 * timeout on [com.hierynomus.smbj.SmbConfig]. The bound covers the handshake only; read
 * behaviour stays with the socket timeouts the config sets.
 */
class TimeoutSocketFactory(
    private val connectTimeoutMs: Int,
    private val sockets: () -> Socket = ::Socket,
) : SocketFactory() {

    override fun createSocket(): Socket = sockets()

    override fun createSocket(host: String, port: Int): Socket {
        val socket = sockets()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        return socket
    }

    override fun createSocket(address: InetAddress, port: Int): Socket {
        val socket = sockets()
        socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = sockets()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        return socket
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket {
        val socket = sockets()
        socket.bind(InetSocketAddress(localAddress, localPort))
        socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
        return socket
    }
}
