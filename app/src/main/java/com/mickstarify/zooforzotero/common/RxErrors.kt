package com.mickstarify.zooforzotero.common

import android.util.Log
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import java.io.IOException

/**
 * Whether [e], given to RxJava's error handler, is an error nobody was left to receive
 * that is expected when a stream is cancelled: a network or file failure, or the
 * interruption of a blocking call, arriving after dispose(). RxJava 3 sends those to
 * the handler, whose default crashes the app.
 */
internal fun isLateCancellationError(e: Throwable): Boolean {
    val cause = (if (e is UndeliverableException) e.cause else null) ?: return false
    return cause is IOException || cause is InterruptedException
}

/**
 * Logs undeliverable network and file errors instead of crashing on them (a download
 * cancelled while its connection fails). Anything else is a bug and still crashes.
 */
fun installRxErrorHandler() {
    RxJavaPlugins.setErrorHandler { e ->
        if (isLateCancellationError(e)) {
            Log.w("zotero", "ignored after cancellation: ${e.cause}")
        } else {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler?.uncaughtException(thread, e)
        }
    }
}
