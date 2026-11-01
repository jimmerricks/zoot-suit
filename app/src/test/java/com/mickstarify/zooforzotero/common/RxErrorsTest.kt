package com.mickstarify.zooforzotero.common

import io.reactivex.rxjava3.exceptions.UndeliverableException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RxErrorsTest {
    @Test
    fun `undeliverable network and interruption errors are ignored`() {
        assertTrue(isLateCancellationError(UndeliverableException(IOException("reset"))))
        assertTrue(isLateCancellationError(UndeliverableException(SocketTimeoutException())))
        assertTrue(isLateCancellationError(UndeliverableException(InterruptedException())))
    }

    @Test
    fun `bugs still crash`() {
        assertFalse(isLateCancellationError(UndeliverableException(IllegalStateException())))
        assertFalse(isLateCancellationError(UndeliverableException(NullPointerException())))
        assertFalse(isLateCancellationError(IOException("not wrapped")))
    }
}
