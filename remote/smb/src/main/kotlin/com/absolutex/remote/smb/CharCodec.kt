package com.absolutex.remote.smb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * CharArray to UTF-8 bytes and back without ever materialising a [String]. The credential
 * store's contract uses [CharArray] precisely so callers can zero the secret after use; routing
 * through `concatToString`/`String(bytes)` pins an unscrubbable copy on the heap until GC.
 *
 * Each direction encodes into a buffer sized up front from the coder's maximum ratio, then
 * zeroes the whole scratch before returning — only the exact-size result escapes, and the
 * caller owns and must zero that too. Malformed input substitutes (REPLACE, explicitly):
 * a password with an unpaired surrogate encodes to U+FFFD rather than throwing, matching
 * the old String path byte for byte — a login-time crash over a paste artifact would be
 * worse than the substitution, and the mismatch surfaces as an auth failure either way.
 */
internal object CharCodec {

    /** Exact UTF-8 bytes of [chars]; the pre-sized scratch buffer is zeroed. */
    fun CharArray.toUtf8Bytes(): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val scratch = ByteBuffer.allocate((size * encoder.maxBytesPerChar()).toInt())
        val result = encoder.encode(CharBuffer.wrap(this), scratch, true)
        if (!result.isUnderflow) throw IOException("password encoding failed: $result")
        encoder.flush(scratch)
        scratch.flip()
        val bytes = ByteArray(scratch.remaining())
        scratch.get(bytes)
        scratch.array().fill(0)
        return bytes
    }

    /** Decodes UTF-8 [bytes]; the decoder's scratch buffer is zeroed. */
    fun ByteArray.toChars(): CharArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val scratch = CharBuffer.allocate((size * decoder.maxCharsPerByte()).toInt())
        val result = decoder.decode(ByteBuffer.wrap(this), scratch, true)
        if (!result.isUnderflow) throw IOException("password decoding failed: $result")
        decoder.flush(scratch)
        scratch.flip()
        val chars = CharArray(scratch.remaining())
        scratch.get(chars)
        scratch.array().fill(Char.MIN_VALUE)
        return chars
    }
}
