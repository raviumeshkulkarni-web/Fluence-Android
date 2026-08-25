package com.groq.voicetyper.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementGateTest {

    @Test
    fun applies_only_when_fresh_and_inserted() {
        assertTrue(DictationIncrementGate.shouldApply(exists = false, insertRowId = 42L))
    }

    @Test
    fun rejects_when_uuid_already_exists() {
        assertFalse(DictationIncrementGate.shouldApply(exists = true, insertRowId = 42L))
    }

    @Test
    fun rejects_when_insert_failed() {
        assertFalse(DictationIncrementGate.shouldApply(exists = false, insertRowId = -1L))
    }

    @Test
    fun rejects_when_existing_and_insert_failed() {
        assertFalse(DictationIncrementGate.shouldApply(exists = true, insertRowId = -1L))
    }
}
