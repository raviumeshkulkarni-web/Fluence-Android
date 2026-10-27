package com.groq.voicetyper.autolearn

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.domain.AutoLearnFieldSnapshot
import com.groq.voicetyper.autolearn.domain.AutoLearnPrivacyHelper
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2 field-gate convergence tests.
 *
 * The two legacy chains (`AutoLearnPrivacyHelper.isAutoLearnAllowed` and the
 * removed `EditorInfoHelper.isAutoLearnAllowed`) are collapsed onto
 * [AutoLearnFieldSnapshot.fromEditorInfo]. These tests pin the convergence:
 * the shared gate equals the legacy chains on every realistic input, is
 * strictly safer on perverse cross-class password encodings, and the bubble
 * (accessibility) gate tolerates only the two IMF-contract members that no
 * [AccessibilityNodeInfo] can ever report.
 */
class AutoLearnFieldGateTest {

    /**
     * Reference copy of the removed `EditorInfoHelper.isAutoLearnAllowed`
     * (field-safety first + no-learning), kept verbatim so the equivalence
     * claims stay honest.
     */
    private fun legacyEditorInfoChain(info: EditorInfo?): Boolean {
        if (info == null) return false
        val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        if (typeClass == InputType.TYPE_NULL) return false

        if (typeClass == InputType.TYPE_CLASS_TEXT) {
            val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            if (isPassword) return false
        }

        if (typeClass == InputType.TYPE_CLASS_NUMBER) {
            if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return false
        }

        val isNoLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return !isNoLearning
    }

    private fun makeInfo(type: Int? = null, imeOptions: Int = 0, packageName: String? = null): EditorInfo =
        EditorInfo().apply {
            if (type != null) inputType = type
            this.imeOptions = imeOptions
            if (packageName != null) this.packageName = packageName
        }

    private class MatrixCase(val name: String, val build: () -> EditorInfo?)

    private val matrix = listOf(
        MatrixCase("null info") { null },
        MatrixCase("TYPE_NULL") { makeInfo(InputType.TYPE_NULL) },
        MatrixCase("text normal") {
            makeInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        },
        MatrixCase("text multiline") {
            makeInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        },
        MatrixCase("text password") {
            makeInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        },
        MatrixCase("text web password") {
            makeInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        },
        MatrixCase("text visible password") {
            makeInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        },
        MatrixCase("number password") {
            makeInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        },
        MatrixCase("number normal") {
            makeInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL)
        },
        MatrixCase("phone") { makeInfo(InputType.TYPE_CLASS_PHONE) },
        MatrixCase("datetime") { makeInfo(InputType.TYPE_CLASS_DATETIME) },
        MatrixCase("text + no learning") {
            makeInfo(InputType.TYPE_CLASS_TEXT, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        },
        MatrixCase("text password + no learning") {
            makeInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        },
        MatrixCase("text normal with package") {
            makeInfo(InputType.TYPE_CLASS_TEXT, packageName = "com.example.app")
        }
    )

    @Test
    fun convergedGateMatchesLegacyChainOnWholeMatrix() {
        for (row in matrix) {
            val info = row.build()
            assertEquals(
                "row ${row.name}",
                legacyEditorInfoChain(info),
                AutoLearnPrivacyHelper.isAutoLearnAllowed(info)
            )
        }
    }

    @Test
    fun decompositionMatchesDelegatedHelper() {
        for (row in matrix) {
            val info = row.build()
            assertEquals(
                "row ${row.name}",
                AutoLearnPrivacyHelper.isAutoLearnAllowed(info),
                AutoLearnFieldSnapshot.fromEditorInfo(info).isObservationAllowedRest()
            )
        }
    }

