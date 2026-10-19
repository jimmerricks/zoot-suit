package com.mickstarify.zooforzotero.ZoteroAPI

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Zotero's rate-limiting rules (Web API v3 "Basics" > "Rate Limiting"):
 * - `429 Too Many Requests`: wait `Retry-After` seconds, or back off exponentially
 *   if it's absent, before retrying.
 * - `503 Service Unavailable` with `Retry-After`: maintenance; wait and retry.
 * - `Backoff: <seconds>` on any response: make no further requests until it passes.
 *
 * Waits are capped so a bad header can't hang the app; a request still limited
 * after [MAX_RETRIES] retries returns its error response to the caller as before.
 */
object ZoteroRateLimitPolicy {
    const val MAX_RETRIES = 3
    const val MAX_WAIT_MS = 60_000L

    /** Milliseconds to wait before retry number [attempt] + 1, or null to not retry. */
    fun retryDelayMillis(code: Int, retryAfter: String?, attempt: Int): Long? {
        if (code != 429 && code != 503) return null
        if (attempt >= MAX_RETRIES) return null
        val seconds = retryAfter?.trim()?.toLongOrNull()
        if (seconds != null) return (seconds * 1000).coerceIn(0, MAX_WAIT_MS)
        if (code == 503) return null // an outage without Retry-After: fail, don't spin
        return (2000L shl attempt).coerceAtMost(MAX_WAIT_MS) // 2 s, 4 s, 8 s
    }

    /** Milliseconds a `Backoff` header asks for, or null if absent/invalid. */
    fun backoffMillis(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return (seconds * 1000).coerceAtMost(MAX_WAIT_MS)
    }
}

/**
 * Applies [ZoteroRateLimitPolicy] to Zotero API calls. One instance is shared by
 * all of a ZoteroAPI's clients so a Backoff seen by one delays the others. Retrying
 * writes is safe: 429/503 mean the server didn't process the request. Runs on
 * OkHttp's calling thread, which for this app is never the main thread.
 */
class ZoteroRateLimitInterceptor(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() }
) : Interceptor {
    @Volatile
    private var backoffUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val pending = backoffUntil - now()
        if (pending > 0) sleep(pending)

        var attempt = 0
        var response = chain.proceed(chain.request())
        while (true) {
            ZoteroRateLimitPolicy.backoffMillis(response.header("Backoff"))?.let {
                backoffUntil = maxOf(backoffUntil, now() + it)
            }
            val delay = ZoteroRateLimitPolicy.retryDelayMillis(
                response.code, response.header("Retry-After"), attempt
            ) ?: return response
            response.close()
            sleep(delay)
            attempt++
            response = chain.proceed(chain.request())
        }
    }
}
