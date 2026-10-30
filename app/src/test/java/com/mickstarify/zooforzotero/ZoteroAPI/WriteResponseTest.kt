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

    // outcomes of a multi-object write

    @Test
    fun `each object's outcome is read by its index`() {
        val body = """{"successful":{"0":{"key":"AAAA2222"}},"success":{"0":"AAAA2222"},"unchanged":{"2":"CCCC2222"},""" +
            """"failed":{"1":{"key":"BBBB2222","code":412,"message":"Item has been modified since specified version"}}}"""
        val outcomes = WriteResponse.outcomes(body, 3)
        assertNull(outcomes[0])
        assertEquals(412, outcomes[1]?.code)
        assertNull(outcomes[2])
    }

    @Test
    fun `an object the answer doesn't mention counts as failed`() {
        val outcomes = WriteResponse.outcomes("""{"successful":{"0":{}},"failed":{}}""", 2)
        assertNull(outcomes[0])
        assertNull(outcomes[1]?.code)
        assertNotNull(outcomes[1])
    }

    @Test
    fun `an unreadable answer fails every object`() {
        assertEquals(2, WriteResponse.outcomes("<html>", 2).count { it != null })
    }

    // Zotero's answer, from the scratch account (2026-10-30)
    @Test
    fun `a collection Zotero doesn't have is named, from data or from the message`() {
        val zotero = """{"successful":{},"success":{},"unchanged":{},"failed":{"0":{"code":409,"message":"Collection ZZZZ2222 not found","data":{"collection":"ZZZZ2222"}}}}"""
        assertEquals("ZZZZ2222", WriteResponse.outcomes(zotero, 1)[0]?.missingCollection)
        val messageOnly = """{"failed":{"0":{"code":409,"message":"Collection ZZZZ2222 not found"}}}"""
        assertEquals("ZZZZ2222", WriteResponse.outcomes(messageOnly, 1)[0]?.missingCollection)
        val fromMessage = """{"failed":{"0":{"code":409,"message":"Collection 123456/QQQQ2222 doesn't exist"}}}"""
        assertEquals("QQQQ2222", WriteResponse.outcomes(fromMessage, 1)[0]?.missingCollection)
        val other = """{"failed":{"0":{"code":400,"message":"Invalid property 'foo'"}}}"""
        assertNull(WriteResponse.outcomes(other, 1)[0]?.missingCollection)
    }
}
