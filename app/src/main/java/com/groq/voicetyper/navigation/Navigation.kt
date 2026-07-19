package com.groq.voicetyper.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.ui.AboutScreen
import com.groq.voicetyper.ui.AgentConfigScreen
import com.groq.voicetyper.ui.HomeScreen
import com.groq.voicetyper.ui.OfflineConfigScreen
import com.groq.voicetyper.ui.SettingsScreen
import com.groq.voicetyper.ui.PermissionsScreen
import com.groq.voicetyper.ui.SttConfigScreen
import com.groq.voicetyper.ui.TranscriptionDetailSheet

private val screenOrder = listOf(
    Screen.Home,
    Screen.SettingsHub,
    Screen.SttConfig,
    Screen.AgentConfig,
    Screen.OfflineConfig,
    Screen.Permissions,
    Screen.About
)

@Composable
fun FluenceNavHost(onRequestPermission: () -> Unit = {}) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = backStack.lastOrNull() ?: Screen.Home
    var previousSize by remember { mutableIntStateOf(1) }

    val isNavigatingForward = backStack.size >= previousSize
    previousSize = backStack.size

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
    }

    fun navigateTo(screen: Screen) {
        if (backStack.lastOrNull() != screen) {
            backStack.add(screen)
        }
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    val transitionSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = FluenceMotion.durationStructural,
        easing = FastOutSlowInEasing
    )

    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (isNavigatingForward) {
                slideInHorizontally(
                    animationSpec = transitionSpec,
                    initialOffsetX = { it / 3 }
                ) + fadeIn(
                    animationSpec = tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)
                ) togetherWith
                slideOutHorizontally(
                    animationSpec = transitionSpec,
                    targetOffsetX = { -it / 3 }
                ) + fadeOut(
                    animationSpec = tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)
                )
            } else {
                slideInHorizontally(
                    animationSpec = transitionSpec,
                    initialOffsetX = { -it / 3 }
                ) + fadeIn(
                    animationSpec = tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)
                ) togetherWith
                slideOutHorizontally(
                    animationSpec = transitionSpec,
                    targetOffsetX = { it / 3 }
                ) + fadeOut(
                    animationSpec = tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)
                )
            }
        },
        label = "nav_content"
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(
                onNavigateToSettings = { navigateTo(Screen.SettingsHub) }
            )
            Screen.SettingsHub -> SettingsScreen(
                onNavigateBack = { navigateBack() },
                onNavigateTo = { navigateTo(it) }
            )
            Screen.SttConfig -> SttConfigScreen(
                onNavigateBack = { navigateBack() }
            )
            Screen.AgentConfig -> AgentConfigScreen(
                onNavigateBack = { navigateBack() }
            )
            Screen.OfflineConfig -> OfflineConfigScreen(
                onNavigateBack = { navigateBack() }
            )
            Screen.Permissions -> PermissionsScreen(
                onNavigateBack = { navigateBack() }
            )
            Screen.About -> AboutScreen(
                onNavigateBack = { navigateBack() }
            )
            is Screen.TranscriptionDetail -> TranscriptionDetailSheet(
                entryId = screen.entryId,
                onDismiss = { navigateBack() }
            )
        }
    }
}
