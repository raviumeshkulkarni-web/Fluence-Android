package com.groq.voicetyper.agent

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.CommandProcessor
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foundation tests for custom agents. Pure helpers plus prefs-backed
 * resolution with mocked storage. No test hits the network.
 */
class AgentPreferencesTest {

    private fun prefsWith(
        storedJson: String,
        defaultId: String = "builtin",
        editor: SharedPreferences.Editor = mockk(relaxed = true)
    ): Pair<Context, SharedPreferences.Editor> {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("agent_custom_styles", "") } returns storedJson
        every { prefs.getString("agent_default_id", "builtin") } returns defaultId
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        return Pair(context, editor)
    }

    @Test
    fun parse_emptyOrCorrupt_returnsEmpty() {
        assertTrue(AgentPreferences.parseAgentsJson("").isEmpty())
        assertTrue(AgentPreferences.parseAgentsJson("not json").isEmpty())
    }

    @Test
    fun serialize_roundTrip_preservesEntries() {
        val original = listOf(
            AgentPreferences.CustomAgent("agent:1", "Translator", "Translate to Hindi"),
            AgentPreferences.CustomAgent("agent:2", "Writer", "Be professional")
        )
        val parsed = AgentPreferences.parseAgentsJson(AgentPreferences.serializeAgentsJson(original))
        assertEquals(original, parsed)
    }

    @Test
    fun parse_dropsBuiltinBlankAndCorruptEntries() {
        val raw = """[{"id":"builtin","name":"x","hint":"y"},
            {"id":"agent:1","name":"  ","hint":"y"},
            {"id":"agent:2","name":"ok","hint":""},
            {"id":"agent:3","name":"Good","hint":"Do it"}]""".trimIndent()
        val parsed = AgentPreferences.parseAgentsJson(raw)
        assertEquals(1, parsed.size)
        assertEquals("agent:3", parsed[0].id)
    }

    @Test
    fun parse_dropsExplicitNullStringsAndValues() {
        // org.json optString() can return literal "null" when JSON has null tokens
        val raw = """[
            {"id":"agent:null1","name":"null","hint":"valid hint"},
            {"id":"agent:null2","name":"Valid Name","hint":"null"},
            {"id":"null","name":"Valid Name","hint":"valid hint"},
            {"id":"agent:valid","name":"Valid Name","hint":"valid hint"}
        ]""".trimIndent()
        val parsed = AgentPreferences.parseAgentsJson(raw)
        assertEquals(1, parsed.size)
        assertEquals("agent:valid", parsed[0].id)
        assertEquals("Valid Name", parsed[0].name)
        assertEquals("valid hint", parsed[0].hint)
    }

    @Test
    fun sanitizeName_stripsNewlinesAndEnforcesLength() {
        val multiline = "Line 1\r\nLine 2\nLine 3"
        val sanitized = AgentPreferences.sanitizeName(multiline)
        assertFalse(sanitized.contains("\r"))
        assertFalse(sanitized.contains("\n"))
        assertEquals("Line 1 Line 2 Line 3", sanitized)

        val longName = "A".repeat(50)
        val truncated = AgentPreferences.sanitizeName(longName)
        assertEquals(AgentPreferences.MAX_AGENT_NAME_LENGTH, truncated.length)
    }

    @Test
    fun sanitizeHint_trimsAndEnforcesLength() {
        assertEquals("", AgentPreferences.sanitizeHint("   "))
        val longHint = "H".repeat(1500)
        val truncated = AgentPreferences.sanitizeHint(longHint)
        assertEquals(AgentPreferences.MAX_AGENT_HINT_LENGTH, truncated.length)
    }

    @Test
    fun validateAgentName_rejectsBlankAndReservedAndDuplicates() {
        val stored = AgentPreferences.serializeAgentsJson(
            listOf(AgentPreferences.CustomAgent("agent:1", "Translator", "Translate to Hindi"))
        )
        val (context, _) = prefsWith(stored)

        // Blank
        assertEquals("Give your agent a name", AgentPreferences.validateAgentName(context, "   "))

        // Reserved names
        assertEquals(
            "\"Fluence Agent\" is reserved. Choose another name.",
            AgentPreferences.validateAgentName(context, "Fluence Agent")
        )
        assertEquals(
            "\"fluence agent\" is reserved. Choose another name.",
            AgentPreferences.validateAgentName(context, "fluence agent")
        )
        assertEquals(
            "\"builtin\" is reserved. Choose another name.",
            AgentPreferences.validateAgentName(context, "builtin")
        )

        // Duplicate name
        assertEquals(
            "An agent named \"Translator\" already exists.",
            AgentPreferences.validateAgentName(context, "Translator")
        )
        assertEquals(
            "An agent named \"translator\" already exists.",
            AgentPreferences.validateAgentName(context, "translator")
        )

        // Editing same agent with same name is allowed
        org.junit.Assert.assertNull(
            AgentPreferences.validateAgentName(context, "Translator", id = "agent:1")
        )

        // Valid unique name
        org.junit.Assert.assertNull(
            AgentPreferences.validateAgentName(context, "New Agent")
        )
    }

    @Test
    fun saveCustomAgent_failsValidationOrBlankHint() {
        val (context, _) = prefsWith("")

        // Reserved name rejected
        org.junit.Assert.assertNull(
            AgentPreferences.saveCustomAgent(context, "Fluence Agent", "Some hint")
        )

        // Blank hint rejected
        org.junit.Assert.assertNull(
            AgentPreferences.saveCustomAgent(context, "Valid Name", "   ")
        )

        // Built-in id edit rejected
        org.junit.Assert.assertNull(
            AgentPreferences.saveCustomAgent(context, "Valid Name", "Some hint", id = "builtin")
        )
    }

    @Test
    fun saveCustomAgent_success_writesToPrefs() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val (context, _) = prefsWith("", editor = editor)

        val created = AgentPreferences.saveCustomAgent(context, "  Grammar Coach\n ", " Fix grammar ")
        org.junit.Assert.assertNotNull(created)
        assertEquals("Grammar Coach", created?.name)
        assertEquals("Fix grammar", created?.hint)
        assertTrue(created?.id?.startsWith("agent:") == true)

        io.mockk.verify { editor.putString("agent_custom_styles", any()) }
        io.mockk.verify { editor.apply() }
    }

    @Test
    fun deleteCustomAgent_resetsDefaultIfDeleted() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val stored = AgentPreferences.serializeAgentsJson(
            listOf(AgentPreferences.CustomAgent("agent:dead", "ToDelete", "hint"))
        )
        val (context, _) = prefsWith(stored, defaultId = "agent:dead", editor = editor)

        AgentPreferences.deleteCustomAgent(context, "agent:dead")

        io.mockk.verify { editor.putString("agent_default_id", "builtin") }
        io.mockk.verify { editor.apply() }
    }

    @Test
    fun resolve_unknownOrDeletedId_fallsBackToBuiltIn() {
        val (context, _) = prefsWith("")
        val resolved = AgentPreferences.resolveActiveAgent(context, "agent:missing")
        assertTrue(resolved.isBuiltIn)
        assertEquals("builtin", resolved.id)
    }

    @Test
    fun resolve_nullOrBlankId_fallsBackToBuiltIn() {
        val (context, _) = prefsWith("")
        val resolvedNull = AgentPreferences.resolveActiveAgent(context, null)
        assertTrue(resolvedNull.isBuiltIn)
        val resolvedBlank = AgentPreferences.resolveActiveAgent(context, "   ")
        assertTrue(resolvedBlank.isBuiltIn)
    }

    @Test
    fun resolve_knownCustom_returnsHint() {
        val stored = AgentPreferences.serializeAgentsJson(
            listOf(AgentPreferences.CustomAgent("agent:9", "Writer", "Be brief"))
        )
        val (context, _) = prefsWith(stored)
        val resolved = AgentPreferences.resolveActiveAgent(context, "agent:9")
        assertFalse(resolved.isBuiltIn)
        assertEquals("Be brief", resolved.hint)
    }

    @Test
    fun default_unknownStoredId_fallsBackToBuiltIn() {
        val (context, _) = prefsWith("", defaultId = "agent:gone")
        assertEquals("builtin", AgentPreferences.getDefaultAgentId(context))
    }

    @Test
    fun buildAgentSystemPrompt_blankHint_returnsBuiltIn() {
        assertEquals(
            CommandProcessor.BUILT_IN_SYSTEM_PROMPT,
            CommandProcessor.buildAgentSystemPrompt("   ")
        )
    }

    @Test
    fun buildAgentSystemPrompt_hint_keepsContractAndHint() {
        val prompt = CommandProcessor.buildAgentSystemPrompt("Translate to Hindi")
        assertTrue(prompt.startsWith(CommandProcessor.BUILT_IN_SYSTEM_PROMPT))
        assertTrue(prompt.contains("DELETE_CHARS"))
        assertTrue(prompt.contains("Translate to Hindi"))
    }
}
