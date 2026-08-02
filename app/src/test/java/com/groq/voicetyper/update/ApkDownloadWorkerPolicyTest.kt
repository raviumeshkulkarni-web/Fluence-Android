package com.groq.voicetyper.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApkDownloadWorkerPolicyTest {

    @Test
    fun isRetryableHttpCode_serverErrors_retries() {
        assertTrue(ApkDownloadWorker.isRetryableHttpCode(500))
        assertTrue(ApkDownloadWorker.isRetryableHttpCode(502))
        assertTrue(ApkDownloadWorker.isRetryableHttpCode(503))
        assertTrue(ApkDownloadWorker.isRetryableHttpCode(429))
    }

    @Test
    fun isRetryableHttpCode_clientErrors_doNotRetry() {
        assertFalse(ApkDownloadWorker.isRetryableHttpCode(400))
        assertFalse(ApkDownloadWorker.isRetryableHttpCode(403))
        assertFalse(ApkDownloadWorker.isRetryableHttpCode(404))
        assertFalse(ApkDownloadWorker.isRetryableHttpCode(410))
    }

    @Test
    fun isRetryable_successCodes_doNotRetry() {
        assertFalse(ApkDownloadWorker.isRetryableHttpCode(200))
        assertFalse(ApkDownloadWorker.isRetryableHttpCode(302))
    }

    @Test
    fun isRetryable_networkIoExceptions_retry() {
        assertTrue(ApkDownloadWorker.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(ApkDownloadWorker.isRetryable(ConnectException("refused")))
        assertTrue(ApkDownloadWorker.isRetryable(UnknownHostException("no host")))
        assertTrue(ApkDownloadWorker.isRetryable(IOException("stream error")))
    }

    @Test
    fun isRetryable_nonIoExceptions_doNotRetry() {
        assertFalse(ApkDownloadWorker.isRetryable(IllegalStateException("bad state")))
        assertFalse(ApkDownloadWorker.isRetryable(SecurityException("denied")))
        assertFalse(ApkDownloadWorker.isRetryable(NullPointerException("null")))
    }
}
