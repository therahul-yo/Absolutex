package com.absolutex.core.decode

/**
 * What a decode attempt did, classified by cause at the one boundary that sees the cause —
 * the decode call itself (docs/decode-oom-policy.md). A corrupt page and an out-of-memory
 * page differ in blame, retryability, and what the app should do next, so they must not
 * share a null: a nullable bitmap makes an OOM look like corruption, marks a good page
 * failed, and invites a retry that re-attempts the same allocation.
 *
 * [Decoded] carries the image; the caller that owns the page cache stores it. [Unreadable]
 * is a property of the page — sticky, page-level, manual retry only. [OutOfMemory] is a
 * resource event — never a property of the page, never marked failed; the caller sheds
 * and stops the batch that produced it.
 *
 * Classification is narrow by policy: OutOfMemoryError maps to [OutOfMemory], the decoder's
 * real corrupt-input failures map to [Unreadable], and anything else propagates rather than
 * being laundered into either.
 */
sealed interface DecodeOutcome {
    data class Decoded(val image: PageImage) : DecodeOutcome
    data object Unreadable : DecodeOutcome
    data object OutOfMemory : DecodeOutcome
}