    @Test
    fun convergedGateIsStricterOnCrossClassPasswordEncodings() {
        // Perverse but SDK-permissible: a password-variation payload outside
        // its own input class. The legacy field-safety chain allowed these;
        // the converged chain (variation compared class-independently,
        // identical to the other legacy helper) denies them.
        for (cls in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            val info = makeInfo(cls or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            assertTrue("legacy allowed ${cls.toHexString()}", legacyEditorInfoChain(info))
            assertFalse(
                "converged denies ${cls.toHexString()}",
                AutoLearnPrivacyHelper.isAutoLearnAllowed(info)
            )
        }
    }

    @Test
    fun nodeAdapterMapsPasswordAndPackageOnly() {
        val node = mockk<AccessibilityNodeInfo> {
            every { isPassword } returns false
            every { packageName } returns "com.gboard"
        }
        val snapshot = AutoLearnFieldSnapshot.fromAccessibilityNode(node)
        assertEquals("com.gboard", snapshot.packageName)
        assertEquals(false, snapshot.isPassword)
        assertNull(snapshot.isNullInputType)
        assertNull(snapshot.noPersonalizedLearning)
        assertTrue(snapshot.isObservationAllowedForBubble())
        // The IME-surface gate still denies unknown IMF members.
        assertFalse(snapshot.isObservationAllowedRest())
    }

    @Test
    fun nodeAdapterDeniesPasswordNode() {
        val node = mockk<AccessibilityNodeInfo> {
            every { isPassword } returns true
            every { packageName } returns "com.gboard"
        }
        assertFalse(AutoLearnFieldSnapshot.fromAccessibilityNode(node).isObservationAllowedForBubble())
    }

    @Test
    fun bubbleGateDeniesPositiveOrUnknownPassword() {
        for (password in listOf(true, null)) {
            assertFalse("isPassword=$password", AutoLearnFieldSnapshot(
                packageName = "com.example.app",
                isPassword = password,
                isNullInputType = null,
                noPersonalizedLearning = null
            ).isObservationAllowedForBubble())
        }
    }

    @Test
    fun bubbleGateDeniesAssertedNullTypeOrNoLearning() {
        val base = AutoLearnFieldSnapshot(
            packageName = "com.example.app",
            isPassword = false,
            isNullInputType = null,
            noPersonalizedLearning = null
        )
        assertFalse(base.copy(isNullInputType = true).isObservationAllowedForBubble())
        assertFalse(base.copy(noPersonalizedLearning = true).isObservationAllowedForBubble())
        // Unknown (null) IMF members stay tolerated - this is the base case.
        assertTrue(base.isObservationAllowedForBubble())
    }

    @Test
    fun bubbleGateFailsClosedOnBlankPackage() {
        val base = AutoLearnFieldSnapshot(
            packageName = null,
            isPassword = false,
            isNullInputType = null,
            noPersonalizedLearning = null
        )
        assertFalse(base.isObservationAllowedForBubble())
        assertFalse(base.copy(packageName = "").isObservationAllowedForBubble())
        assertFalse(base.copy(packageName = "   ").isObservationAllowedForBubble())
    }

    @Test
    fun bubbleGateRespectsExcludedFlag() {
        val base = AutoLearnFieldSnapshot(
            packageName = "com.example.app",
            isPassword = false,
            isNullInputType = null,
            noPersonalizedLearning = null
        )
        assertTrue(base.isObservationAllowedForBubble(excluded = false))
        assertFalse(base.isObservationAllowedForBubble(excluded = true))
    }

    @Test
    fun nodeAdapterNullNodeFailsClosedOnBothSurfaces() {
        val snapshot = AutoLearnFieldSnapshot.fromAccessibilityNode(null)
        assertFalse(snapshot.isObservationAllowedForBubble())
        assertFalse(snapshot.isObservationAllowedRest())
    }

    @Test
    fun nodeAdapterBlankPackageNameBecomesNull() {
        val node = mockk<AccessibilityNodeInfo> {
            every { isPassword } returns false
            every { packageName } returns "   "
        }
        val snapshot = AutoLearnFieldSnapshot.fromAccessibilityNode(node)
        assertNull(snapshot.packageName)
        assertFalse(snapshot.isObservationAllowedForBubble())
    }

    private fun Int.toHexString(): String = String.format("0x%08X", this)
}
