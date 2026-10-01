package com.lekaspos.ui

import android.content.res.Configuration
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.testing.TestDb
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → App language (D-046): screens use the chosen language, whatever the phone's. */
@RunWith(AndroidJUnit4::class)
class AppLanguageTest {

    private val ctx get() = TestDb.context

    @After
    fun tearDown() = AppLanguage.set(ctx, AppLanguage.PHONE)

    @Test
    fun theChosenLanguageIsUsedAndRemembered() {
        AppLanguage.set(ctx, AppLanguage.MALAY)
        assertEquals("Bahasa aplikasi", AppLanguage.wrap(ctx).getString(R.string.settings_language))
        AppLanguage.set(ctx, AppLanguage.ENGLISH)
        assertEquals("App language", AppLanguage.wrap(ctx).getString(R.string.settings_language))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.get(ctx))
    }

    /**
     * Only the language is overridden (2026-10 review): a full copy of the configuration kept the
     * first orientation and size after a rotation; a careless delta would reset the font size or
     * (below API 24) the screen size class.
     */
    @Test
    fun onlyTheLanguageIsOverridden() {
        val base = ctx.resources.configuration
        AppLanguage.set(ctx, AppLanguage.MALAY)
        val c = AppLanguage.wrap(ctx).resources.configuration
        @Suppress("DEPRECATION")
        val locale = if (Build.VERSION.SDK_INT >= 24) c.locales[0] else c.locale
        assertEquals("ms", locale.language)
        assertEquals(base.fontScale, c.fontScale)
        assertEquals(base.orientation, c.orientation)
        assertEquals(base.screenWidthDp, c.screenWidthDp)
        assertEquals(base.smallestScreenWidthDp, c.smallestScreenWidthDp)
        assertEquals(base.densityDpi, c.densityDpi)
        val size = Configuration.SCREENLAYOUT_SIZE_MASK or Configuration.SCREENLAYOUT_LONG_MASK
        assertEquals(base.screenLayout and size, c.screenLayout and size)
        assertEquals(base.uiMode, c.uiMode)
    }

    @Test
    fun phoneLanguageLeavesTheContextAlone() {
        AppLanguage.set(ctx, AppLanguage.PHONE)
        assertEquals(ctx, AppLanguage.wrap(ctx))
    }
}
