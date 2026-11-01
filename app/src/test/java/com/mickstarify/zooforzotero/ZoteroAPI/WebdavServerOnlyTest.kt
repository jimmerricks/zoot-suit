package com.mickstarify.zooforzotero.ZoteroAPI

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebdavServerOnlyTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val basic = Authenticator { _, response ->
        response.request.newBuilder().header("Authorization", Credentials.basic("u", "p")).build()
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun client(address: String) =
        OkHttpClient.Builder().authenticator(serverOnly(address, basic)).build()

    @Test
    fun `the configured server gets the login`() {
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\""))
        server.enqueue(MockResponse().setResponseCode(200))
        val address = server.url("/zotero").toString()
        client(address).newCall(Request.Builder().url(address).build()).execute().use {
            assertEquals(200, it.code)
        }
        server.takeRequest()
        assertEquals(Credentials.basic("u", "p"), server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a host redirected to doesn't`() {
        // localhost vs 127.0.0.1: same machine, different host names
        val elsewhere = other.url("/steal").newBuilder().host("127.0.0.1").build()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", elsewhere))
        other.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\""))
        val address = server.url("/zotero").newBuilder().host("localhost").build().toString()
        client(address).newCall(Request.Builder().url(address).build()).execute().use {
            assertEquals(401, it.code)
        }
        assertEquals(1, other.requestCount)
        assertNull(other.takeRequest().getHeader("Authorization"))
    }
}
