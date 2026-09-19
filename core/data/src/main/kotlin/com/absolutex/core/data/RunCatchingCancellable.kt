package com.absolutex.core.data

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` for suspend code: a failure is captured, a cancellation is not.
 *
 * Plain `runCatching` catches every `Throwable`, including [CancellationException]. Around a
 * suspending block that is wrong: a delivered cancellation is swallowed into `Result.failure`,
 * so the caller's job completes "normally" instead of cancelling and keeps running past the
 * point it was asked to stop (see the `ShellViewModel.scan` regression this fixes). Rethrowing
 * the cancellation before it can be captured restores structured concurrency; every other
 * failure is still returned as `Result.failure` for the caller to degrade.
 *
 * The block is `inline`, so it inherits the suspend context and may call suspend functions
 * directly. Deliberately rethrows only [CancellationException] — whether to also let
 * `OutOfMemoryError` through on load paths is a separate decision, not bundled here.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching { block() }.onFailure { if (it is CancellationException) throw it }
