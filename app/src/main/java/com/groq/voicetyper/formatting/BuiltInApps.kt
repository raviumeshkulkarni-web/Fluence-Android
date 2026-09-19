package com.groq.voicetyper.formatting

/**
 * Wispr-style built-in app groups with default formatting styles.
 *
 * Pure Kotlin, no Android imports (JVM-testable). These are CODE DEFAULTS,
 * not user data: an explicit per-package user override in
 * [FormattingPreferences] always wins over this map. Unknown packages fall
 * through to field-type detection, then NEUTRAL.
 *
 * Group defaults: EMAIL -> FORMAL, PERSONAL -> CASUAL, WORK -> CASUAL,
 * OTHER (unlisted) -> field-type/NEUTRAL.
 */
enum class AppGroup {
    EMAIL,
    PERSONAL,
    WORK,
    OTHER;

    companion object {
        fun label(group: AppGroup): String = when (group) {
            EMAIL -> "Email"
            PERSONAL -> "Personal messages"
            WORK -> "Work messages"
            OTHER -> "Other apps"
        }

        fun defaultStyle(group: AppGroup): FormattingCategory = when (group) {
            EMAIL -> FormattingCategory.FORMAL
            PERSONAL -> FormattingCategory.CASUAL
            WORK -> FormattingCategory.CASUAL
            OTHER -> FormattingCategory.NEUTRAL
        }
    }
}

object BuiltInApps {

    private val EMAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        "com.samsung.android.email.provider",
        "com.superhuman.android",
        "ch.protonmail.android",
        "com.edisonmail.oh",
        "com.readdle.spark",
        "com.tutanota.android",
        "com.fsck.k9"
    )

    private val PERSONAL_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "com.discord",
        "com.instagram.android",
        "com.facebook.orca",
        "org.thoughtcrime.securesms",
        "com.snapchat.android",
        "com.tencent.mm",
        "jp.naver.line.android",
        "com.viber.voip",
        "com.imo.android.imoim"
    )

    private val WORK_PACKAGES = setOf(
        "com.Slack",
        "com.microsoft.teams",
        "com.linkedin.android",
        "us.zoom.videomeetings",
        "com.google.android.apps.meet",
        "com.cisco.webex.meetings",
        "com.skype.raider"
    )

    /** Package -> default style, derived from the group lists above. */
    val PACKAGE_CATEGORY: Map<String, FormattingCategory> by lazy {
        buildMap {
            EMAIL_PACKAGES.forEach { put(it, FormattingCategory.FORMAL) }
            PERSONAL_PACKAGES.forEach { put(it, FormattingCategory.CASUAL) }
            WORK_PACKAGES.forEach { put(it, FormattingCategory.CASUAL) }
        }
    }

    /** Group membership for settings UI sections. Unlisted -> OTHER. */
    fun groupFor(packageName: String?): AppGroup {
        if (packageName.isNullOrBlank()) return AppGroup.OTHER
        return when {
            EMAIL_PACKAGES.contains(packageName) -> AppGroup.EMAIL
            PERSONAL_PACKAGES.contains(packageName) -> AppGroup.PERSONAL
            WORK_PACKAGES.contains(packageName) -> AppGroup.WORK
            else -> AppGroup.OTHER
        }
    }
}
