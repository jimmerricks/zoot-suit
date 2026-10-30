package com.mickstarify.zooforzotero.ZoteroAPI

object NetworkErrors {
    /** The server took too long to answer, anywhere in [e]'s cause chain. */
    fun isTimeout(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(10).any { it is java.net.SocketTimeoutException }

    /** The device is offline or the server unreachable, anywhere in [e]'s cause chain. */
    fun isOffline(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(10).any {
            it is java.net.UnknownHostException || it is java.net.ConnectException ||
                    it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException
        }
}
