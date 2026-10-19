package com.mickstarify.zooforzotero.ZoteroAPI

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentFileCheckTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `a normal PDF passes`() {
        assertNull(AttachmentFileCheck.problem(b("%PDF-1.7\n%âã"), b("startxref\n123\n%%EOF\n"), 5000, true))
    }

    @Test
    fun `header after leading junk and bytes after EOF are allowed`() {
        assertNull(AttachmentFileCheck.problem(b("\u0000\u0000junk%PDF-1.4"), b("%%EOF\r\n\u0000\u0000"), 5000, true))
    }

    @Test
    fun `empty file is rejected whatever its type`() {
        assertNotNull(AttachmentFileCheck.problem(ByteArray(0), ByteArray(0), 0, true))
        assertNotNull(AttachmentFileCheck.problem(ByteArray(0), ByteArray(0), 0, false))
    }

    @Test
    fun `a PDF without a header is rejected`() {
        assertNotNull(AttachmentFileCheck.problem(b("<html>error</html>"), b("%%EOF"), 5000, true))
    }

    @Test
    fun `a truncated PDF is rejected`() {
        assertNotNull(AttachmentFileCheck.problem(b("%PDF-1.7"), b("stream\nxxxxxxxxxx"), 5000, true))
    }

    @Test
    fun `non-PDF attachments only need to be non-empty`() {
        assertNull(AttachmentFileCheck.problem(b("PK\u0003\u0004"), b("anything"), 10, false))
    }
}
