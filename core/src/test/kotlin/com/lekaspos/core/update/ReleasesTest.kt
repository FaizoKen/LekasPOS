package com.lekaspos.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {

    private val sha = "bd0dc579546d0349edc3d9f1dd333b1953da478781d164f5f0440eacb6630efd"

    private fun apk(version: String, name: String = "LekasPOS-$version.apk", digest: String = "sha256:$sha", size: Long = 1_392_493L) =
        Releases.Asset(name, "https://github.com/FaizoKen/LekasPOS/releases/download/v$version/$name", size, digest)

    private fun release(
        version: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<Releases.Asset> = listOf(apk(version), apk(version, Releases.APK_NAME)),
    ) = Releases.Release("v$version", draft, prerelease, "notes $version", assets)

    @Test
    fun versionsAreReadFromTagsAndVersionNames() {
        assertEquals(listOf(1, 5, 2), Releases.version("v1.5.2"))
        assertEquals(listOf(1, 5, 2), Releases.version("1.5.2"))
        assertEquals(listOf(1, 5, 2), Releases.version("1.5.2-debug"))
        assertEquals(listOf(0, 6, 0), Releases.version("v0.6.0-phase6-fix1"))
        assertEquals(listOf(2), Releases.version("2"))
        assertEquals(listOf(1, 6), Releases.version("1.6."))
        assertNull(Releases.version("latest"))
        assertNull(Releases.version(""))
        assertNull(Releases.version("v99999999999.1"))
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Releases.isNewer("1.10.0", "1.9.9"))
        assertTrue(Releases.isNewer("v1.6.0", "1.5.2-debug"))
        assertTrue(Releases.isNewer("2.0", "1.99.99"))
        assertFalse(Releases.isNewer("1.6", "1.6.0"))
        assertFalse(Releases.isNewer("1.5.2", "1.5.2"))
        assertFalse(Releases.isNewer("1.5.1", "1.5.2"))
        assertFalse(Releases.isNewer("nightly", "1.5.2"))
    }

    @Test
    fun theNewestNewerReleaseIsChosen() {
        val u = Releases.choose(listOf(release("1.5.2"), release("1.7.0"), release("1.6.0")), "1.5.2", tests = false)
        assertEquals("1.7.0", u?.version)
        assertEquals("https://github.com/FaizoKen/LekasPOS/releases/download/v1.7.0/LekasPOS-1.7.0.apk", u?.url)
        assertEquals(1_392_493L, u?.size)
        assertEquals(sha, u?.sha256)
        assertEquals(false, u?.test)
        assertEquals("notes 1.7.0", u?.notes)
    }

    @Test
    fun theSameOrAnOlderVersionIsNoUpdate() {
        assertNull(Releases.choose(listOf(release("1.5.2")), "1.5.2", tests = false))
        assertNull(Releases.choose(listOf(release("1.5.0")), "1.5.2", tests = true))
        // A test version installed by hand is newer than the latest release.
        assertNull(Releases.choose(listOf(release("1.6.0")), "1.6.1", tests = false))
    }

    @Test
    fun testVersionsOnlyWhenAskedForAndNeverDrafts() {
        val list = listOf(release("1.6.0"), release("1.7.0", prerelease = true), release("1.8.0", draft = true))
        assertEquals("1.6.0", Releases.choose(list, "1.5.2", tests = false)?.version)
        val test = Releases.choose(list, "1.5.2", tests = true)
        assertEquals("1.7.0", test?.version)
        assertEquals(true, test?.test)
    }

    @Test
    fun aReleaseWithoutAUsableApkIsSkipped() {
        val noDigest = release("1.8.0", assets = listOf(apk("1.8.0", digest = "")))
        val plainHttp = release("1.7.5", assets = listOf(apk("1.7.5").let { Releases.Asset(it.name, "http://example.com/a.apk", it.size, it.digest) }))
        val huge = release("1.7.2", assets = listOf(apk("1.7.2", size = Releases.MAX_APK_BYTES + 1)))
        val other = release("1.7.1", assets = listOf(apk("1.7.1", name = "mapping-1.7.1.txt")))
        val good = release("1.6.0")
        assertEquals("1.6.0", Releases.choose(listOf(noDigest, plainHttp, huge, other, good), "1.5.2", tests = false)?.version)
    }

    @Test
    fun theVersionedApkIsPreferredThenTheFixedName() {
        val both = release("1.6.0", assets = listOf(apk("1.6.0", Releases.APK_NAME, size = 5L), apk("1.6.0", size = 6L)))
        assertEquals(6L, Releases.apk(both, "1.6.0")?.size)
        val fixedOnly = release("1.6.0", assets = listOf(apk("1.6.0", Releases.APK_NAME, size = 5L)))
        assertEquals(5L, Releases.apk(fixedOnly, "1.6.0")?.size)
    }

    @Test
    fun digestsAreGitHubsSha256() {
        assertEquals(sha, Releases.sha256("sha256:$sha"))
        assertEquals(sha, Releases.sha256("sha256:${sha.uppercase()}"))
        assertNull(Releases.sha256("sha512:$sha"))
        assertNull(Releases.sha256("sha256:${sha.dropLast(1)}"))
        assertNull(Releases.sha256("sha256:${sha.dropLast(1)}x"))
    }

    // ---- release notes -------------------------------------------------------------------------

    /** The notes of release 1.5.2, shortened. */
    private val notes = """
        **LekasPOS 1.5.2** — reports fast on big stores. Download `LekasPOS.apk` below.

        ### What's new since 1.5.0
        - **Reports open many times faster on big stores**, most of all on Android 7–10 tablets.
        - **The Popular tab shows at once**, also right after the app is opened;
          its ranking is refreshed in the background.
        - **Search**: two words (e.g. "milo susu") are faster; see [the website](https://faizoken.github.io/LekasPOS/#download).

        Coming from an older version? See what changed in [1.5.0](https://github.com/FaizoKen/LekasPOS/releases/tag/v1.5.0).

        ### Checked
        - 224/224 emulator tests.
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun whatsNewIsTheListUnderItsHeadingInPlainText() {
        assertEquals(
            listOf(
                "Reports open many times faster on big stores, most of all on Android 7–10 tablets.",
                "The Popular tab shows at once, also right after the app is opened; its ranking is refreshed in the background.",
                "Search: two words (e.g. \"milo susu\") are faster; see the website.",
            ),
            ReleaseNotes.whatsNew(notes, "en"),
        )
    }

    @Test
    fun malayNotesWhenTheReleaseHasThemOtherwiseEnglish() {
        assertEquals(3, ReleaseNotes.whatsNew(notes, "ms").size)
        val both = "$notes\n\n## Apa yang baharu\n* **Laporan** lebih pantas.\n"
        assertEquals(listOf("Laporan lebih pantas."), ReleaseNotes.whatsNew(both, "ms"))
        assertEquals(3, ReleaseNotes.whatsNew(both, "en").size)
    }

    @Test
    fun notesWithoutTheHeadingHaveNoList() {
        assertEquals(emptyList(), ReleaseNotes.whatsNew("Bug fixes.\n- one\n", "en"))
        assertEquals(emptyList(), ReleaseNotes.whatsNew("", "ms"))
    }

    @Test
    fun markdownMarksAreTakenOut() {
        assertEquals("Faster reports and a new screen", ReleaseNotes.plain("**Faster** __reports__ and `a` [new screen](https://x.y/z)"))
        assertEquals("a [b] c * d", ReleaseNotes.plain("a [b] c * d"))
    }
}
