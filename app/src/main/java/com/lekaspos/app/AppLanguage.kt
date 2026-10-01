package com.lekaspos.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.StrictMode
import java.util.Locale

/**
 * The language of the app's screens, chosen on this phone (Settings → App language), separate
 * from the receipt language (a store setting). Empty = the phone's language.
 *
 * Every screen must be created in the chosen language, before any database is open, so the
 * choice lives in a tiny preferences file read once when the process starts (D-046) — the one
 * deliberate disk read on the main thread. Activities wrap their base context with [wrap].
 */
object AppLanguage {

    const val PHONE = ""
    const val ENGLISH = "en"
    const val MALAY = "ms"
    val ALL = listOf(PHONE, ENGLISH, MALAY)

    private const val PREFS = "lekas_ui"
    private const val KEY = "language"

    @Volatile
    private var current: String? = null

    /** The chosen language code ([PHONE] when following the phone). */
    fun get(context: Context): String = current ?: load(context)

    private fun load(context: Context): String {
        val saved = StrictMode.allowThreadDiskReads()
        return try {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, PHONE) ?: PHONE
            (if (v in ALL) v else PHONE).also { current = it }
        } finally {
            StrictMode.setThreadPolicy(saved)
        }
    }

    /** Saves the choice (written in the background); screens opened afterwards use it. */
    fun set(context: Context, language: String) {
        require(language in ALL) { "unknown language $language" }
        current = language
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    /**
     * [base] with the chosen language, for Activity/Application.attachBaseContext.
     *
     * Only the language is overridden. Android lays the override over every later configuration,
     * and a full copy kept the screen's first orientation and size once the back-office screens
     * rotated in place: dialogs were sized for the old width (2026-10 review). Everything else in
     * the delta stays undefined: fontScale 0 (below API 24 `Configuration()` means 1, which would
     * undo the user's font size) and no screenLayout bits (below API 24 a delta carrying layout
     * direction bits replaced the whole screenLayout; the locale field alone is enough there, as
     * updateFrom derives the layout direction from it).
     */
    fun wrap(base: Context): Context {
        val language = get(base)
        if (language == PHONE) return base
        val locale = Locale(language)
        val config = Configuration()
        config.fontScale = 0f
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }
}
