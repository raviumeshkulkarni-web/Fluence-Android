# ADR 001: Headers with Joint Cards Pattern & Full-Width Separators

## Status
Accepted

## Context
Across the Fluence desktop ecosystem (Windows client / Codex architecture), settings and configurable screens adhere to a disciplined layout architecture:
1. **Section Headers Outside Cards**: Section titles and descriptive text live outside the card containers. This establishes visual hierarchy before the interactive surface is encountered.
2. **Joint Card Containers**: Sub-settings and options are grouped inside unified cards with 12dp rounded corners (`FluenceShapes.Medium`), `cardSurface` background, and a 1dp `cardBorder` stroke.
3. **Full-Width Border-to-Border Separators**: Dividers inside cards must extend 100% to the outer borders (`HorizontalDivider(color = colors.outlineSubtle, thickness = 1.dp)`) without start/end insets or padding.

Previously on Android, several screens deviated from this pattern:
- Standalone floating cards with duplicate headers inside the card.
- Inconsistent section dividers containing horizontal insets (e.g., `padding(start = 76.dp)` or `padding(horizontal = 20.dp)`).
- Loose lists of settings rows without container joint cards.

## Decision
1. Standardize on `SettingsSectionHeader` and `SettingsJointCard` (defined in `com.groq.voicetyper.Components.kt`) across all Android settings sub-screens:
   - `SettingsScreen.kt`:
     - Section 1: "Transcription" (AI Transcription, AI Agent Mode, Offline Transcription)
     - Section 2: "System & Privacy" (Permissions & Services, Privacy & App Exclusions, Google Drive Sync, Floating Bubble)
     - Section 3: "Preferences" (Media playback while dictating / Audio Focus, Appearance)
     - Section 4: "About" (About Fluence navigation row)
   - `SttConfigScreen.kt`:
     - Section 1: "Provider & Language"
     - Section 2: "Model & Mode"
     - Section 3: "Credentials & Connection"
   - `OfflineConfigScreen.kt`:
     - Section 1: "Offline Dictation"
     - Section 2: "Recognition Model" (theme-adaptive selection tint)
     - Section 3: "Model Packages"
   - `PermissionsScreen.kt`:
     - Section 1: "Required Permissions"
   - `AboutScreen.kt`:
     - Section 1: "App Updates" (deduplicated internal header removed in `UpdateCard.kt`)
     - Section 2: "Legal & Privacy"
   - `SyncScreen.kt`:
     - Section 1: "Account & Status" (border-to-border divider between account info and sync status)
     - Section 2: "Sync Settings"
     - Section 3: "Privacy & Encryption"
   - `AgentsScreen.kt` & `AgentConfigScreen.kt`:
     - Standardized into Joint Cards with external headers and edge-to-edge dividers.
   - `PrivacyExclusionsScreen.kt`:
     - Banking advisory notice placed into `SettingsJointCard`.
     - Application search results and installed apps list wrapped in a `SettingsJointCard` container with border-to-border `HorizontalDivider` between items.
   - `BubbleSettingsScreen.kt`:
     - Section 1: "Activation & Visibility" (Master switch and keyboard-visibility toggle)
     - Section 2: "Live Preview" (Unified `SettingsJointCard` container)
     - Section 3: "Day / Night Auto-Switch"
     - Section 4: "Collapsed Bubble" (Joined rows for Original, Classic, Minimal)
     - Section 5: "Recording Pill" (Joined rows for theme presets with edge-to-edge dividers)
     - Section 6: "Appearance & Opacity" (Outer glow switch and idle opacity slider)

2. Eliminate horizontal divider insets across all list items and card rows:
   - Changed all `HorizontalDivider(color = colors.outlineSubtle, modifier = Modifier.padding(start = 76.dp))` to border-to-border full-width dividers in `PrivacyExclusionsScreen.kt`, `BucketPickerScreen.kt`, and `AiStylePickerScreen.kt`.
   - Removed `SectionDivider` horizontal padding in `PermissionsScreen.kt`.
   - Verified that `SnippetsScreen.kt`, `DictionaryScreen.kt`, `FormattingScreen.kt`, `AiCleanupStylesScreen.kt`, and `HistoryScreen.kt` maintain border-to-border dividers.

3. Card surface, divider & border contrast calibration:
   - In Dark mode: `SettingsJointCard` containers use `colors.panel` (`#1E1E1E`), restoring the exact settings card shade from v1.28.0 with `colors.outlineSubtle` (`#2A2A2A`) border.
   - Dashboard isolation: The Android dashboard (`HomeScreen.kt`) remains completely frozen on `cardSurface` (`#141414`) with `cardBorder` (`0x0DFFFFFF`).
   - Crisp Full-Width Dividers: `DividerVisible = Color(0xFF383838)` (exposed as `colors.divider`) is used for all internal joint card dividers and list separators for clear edge-to-edge delineation.
   - Form Input Borders: `InputBorderDark = Color(0xFF484848)` (exposed as `colors.inputBorder`) provides crisp outlines for text and API key fields.
   - Button & Segmented Control Polish: Preserved the exact v1.28.0 `FluenceSegmentedControl` button architecture across the app (Online/Offline, Words/Sessions, chart range, History date filters, and preferences selectors).

4. Strict preservation of frozen components:
   - `HomeScreen.kt` (Dashboard) and `ActivityChart.kt` remain completely frozen and identical to v1.28.0.
   - Runtime overlay service and IME floating bubble logic remain completely untouched.

## Consequences
- **Visual Consistency & Tactility**: Android settings match the exact layout rhythm, typography, card-divider structure, and subtle premium elevation of the Windows desktop application.
- **Card Distinction**: Cards are immediately perceptible from the screen canvas in both dark and light modes using existing tokens without inventing new hex codes.
- **Maintainability**: Centralized re-usable components (`SettingsSectionHeader`, `SettingsJointCard`) reduce boilerplate across screens.
- **Touch & Accessibility**: Hit targets and semantics remain accessible (min 48dp touch targets on interactive rows), with clear boundary lines between distinct setting items.
