package com.mickstarify.zooforzotero.ZoteroAPI

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ZoteroRateLimitTest {

    // --- policy ---

    @Test
    fun `success and ordinary errors are not retried`() {
        assertNull(ZoteroRateLimitPolicy.retryDelayMillis(200, null, 0))
        assertNull(ZoteroRateLimitPolicy.retryDelayMillis(412, "5", 0))
        assertNull(ZoteroRateLimitPolicy.retryDelayMillis(500, null, 0))
    }

    @Test
    fun `429 honours Retry-After, else backs off 2 4 8 s, then gives up`() {
        assertEquals(5000L, ZoteroRateLimitPolicy.retryDelayMillis(429, "5", 0))
        assertEquals(2000L, ZoteroRateLimitPolicy.retryDelayMillis(429, null, 0))
        assertEquals(4000L, ZoteroRateLimitPolicy.retryDelayMillis(429, null, 1))
        assertEquals(8000L, ZoteroRateLimitPolicy.retryDelayMillis(429, null, 2))
        assertNull(ZoteroRateLimitPolicy.retryDelayMillis(429, null, 3))
    }

    @Test
    fun `503 is retried only with Retry-After, and waits are capped`() {
        assertEquals(10_000L, ZoteroRateLimitPolicy.retryDelayMillis(503, "10", 0))
        assertNull(ZoteroRateLimitPolicy.retryDelayMillis(503, null, 0))
        assertEquals(60_000L, ZoteroRateLimitPolicy.retryDelayMillis(429, "86400", 0))
    }

    @Test
    fun `Backoff header parsing`() {
        assertEquals(30_000L, ZoteroRateLimitPolicy.backoffMillis("30"))
        assertNull(ZoteroRateLimitPolicy.backoffMillis(null))
        assertNull(ZoteroRateLimitPolicy.backoffMillis("soon"))
        assertNull(ZoteroRateLimitPolicy.backoffMillis("0"))
    }

    // --- interceptor ---

    private class FakeChain(private val codes: MutableList<Pair<Int, Map<String, String>>>) : Interceptor.Chain {
        var calls = 0
        private val request = Request.Builder().url("https://api.zotero.org/users/1/items").build()
        override fun request() = request
        override fun proceed(request: Request): Response {
            calls++
            val (code, headers) = codes.removeAt(0)
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("x").apply { headers.forEach { (k, v) -> header(k, v) } }
                .body("".toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }

    @Test
    fun `retries a 429 after Retry-After and returns the eventual success`() {
        val slept = mutableListOf<Long>()
        val interceptor = ZoteroRateLimitInterceptor(sleep = { slept.add(it) }, now = { 0L })
        val chain = FakeChain(mutableListOf(429 to mapOf("Retry-After" to "3"), 200 to emptyMap()))
        val response = interceptor.intercept(chain)
        assertEquals(200, response.code)
        assertEquals(2, chain.calls)
        assertEquals(listOf(3000L), slept)
    }

    @Test
    fun `gives the 429 back to the caller after the retry limit`() {
        val interceptor = ZoteroRateLimitInterceptor(sleep = {}, now = { 0L })
        val chain = FakeChain(MutableList(4) { 429 to emptyMap<String, String>() })
        assertEquals(429, interceptor.intercept(chain).code)
        assertEquals(4, chain.calls)
    }

    @Test
    fun `a Backoff header delays the next request`() {
        var clock = 1_000L
        val slept = mutableListOf<Long>()
        val interceptor = ZoteroRateLimitInterceptor(sleep = { slept.add(it) }, now = { clock })
        interceptor.intercept(FakeChain(mutableListOf(200 to mapOf("Backoff" to "10"))))
        assertEquals(emptyList<Long>(), slept)
        clock += 4_000
        interceptor.intercept(FakeChain(mutableListOf(200 to emptyMap())))
        assertEquals(listOf(6_000L), slept)
    }
}
