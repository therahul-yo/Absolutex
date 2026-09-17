package com.absolutex.remote.smb

import java.io.IOException
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

    override fun createSocket(host: String, port: Int): Socket =
        connect(sockets(), InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress, port: Int): Socket =
        connect(sockets(), InetSocketAddress(address, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = sockets()
        socket.bind(InetSocketAddress(localHost, localPort))
        return connect(socket, InetSocketAddress(host, port))
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket {
        val socket = sockets()
        socket.bind(InetSocketAddress(localAddress, localPort))
        return connect(socket, InetSocketAddress(address, port))
    }

    /**
     * A failed connect leaves the socket open — java.net.Socket does not close itself on
     * SocketTimeoutException — so an unreachable NAS would leak one fd per retry without
     * the close below.
     */
    private fun connect(socket: Socket, endpoint: InetSocketAddress): Socket {
        try {
            socket.connect(endpoint, connectTimeoutMs)
            return socket
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }
}
