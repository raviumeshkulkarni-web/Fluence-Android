package com.groq.voicetyper.ui

import androidx.compose.ui.unit.dp
import com.groq.voicetyper.ui.icons.FluenceIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluenceIconsAndProviderUtilsTest {

    @Test
    fun allFluenceIcons_parseCleanlyWith24DpBoundsAndNonEmptyNames() {
        val icons = listOf(
            FluenceIcons.LayoutDashboard,
            FluenceIcons.History,
            FluenceIcons.BookOpen,
            FluenceIcons.Braces,
            FluenceIcons.RefreshCw,
            FluenceIcons.Settings,
            FluenceIcons.Search,
            FluenceIcons.Mic,
            FluenceIcons.AudioWaveform,
            FluenceIcons.X,
            FluenceIcons.MoreHorizontal,
            FluenceIcons.Copy,
            FluenceIcons.Share2,
            FluenceIcons.Download,
            FluenceIcons.TrendingUp,
            FluenceIcons.TrendingDown,
            FluenceIcons.Minus,
            FluenceIcons.ChevronDown,
            FluenceIcons.Check,
            FluenceIcons.Trash2,
            FluenceIcons.Menu,
            FluenceIcons.Eye,
            FluenceIcons.ArrowUpDown,
            FluenceIcons.Zap,
            FluenceIcons.Lightbulb,
            FluenceIcons.CaseSensitive,
            FluenceIcons.EyeOff,
            FluenceIcons.Bot,
            FluenceIcons.Smartphone,
            FluenceIcons.Shield,
            FluenceIcons.Lock,
            FluenceIcons.CircleDot,
            FluenceIcons.Volume2,
            FluenceIcons.Sun,
            FluenceIcons.Moon,
            FluenceIcons.Info,
            FluenceIcons.Keyboard,
            FluenceIcons.Layers,
            FluenceIcons.PersonStanding,
            FluenceIcons.BatteryWarning
        )

        assertEquals(40, icons.size)
        icons.forEach { vector ->
            assertTrue("Expected non-empty icon name", vector.name.isNotBlank())
            assertEquals("Expected 24.dp width for ${vector.name}", 24.dp, vector.defaultWidth)
            assertEquals("Expected 24.dp height for ${vector.name}", 24.dp, vector.defaultHeight)
        }
    }

    @Test
    fun modelsEndpointUrl_resolvesExpectedUrlsWithoutDoubleV1() {
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            modelsEndpointUrl("groq", null)
        )
        assertEquals(
            "https://api.mistral.ai/v1/models",
            modelsEndpointUrl("mistral", null)
        )
        assertEquals(
            "https://api.example.com/v1/models",
            modelsEndpointUrl("custom", "https://api.example.com")
        )
        assertEquals(
            "https://api.example.com/v1/models",
            modelsEndpointUrl("custom", "https://api.example.com/v1")
        )
    }
}
