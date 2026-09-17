package com.absolutex.remote.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SmbConfig
import java.util.concurrent.TimeUnit

/**
 * The single SmbConfig construction site, so the signing/encryption posture is pinned in one
 * place and in one test. Signatures verified against smbj-0.15.0 (javap over the Central jar,
 * 2026-09-15) rather than docs.
 *
 * Default is SMB3-only dialects with signing and encryption required: someone on the path can
 * otherwise rewrite READ responses and attacker bytes reach the ZIP parser and image decoders.
 * [SmbLocation.allowUnsigned] is the explicit per-server opt-out for guest and legacy shares.
 */
internal object SmbConfigFactory {

    // A LAN NAS answers in milliseconds; these bound a stalled server (dead Wi-Fi, sleeping
    // NAS) instead of pinning a decode thread indefinitely. The TCP handshake gets its own
    // bound via TimeoutSocketFactory: smbj connects with SocketFactory.createSocket(host,
    // port), which is unbounded, and only sets soTimeout afterwards — none of the
    // SmbConfig timeouts below cover it.
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val SYNC_TIMEOUT_SECONDS = 30L
    private const val SOCKET_TIMEOUT_SECONDS = 30L

    fun build(allowUnsigned: Boolean): SmbConfig {
        val builder = SmbConfig.builder()
            .withSocketFactory(TimeoutSocketFactory(CONNECT_TIMEOUT_MS))
            .withTimeout(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withReadTimeout(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withWriteTimeout(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withTransactTimeout(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (allowUnsigned) {
            builder.withDialects(
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            )
        } else {
            builder.withDialects(
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            )
                .withSigningRequired(true)
                .withEncryptData(true)
        }
        return builder.build()
    }
}
