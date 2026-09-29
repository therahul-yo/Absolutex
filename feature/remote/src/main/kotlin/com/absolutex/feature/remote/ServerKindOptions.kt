package com.absolutex.feature.remote

import com.absolutex.remote.sync.RemoteKind

/**
 * Whether the Add-server form offers Komga and Kavita.
 *
 * Off because reading progress never syncs to them: `SyncController.onBookOpened`,
 * `onPageSettled` and `onBookClosed` have no callers, so a server added here would connect
 * and then do nothing. Flip this to `true` in the change that wires those three hooks into
 * the reader. The kinds, the sync module and every saved record stay as they are.
 */
internal const val OFFER_SYNC_SERVER_KINDS = false

/**
 * The kind chips the form shows, in enum order.
 *
 * Kinds that can be added right now, plus [current]: a Komga or Kavita server saved before
 * they were hidden must still show what it is when it is opened for editing, so its own
 * kind is always listed. In edit mode the kind is fixed, so that chip is shown selected and
 * disabled.
 */
internal fun selectableKinds(
    current: RemoteKind,
    offerSyncKinds: Boolean = OFFER_SYNC_SERVER_KINDS,
): List<RemoteKind> = RemoteKind.entries.filter { kind ->
    kind == current || offerSyncKinds || (kind != RemoteKind.KOMGA && kind != RemoteKind.KAVITA)
}
