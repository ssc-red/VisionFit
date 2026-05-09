package com.example.visionfit.util

/** Known social / short-video apps; lower index = nearer top of the Apps list. */
object SuggestedSocialApps {
    val packageOrder: List<String> = listOf(
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube",
        "com.twitter.android",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.linkedin.android",
        "com.pinterest",
        "org.telegram.messenger",
        "com.whatsapp",
        "com.discord",
        "tv.twitch.android.app",
    )

    fun rank(packageName: String): Int {
        val i = packageOrder.indexOf(packageName)
        return if (i >= 0) i else Int.MAX_VALUE
    }
}
