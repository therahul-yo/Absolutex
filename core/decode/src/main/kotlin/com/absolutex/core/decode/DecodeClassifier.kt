package com.absolutex.core.decode

import java.io.IOException

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
        DecodeOutcome.OutOfMemory
    } catch (e: IOException) {
        DecodeOutcome.Unreadable
    } catch (e: IllegalArgumentException) {
        DecodeOutcome.Unreadable
    }
}
