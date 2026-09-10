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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.groq.voicetyper.dictionary.ui.DictionaryScreen
import com.groq.voicetyper.snippets.ui.SnippetsScreen
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.sync.ui.SyncScreen
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.Sidebar
import com.groq.voicetyper.ui.AboutScreen
import com.groq.voicetyper.ui.AgentConfigScreen
import com.groq.voicetyper.ui.HistoryScreen
import com.groq.voicetyper.ui.HomeScreen
import com.groq.voicetyper.ui.OfflineConfigScreen
import com.groq.voicetyper.ui.PermissionsScreen
import com.groq.voicetyper.ui.PrivacyExclusionsScreen
import com.groq.voicetyper.ui.SettingsScreen
import com.groq.voicetyper.ui.SttConfigScreen
import com.groq.voicetyper.ui.TranscriptionDetailSheet
import kotlinx.coroutines.launch

private val screenOrder = listOf(
    Screen.Home,
    Screen.History,
    Screen.SettingsHub,
    Screen.SttConfig,
    Screen.AgentConfig,
    Screen.OfflineConfig,
    Screen.CustomDictionary,
    Screen.Snippets,
    Screen.SyncConfig,
    Screen.Permissions,
    Screen.PrivacyExclusions,
    Screen.About
)

@Composable
fun FluenceNavHost(
    syncManager: SyncManager,
    deepLinkToSettings: Boolean = false,
    onRequestPermission: () -> Unit = {},
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    onConsentClick: () -> Unit = {},
    signInError: String? = null,
    syncSection: @Composable () -> Unit = {}
) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = backStack.lastOrNull() ?: Screen.Home
    var previousSize by remember { mutableIntStateOf(1) }
    val isNavigatingForward = backStack.size >= previousSize
    androidx.compose.runtime.SideEffect {
        previousSize = backStack.size
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // Reduced motion: crossfade between screens instead of sliding them.
    val reducedMotion = LocalMotionPreferences.current.reducedMotion

    LaunchedEffect(deepLinkToSettings) {
        if (deepLinkToSettings) {
            backStack.clear()
            backStack.addAll(listOf(Screen.Home, Screen.SettingsHub))
        }
    }

    // Drawer open = back closes drawer first, not the screen.
    BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && backStack.size > 1) {
        backStack.removeLastOrNull()
    }

    fun navigateTo(screen: Screen) {
        if (backStack.lastOrNull() != screen) {
            backStack.add(screen)
        }
    }

    fun navigateFromDrawer(screen: Screen) {
        drawerScope.launch { drawerState.close() }
        when (screen) {
            // Top-level: reset to a flat root so back never stacks drawer destinations.
            Screen.Home -> {
                backStack.clear()
                backStack.add(Screen.Home)
            }
            Screen.History -> {
                backStack.clear()
                backStack.add(Screen.Home)
                backStack.add(Screen.History)
            }
            Screen.Snippets, Screen.CustomDictionary, Screen.SyncConfig, Screen.SettingsHub -> {
                if (backStack.lastOrNull() != screen) {
                    backStack.clear()
                    backStack.add(Screen.Home)
                    backStack.add(screen)
                }
            }
            else -> navigateTo(screen)
        }
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun openDrawer() {
        drawerScope.launch { drawerState.open() }
    }

    val transitionSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = FluenceMotion.durationStructural,
        easing = FastOutSlowInEasing
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
        drawerContent = {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.80f),
                color = Sidebar,
                contentColor = androidx.compose.ui.graphics.Color.White,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                FluenceDrawer(
                    current = current,
                    onNavigate = { navigateFromDrawer(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(
                        animationSpec = tween(FluenceMotion.durationImmediate, easing = FastOutSlowInEasing)
                    ) togetherWith fadeOut(
                        animationSpec = tween(FluenceMotion.durationImmediate, easing = FastOutSlowInEasing)
                    )
                } else if (isNavigatingForward) {
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
                    onOpenDrawer = { openDrawer() },
                    onNavigateToSettings = { navigateTo(Screen.SettingsHub) },
                    onOpenDetail = { entryId -> navigateTo(Screen.TranscriptionDetail(entryId)) },
                    onNavigateToSttConfig = { navigateTo(Screen.SttConfig) },
                    onNavigateToAgentConfig = { navigateTo(Screen.AgentConfig) },
                    onNavigateToOfflineConfig = { navigateTo(Screen.OfflineConfig) },
                    onRequestPermission = onRequestPermission
                )
                Screen.History -> HistoryScreen(
                    onOpenDrawer = { openDrawer() },
                    onOpenDetail = { entryId -> navigateTo(Screen.TranscriptionDetail(entryId)) },
                    onNavigateToSttConfig = { navigateTo(Screen.SttConfig) },
                    onRequestPermission = onRequestPermission
                )
                Screen.SettingsHub -> SettingsScreen(
                    onNavigateBack = { navigateBack() },
                    onNavigateTo = { navigateTo(it) },
                    syncManager = syncManager,
                    syncSection = syncSection
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
                Screen.PrivacyExclusions -> PrivacyExclusionsScreen(
                    onNavigateBack = { navigateBack() }
                )
                Screen.CustomDictionary -> DictionaryScreen(
                    onNavigateBack = { navigateBack() }
                )
                Screen.Snippets -> SnippetsScreen(
                    onNavigateBack = { navigateBack() }
                )
                Screen.SyncConfig -> SyncScreen(
                    onNavigateBack = { navigateBack() },
                    manager = syncManager,
                    onSignInClick = onSignInClick,
                    onSignOutClick = onSignOutClick,
                    onConsentClick = onConsentClick,
                    signInError = signInError
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
}
