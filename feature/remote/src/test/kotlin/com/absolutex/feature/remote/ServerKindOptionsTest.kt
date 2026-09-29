package com.absolutex.feature.remote

import com.absolutex.remote.sync.RemoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Which kind chips the server form shows. Komga and Kavita are hidden while nothing syncs
 * to them, but a record saved earlier must still show its own kind when it is edited.
 */
class ServerKindOptionsTest {

    @Test fun `the sync kinds are hidden until sync is wired`() {
        // The shipped flag: flipping it is the one-line change that brings the chips back.
        assertFalse(OFFER_SYNC_SERVER_KINDS)
    }

    @Test fun `adding a server offers only file servers while sync is off`() {
        assertEquals(
            listOf(RemoteKind.SMB, RemoteKind.FTP),
            selectableKinds(RemoteKind.SMB, offerSyncKinds = false),
        )
        assertEquals(
            listOf(RemoteKind.SMB, RemoteKind.FTP),
            selectableKinds(RemoteKind.FTP, offerSyncKinds = false),
        )
    }

    @Test fun `editing a saved Komga server still lists Komga`() {
        assertEquals(
            listOf(RemoteKind.SMB, RemoteKind.FTP, RemoteKind.KOMGA),
            selectableKinds(RemoteKind.KOMGA, offerSyncKinds = false),
        )
    }

    @Test fun `editing a saved Kavita server still lists Kavita`() {
        assertEquals(
            listOf(RemoteKind.SMB, RemoteKind.FTP, RemoteKind.KAVITA),
            selectableKinds(RemoteKind.KAVITA, offerSyncKinds = false),
        )
    }

    @Test fun `every kind is offered once sync is wired`() {
        for (current in RemoteKind.entries) {
            assertEquals(RemoteKind.entries.toList(), selectableKinds(current, offerSyncKinds = true))
        }
    }
}
