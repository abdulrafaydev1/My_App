package com.instaguard.app.model

/**
 * Defines the 4 supported social media platforms and their package variants.
 */
enum class TrackedApp(
    val key: String,
    val displayName: String,
    val iconEmoji: String,
    val packageNames: List<String>
) {
    INSTAGRAM(
        key = "INSTAGRAM",
        displayName = "Instagram",
        iconEmoji = "📸",
        packageNames = listOf(
            "com.instagram.android",
            "com.instagram.lite"
        )
    ),
    YOUTUBE(
        key = "YOUTUBE",
        displayName = "YouTube",
        iconEmoji = "▶️",
        packageNames = listOf(
            "com.google.android.youtube"
        )
    ),
    FACEBOOK(
        key = "FACEBOOK",
        displayName = "Facebook",
        iconEmoji = "📘",
        packageNames = listOf(
            "com.facebook.katana",
            "com.facebook.lite"
        )
    ),
    TIKTOK(
        key = "TIKTOK",
        displayName = "TikTok",
        iconEmoji = "🎵",
        packageNames = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go"
        )
    );

    companion object {
        fun fromPackageName(packageName: String): TrackedApp? {
            return entries.firstOrNull { app ->
                app.packageNames.contains(packageName)
            }
        }

        fun fromKey(key: String): TrackedApp? {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        }
    }
}
