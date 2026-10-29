package com.mickstarify.zooforzotero.ZoteroAPI

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WriteResponseTest {
    @Test
    fun `all successful is not a failure`() {
        val body = """{"successful":{"0":{"key":"ABCD2345"}},"success":{"0":"ABCD2345"},"unchanged":{},"failed":{}}"""
        assertNull(WriteResponse.firstFailure(body))
    }

    @Test
    fun `missing failed object is not a failure`() {
        assertNull(WriteResponse.firstFailure("""{"successful":{"0":{}}}"""))
    }

    // the answers below are Zotero's, from the scratch account (2026-10-29)
    @Test
    fun `a failed object inside a 200 is reported with its code and message`() {
        val body = """{"successful":{},"success":{},"unchanged":{},"failed":{"0":{"code":409,"message":"Parent item ZZZZ9999 not found","data":{"parentItem":"ZZZZ9999"}}}}"""
        val failure = WriteResponse.firstFailure(body)
        assertEquals(409, failure?.code)
        assertEquals("409: Parent item ZZZZ9999 not found", failure.toString())
    }

    @Test
    fun `creating a key that exists is a 412`() {
        val body = """{"successful":{},"success":{},"unchanged":{},"failed":{"0":{"code":412,"message":"Item has been modified since specified version (expected 0, found 22071)"}}}"""
        assertEquals(412, WriteResponse.firstFailure(body)?.code)
    }

    @Test
    fun `an unreadable body counts as a failure`() {
        assertNotNull(WriteResponse.firstFailure("<html>oops</html>"))
        assertNull(WriteResponse.firstFailure("<html>oops</html>")?.code)
        assertNotNull(WriteResponse.firstFailure(""))
    }
}
