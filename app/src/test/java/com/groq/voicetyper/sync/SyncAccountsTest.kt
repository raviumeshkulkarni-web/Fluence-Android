package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.v1.AccountHash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAccountsTest {
    @Test
    fun ownership_uses_the_same_hash_as_sync_rows() {
        val previous = SyncAccounts.cachedAccount
        try {
            SyncAccounts.cachedAccount = "Test@Example.com"

            assertFalse(SyncAccounts.isForeign(AccountHash.of("test@example.com")))
            assertTrue(SyncAccounts.isForeign(AccountHash.of("other@example.com")))
        } finally {
            SyncAccounts.cachedAccount = previous
        }
    }
}
