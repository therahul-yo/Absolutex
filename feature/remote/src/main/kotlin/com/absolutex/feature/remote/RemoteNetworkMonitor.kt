package com.absolutex.feature.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.absolutex.remote.core.TransportInvalidator
import java.io.Closeable

/**
 * Drops stale transport sessions on network change (Phase F item 2).
 *
 * Wi-Fi→cellular or a VPN toggle invalidates sockets with no immediate error: without
 * this, every in-flight read burns the full ~30s socket timeout before the retry loop
 * even starts. The default-network callback proactively [TransportInvalidator.invalidate]s
 * every watched session instead, so the next read reconnects on the new path.
 *
 * Module split is deliberate: transports stay testable without Android (the hook lives
 * in `:remote:core`, exercised by JVM tests calling `invalidate()` directly), and only
 * this class touches [ConnectivityManager]. One instance per app (Hilt singleton in
 * [RemoteModule]); it holds no static state and never outlives the process.
 *
 * Lifetimes (no leaking registry): [watch] adds a session, the returned handle removes
 * it, and [close] removes everything. Handles are bound to book sessions —
 * [RemoteBackendResolver] wraps each opened book so closing the book unwatches — so the
 * set holds open books only, never closed ones. Callbacks fire on a connectivity
 * thread, which is why [TransportInvalidator.invalidate] must never throw or block.
 *
 * Permission: `registerDefaultNetworkCallback` needs `ACCESS_NETWORK_STATE`, which the
 * app manifest does not declare yet (owed: app lane). Until it does, registration
 * throws SecurityException and the monitor degrades to inert watching — books still
 * open, sessions still retry, only the proactive drop waits. [isActive] reports which
 * mode is live; real callback delivery stays device-owed (no live NAS here).
 */
class RemoteNetworkMonitor(context: Context) : Closeable {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val guard = Any()
    private val watched = LinkedHashSet<TransportInvalidator>()
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyNetworkChanged()
        }

        override fun onLost(network: Network) {
            notifyNetworkChanged()
        }
    }

    init {
        // Fail inert, not loud: without the manifest permission every book open would
        // otherwise crash at Hilt init. Anything besides the permission refusal is a
        // platform bug and propagates; the refusal itself is logged, not swallowed.
        callbackRegistered = try {
            connectivity.registerDefaultNetworkCallback(callback)
            true
        } catch (expected: SecurityException) {
            android.util.Log.w(
                "RemoteNetworkMonitor",
                "ACCESS_NETWORK_STATE missing; network monitoring inert",
                expected,
            )
            false
        }
    }

    /** True when the network callback is live; false when permission-owed and inert. */
    fun isActive(): Boolean = synchronized(guard) { callbackRegistered }

    /**
     * Watches [invalidator] until the returned handle closes. The handle's lifetime
     * must match the book session — the resolver binds them, so a closed book can
     * never linger here.
     */
    fun watch(invalidator: TransportInvalidator): AutoCloseable {
        synchronized(guard) { watched += invalidator }
        return AutoCloseable { unwatch(invalidator) }
    }

    /** Test hook: open books currently watched. */
    internal fun watchedCount(): Int = synchronized(guard) { watched.size }

    /** Test and callback entry: drops every watched session, best-effort each. */
    internal fun notifyNetworkChanged() {
        val snapshot = synchronized(guard) { watched.toList() }
        for (invalidator in snapshot) {
            runCatching { invalidator.invalidate() }
        }
    }

    private fun unwatch(invalidator: TransportInvalidator) {
        synchronized(guard) { watched -= invalidator }
    }

    override fun close() {
        synchronized(guard) {
            watched.clear()
            if (callbackRegistered) {
                callbackRegistered = false
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    }
}
