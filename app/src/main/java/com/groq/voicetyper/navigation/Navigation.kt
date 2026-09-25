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
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.groq.voicetyper.FluenceFeedbackHost
import com.groq.voicetyper.dictionary.ui.DictionaryScreen
import com.groq.voicetyper.snippets.ui.SnippetsScreen
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.sync.ui.SyncScreen
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.AboutScreen
import com.groq.voicetyper.ui.AgentConfigScreen
import com.groq.voicetyper.ui.AgentsScreen
import com.groq.voicetyper.ui.AiCleanupStylesScreen
import com.groq.voicetyper.ui.AiStylePickerScreen
import com.groq.voicetyper.ui.BubbleSettingsScreen
import com.groq.voicetyper.ui.BucketPickerScreen
import com.groq.voicetyper.ui.FormattingScreen
import com.groq.voicetyper.ui.HistoryScreen
import com.groq.voicetyper.ui.HomeScreen
import com.groq.voicetyper.ui.OfflineConfigScreen
import com.groq.voicetyper.ui.PermissionsScreen
import com.groq.voicetyper.ui.PrivacyExclusionsScreen
import com.groq.voicetyper.ui.SettingsScreen
import com.groq.voicetyper.ui.SttConfigScreen
import com.groq.voicetyper.ui.TranscriptionDetailSheet
import kotlinx.coroutines.launch

// M3 WindowWidthSizeClass Expanded breakpoint: at/above this width the
// pinned sidebar replaces the modal hamburger drawer (rotation/landscape).
private val ExpandedWidthBreakpoint = 840.dp
private val PermanentDrawerWidth = 320.dp

@Composable
fun FluenceNavHost(
    syncManager: SyncManager,
    deepLinkToSettings: Boolean = false,
    onRequestPermission: () -> Unit = {},
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    onConsentClick: () -> Unit = {},
    signInError: String? = null
) {
    // Back stack survives config changes (rotation); screens' local state is
    // remembered saveably on their own. Restored via string codes (see Saver).
    val backStack = rememberSaveable(
        saver = ScreenStackSaver,
        init = { mutableStateListOf<Screen>(Screen.Home) }
    )
    val current = backStack.lastOrNull() ?: Screen.Home
    // Seeded from the restored stack (not a constant) so a rotation restore
    // to a deep screen doesn't misread as forward navigation and play a
    // spurious slide on the first frame.
    var previousSize by rememberSaveable { mutableIntStateOf(backStack.size) }
    val isNavigatingForward = backStack.size >= previousSize
    androidx.compose.runtime.SideEffect {
        previousSize = backStack.size
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // Reduced motion: crossfade between screens instead of sliding them.
    val reducedMotion = LocalMotionPreferences.current.reducedMotion

    LaunchedEffect(deepLinkToSettings) {
        // Apply the deep link only once (bare root). If the stack was restored
        // after rotation, the user's place is deeper — don't flatten it again.
        if (deepLinkToSettings && backStack.size == 1 && backStack.first() == Screen.Home) {
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
            Screen.Snippets, Screen.CustomDictionary, Screen.SyncConfig, Screen.SettingsHub, Screen.Formatting, Screen.Agents -> {
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

    // Adaptive chrome: expanded-width windows (landscape / tablet — M3
    // ≥840dp breakpoint) pin the sidebar open as a permanent drawer instead
    // of the modal hamburger sheet. LocalConfiguration recomposes on
    // rotation, so the right chrome is always showing.
    val usePermanentDrawer =
        LocalConfiguration.current.screenWidthDp.dp >= ExpandedWidthBreakpoint

    @Composable
    fun DrawerContent() {
        // Single app-wide feedback host: one Fluence Snackbar, overlaid
        // above every destination, inset-aware (nav bars + IME). It is a
        // sibling of AnimatedContent (not its content) so it never slides
        // with screens, never duplicates during transitions, and its
        // SnackbarHostState survives navigation.
        Box(modifier = Modifier.fillMaxSize()) {
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
                    showDrawerButton = !usePermanentDrawer,
                    onNavigateToSettings = { navigateTo(Screen.SettingsHub) },
                    onOpenDetail = { entryId -> navigateTo(Screen.TranscriptionDetail(entryId)) },
                    onNavigateToSttConfig = { navigateTo(Screen.SttConfig) },
                    onNavigateToAgentConfig = { navigateTo(Screen.AgentConfig) },
                    onNavigateToOfflineConfig = { navigateTo(Screen.OfflineConfig) },
                    onRequestPermission = onRequestPermission
                )
                Screen.History -> HistoryScreen(
                    onOpenDrawer = { openDrawer() },
                    showDrawerButton = !usePermanentDrawer,
                    onOpenDetail = { entryId -> navigateTo(Screen.TranscriptionDetail(entryId)) },
                    onNavigateToSttConfig = { navigateTo(Screen.SttConfig) },
                    onRequestPermission = onRequestPermission
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
                Screen.PrivacyExclusions -> PrivacyExclusionsScreen(
                    onNavigateBack = { navigateBack() }
                )
                Screen.Formatting -> FormattingScreen(
                    onNavigateBack = { navigateBack() },
                    onNavigateTo = { navigateTo(it) }
                )
                Screen.BubbleSettings -> BubbleSettingsScreen(
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
                is Screen.BucketPicker -> BucketPickerScreen(
                    bucket = com.groq.voicetyper.formatting.FormattingCategory.fromName(screen.bucket),
                    onNavigateBack = { navigateBack() }
                )
                Screen.AiCleanupStyles -> AiCleanupStylesScreen(
                    onNavigateBack = { navigateBack() },
                    onNavigateTo = { navigateTo(it) }
                )
                is Screen.AiStylePicker -> AiStylePickerScreen(
                    styleId = screen.styleId,
                    onNavigateBack = { navigateBack() }
                )
                Screen.Agents -> AgentsScreen(
                    onNavigateBack = { navigateBack() }
                )
            }
        }
            FluenceFeedbackHost(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .displayCutoutPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Md)
            )
        }
    }

    val colors = PrecisionTheme.colors
    if (usePermanentDrawer) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    modifier = Modifier.width(PermanentDrawerWidth),
                    drawerContainerColor = colors.sidebar,
                ) {
                    FluenceDrawer(
                        current = current,
                        onNavigate = { navigateFromDrawer(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        ) {
            DrawerContent()
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
            drawerContent = {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.80f),
                    color = colors.sidebar,
                    contentColor = colors.textPrimary,
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
            DrawerContent()
        }
    }
}
