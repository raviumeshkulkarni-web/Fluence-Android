package com.groq.voicetyper

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keystore-invalidation guard must distinguish a permanently evicted key
 * (quarantine the encrypted file) from every other transient failure (leave the
 * file intact and degrade to signed-out for the process).
 */
class SecurePrefsStoreTest {

    @Test
    fun permanentInvalidation_detected_whenMessageNamesIt() {
        val cause = RuntimeException("android.security.KeyStoreException: Key abc was permanently invalidated")
        assertTrue(SecurePrefsStore.isPermanentInvalidation(cause))
    }

    @Test
    fun permanentInvalidation_detected_throughWrappedChain() {
        val inner = RuntimeException("keystore key was PERMANENTLY INVALIDATED")
        val wrapped = RuntimeException("create failed", inner)
        assertTrue(SecurePrefsStore.isPermanentInvalidation(wrapped))
    }

    @Test
    fun transientFailures_areNotPermanent() {
        assertFalse(SecurePrefsStore.isPermanentInvalidation(IllegalStateException("device not unlocked yet")))
        assertFalse(SecurePrefsStore.isPermanentInvalidation(IOException("key not found")))
        assertFalse(SecurePrefsStore.isPermanentInvalidation(RuntimeException("chacha20")))
        assertFalse(SecurePrefsStore.isPermanentInvalidation(KeyStoreBootLocked()))
    }

    @Test
    fun nullAndMessageFreeCausesAreNotPermanent() {
        assertFalse(SecurePrefsStore.isPermanentInvalidation(null))
        assertFalse(SecurePrefsStore.isPermanentInvalidation(IllegalStateException()))
    }

    private class KeyStoreBootLocked : RuntimeException("android.security.keystore.KeyStoreException: user not authenticated")
}