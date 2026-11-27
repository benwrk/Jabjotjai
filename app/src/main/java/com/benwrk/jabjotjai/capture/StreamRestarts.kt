package com.benwrk.jabjotjai.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The phone failing to store what a stream delivered, as opposed to the stream failing: thrown
 * from a stream's collection so that [keepStreaming] ends the capture with [cause] instead of
 * recording a `stream_error` and trying again.
 */
internal class SessionWriteFailed(override val cause: Exception) : Exception(cause)

/** Runs [write], turning any failure of it into a [SessionWriteFailed]. */
internal inline fun <T> stored(write: () -> T): T = try {
    write()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw SessionWriteFailed(e)
}

/**
 * Keeps one stream going on one connection. [attempt] collects the stream once; whenever it fails,
 * or ends without failing, [onError] records why, and after a pause it is tried again. The pause
 * doubles from [FIRST_PAUSE_MS] to [LONGEST_PAUSE_MS], and goes back to the first after a
 * collection that lasted [SETTLED_MS], so a stream that fails now and then is retried at once and
 * one that keeps failing is not retried every second all night. Returns as soon as [wanted] says
 * no, which it does once the capture stops or the connection changes; a disconnection also cancels
 * the caller.
 *
 * A [SessionWriteFailed] ends it by throwing its cause: the phone could not store the capture, and
 * that ends it rather than being recorded as the stream failing.
 *
 * @param nowMs a clock in milliseconds, used only to tell how long a collection lasted
 */
internal suspend fun keepStreaming(
    attempt: suspend () -> Unit,
    onError: (String) -> Unit,
    wanted: () -> Boolean,
    nowMs: () -> Long,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    var pauseMs = FIRST_PAUSE_MS
    while (true) {
        val startedMs = nowMs()
        val error = try {
            attempt()
            ENDED_WITHOUT_ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionWriteFailed) {
            throw e.cause
        } catch (e: Exception) {
            e.toString()
        }
        if (!wanted()) return
        onError(error)
        if (nowMs() - startedMs >= SETTLED_MS) pauseMs = FIRST_PAUSE_MS
        pause(pauseMs)
        if (!wanted()) return
        pauseMs = (pauseMs * 2).coerceAtMost(LONGEST_PAUSE_MS)
    }
}

internal const val ENDED_WITHOUT_ERROR = "the stream ended without an error"
internal const val FIRST_PAUSE_MS = 1_000L
internal const val LONGEST_PAUSE_MS = 60_000L
internal const val SETTLED_MS = 60_000L
