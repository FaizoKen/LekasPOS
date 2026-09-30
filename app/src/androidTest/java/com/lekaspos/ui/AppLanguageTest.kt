package com.lekaspos.ui

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

    @Test
    fun phoneLanguageLeavesTheContextAlone() {
        AppLanguage.set(ctx, AppLanguage.PHONE)
        assertEquals(ctx, AppLanguage.wrap(ctx))
    }
}
