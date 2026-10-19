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

    @Test
    fun `a failed object inside a 200 is reported with its code and message`() {
        val body = """{"successful":{},"unchanged":{},"failed":{"0":{"key":"ABCD2345","code":400,"message":"Parent item ZZZZ9999 doesn't exist"}}}"""
        assertEquals("400: Parent item ZZZZ9999 doesn't exist", WriteResponse.firstFailure(body))
    }

    @Test
    fun `an unreadable body counts as a failure`() {
        assertNotNull(WriteResponse.firstFailure("<html>oops</html>"))
        assertNotNull(WriteResponse.firstFailure(""))
    }
}
