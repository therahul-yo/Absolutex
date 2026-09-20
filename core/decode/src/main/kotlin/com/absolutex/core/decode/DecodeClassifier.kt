package com.absolutex.core.decode

import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Classifies one decode attempt into a [DecodeOutcome], catching narrowly at the boundary
 * the policy names (docs/decode-oom-policy.md): OutOfMemoryError is a resource event,
 * corrupt-input failures are a property of the page, everything else propagates.
 *
 * Pure and total so the classification is JVM-testable the way MemoryBudgetEdgeTest tests
 * the budget arithmetic — the policy's stated reason for classifying by computation
 * instead of by catching broadly at the caller.
 */
object DecodeClassifier {

    /**
     * Runs [attempt], mapping its outcome:
     * - a thrown [OutOfMemoryError] → [DecodeOutcome.OutOfMemory] (resource event);
     * - an [IOException] or [IllegalArgumentException] → [DecodeOutcome.Unreadable]
     *   (corrupt or unsupported input — ImageDecoder.DecodeException and
     *   BitmapFactory's null are surfaced by the attempt itself as these);
     * - a null result → [DecodeOutcome.Unreadable] (decoders signal some corrupt input
     *   by returning null rather than throwing);
     * - anything else rethrows: a broad catch here would launder unrelated Errors
     *   (assertions, StackOverflowError) into "unreadable page", which is the exact
     *   failure mode the policy exists to prevent.
     */
    fun classify(attempt: () -> PageImage?): DecodeOutcome = try {
        when (val image = attempt()) {
            null -> DecodeOutcome.Unreadable
            else -> DecodeOutcome.Decoded(image)
        }
    } catch (e: OutOfMemoryError) {
        // The cause is the resource event itself; the message can carry heap detail worth
        // having in the log when diagnosing pressure, so log rather than drop it.
        LOG.log(Level.WARNING, "decode allocation failed", e)
        DecodeOutcome.OutOfMemory
    } catch (e: IOException) {
        // Corrupt or truncated input; the page-level failure path owns the user-facing
        // string, the log keeps the cause.
        LOG.log(Level.INFO, "decode unreadable", e)
        DecodeOutcome.Unreadable
    } catch (e: IllegalArgumentException) {
        // The decoder rejecting the format; same page-level path as above.
        LOG.log(Level.INFO, "decode rejected input", e)
        DecodeOutcome.Unreadable
    }

    private val LOG = Logger.getLogger(DecodeClassifier::class.java.name)
}
