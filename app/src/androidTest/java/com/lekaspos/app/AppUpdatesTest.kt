package com.lekaspos.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.core.update.Update
import com.lekaspos.util.Log
import java.io.File
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Updates from GitHub (D-059): GitHub's JSON read; the release list and a release's APK reached over
 * HTTPS from this Android (Android 5's certificates included); the download checked against GitHub's
 * SHA-256; the APK's package, build and signing key read as the update check does.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdatesTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** An updater with its own settings and folder (the app's stay untouched). */
    private fun fresh(): AppUpdates {
        ctx.getSharedPreferences(TEST, Context.MODE_PRIVATE).edit().clear().commit()
        File(ctx.filesDir, TEST).deleteRecursively()
        return AppUpdates(ctx, prefsName = TEST, dirName = TEST)
    }

    private fun note(text: String) {
        android.util.Log.i(Log.TAG, text)
        // Also in the test run's own output (CI keeps it whole; the old emulators' logcat is cut short).
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$text\n") })
    }

    @Test
    fun releaseListsAndSingleReleasesAreRead() {
        val list = ReleaseJson.read(
            StringReader(
                """
                [
                  {"tag_name": "v1.7.0", "draft": false, "prerelease": true, "body": null, "assets": [],
                   "author": {"login": "x", "id": 1}},
                  {"url": "https://api.github.com/x", "tag_name": "v1.6.0", "draft": false, "prerelease": false,
                   "body": "### What's new\n- Updates in the app", "published_at": "2026-10-03T00:00:00Z",
                   "assets": [
                     {"name": "LekasPOS-1.6.0.apk", "size": 1400000, "digest": "sha256:$SHA_152",
                      "content_type": "application/vnd.android.package-archive",
                      "browser_download_url": "https://github.com/FaizoKen/LekasPOS/releases/download/v1.6.0/LekasPOS-1.6.0.apk",
                      "uploader": {"login": "x"}},
                     {"name": "mapping-1.6.0.txt", "size": 9, "digest": null, "browser_download_url": "https://x/y"}
                   ]}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("v1.7.0", "v1.6.0"), list.map { it.tag })
        assertTrue(list[0].prerelease)
        assertEquals("", list[0].body)
        assertEquals("### What's new\n- Updates in the app", list[1].body)
        assertEquals(2, list[1].assets.size)
        assertEquals(1_400_000L, list[1].assets[0].size)
        assertEquals("sha256:$SHA_152", list[1].assets[0].digest)
        assertEquals("", list[1].assets[1].digest)

        val one = ReleaseJson.read(StringReader("""{"tag_name": "v1.5.2", "draft": false, "prerelease": false, "assets": []}"""))
        assertEquals("v1.5.2", one.single().tag)
    }

    /** GitHub answers this Android's check; skipped without internet or when GitHub limits this address. */
    @Test
    fun theLatestReleaseIsAskedFromGitHub() = runBlocking {
        val s = fresh().check()
        note("Update check: problem ${s.problem}, update ${s.update?.version}; the phone's trust store: ${PublicTrust.systemRefusal ?: "accepted"}")
        assumeTrue("no internet", s.problem != AppUpdates.Problem.OFFLINE)
        assumeTrue("GitHub busy or limiting this address", s.problem != AppUpdates.Problem.SERVER)
        assertNull(s.problem)
        assertTrue(s.checkedAt > 0L)
        assertFalse(s.checking)
    }

    /**
     * Release 1.5.2's APK (fixed for good) downloaded from GitHub and checked: GitHub's SHA-256, the
     * package and build, the release key. This test build is another package (".debug"), so it is
     * refused as another app.
     */
    @Test
    fun aReleaseIsDownloadedCheckedAndReadLikeAnUpdate() = runBlocking {
        val updates = fresh()
        val apk = File(File(ctx.filesDir, TEST), "LekasPOS-1.5.2.apk")
        val problem = updates.fetch(RELEASE_152, apk)
        note("Update download: problem $problem; the phone's trust store: ${PublicTrust.systemRefusal ?: "accepted"}")
        assumeTrue("no internet", problem != AppUpdates.Problem.OFFLINE)
        assertNull(problem)
        assertEquals(RELEASE_152.size, apk.length())
        assertFalse(File(apk.path + ".part").exists())

        val info = assertNotNull(updates.archiveInfo(apk), "Android could not read the release APK")
        assertEquals("com.lekaspos.app", info.packageName)
        assertEquals("1.5.2", info.versionName)
        assertEquals(setOf(RELEASE_KEY), AppUpdates.signers(info))
        assertEquals(AppUpdates.Problem.WRONG_APP, updates.verify(apk)) // com.lekaspos.app.debug here
    }

    @Test
    fun aFileThatIsNotTheListedOneIsDeleted() = runBlocking {
        val updates = fresh()
        val apk = File(File(ctx.filesDir, TEST), "LekasPOS-1.5.2.apk")
        // GitHub sends more bytes than listed: stopped early, nothing kept.
        val problem = updates.fetch(RELEASE_152.copy(size = 50_000L), apk)
        assumeTrue("no internet", problem != AppUpdates.Problem.OFFLINE)
        assertEquals(AppUpdates.Problem.DAMAGED, problem)
        assertFalse(apk.exists())
        assertTrue(File(ctx.filesDir, TEST).listFiles().orEmpty().isEmpty())
    }

    private companion object {
        const val TEST = "test_updates"
        const val SHA_152 = "bd0dc579546d0349edc3d9f1dd333b1953da478781d164f5f0440eacb6630efd"

        /** The release key's certificate (docs/BUILD.md "Signing"). */
        const val RELEASE_KEY = "0a67abecd6cb31634eaca5edab9737be7f940ce9a86919b8f47e1ad99ab7d4b9"

        val RELEASE_152 = Update(
            version = "1.5.2",
            url = "https://github.com/FaizoKen/LekasPOS/releases/download/v1.5.2/LekasPOS-1.5.2.apk",
            size = 1_392_493L,
            sha256 = SHA_152,
            test = false,
            notes = "",
        )
    }
}
