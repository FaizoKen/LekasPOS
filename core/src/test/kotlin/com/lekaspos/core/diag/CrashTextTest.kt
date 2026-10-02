package com.lekaspos.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CrashTextTest {

    private val crash = """
        java.lang.RuntimeException: Unable to start activity ComponentInfo{com.lekaspos.app/com.lekaspos.ui.sell.SellActivity}
        	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2416)
        	at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2476)
        Caused by: java.lang.IllegalStateException: line 12 of "Kopi O" is invalid
        	at com.lekaspos.domain.sell.CartSession${'$'}commit${'$'}2.invokeSuspend(SourceFile:812)
        	at com.lekaspos.domain.sell.CartSession.commit(SourceFile:790)
        	at com.lekaspos.ui.sell.SellActivity${'$'}${'$'}ExternalSyntheticLambda3.onClick(SourceFile:0)
        	at l.a.b(SourceFile:3)
        	... 12 more
    """.trimIndent()

    @Test
    fun aTraceIsReadAsItsChainOfExceptions() {
        val chain = CrashText.parse(crash)
        assertEquals(listOf("java.lang.RuntimeException", "java.lang.IllegalStateException"), chain.map { it.className })
        assertEquals(2, chain[0].frames.size)
        assertEquals(4, chain[1].frames.size)
        assertTrue(chain[1].frames[0].inApp)
        assertFalse(chain[1].frames[3].inApp)
    }

    @Test
    fun theTitleNamesTheCauseAndWhereInTheApp() {
        assertEquals("IllegalStateException in CartSession.commit", CrashText.ofThrowable(crash).title)
    }

    @Test
    fun theSameBugInAnotherBuildHasTheSameFingerprint() {
        // Another build: other line numbers, other lambda numbers, another library name, another message.
        val later = crash
            .replace("SourceFile:812", "SourceFile:845").replace("SourceFile:790", "SourceFile:801")
            .replace("commit\$2", "commit\$3").replace("Lambda3", "Lambda7").replace("l.a.b", "l.c.d")
            .replace("line 12", "line 13")
        assertEquals(CrashText.ofThrowable(crash).fingerprint, CrashText.ofThrowable(later).fingerprint)
        assertEquals(16, CrashText.ofThrowable(crash).fingerprint.length)
    }

    @Test
    fun anotherPlaceIsAnotherBug() {
        val elsewhere = crash.replace("CartSession.commit(", "CartSession.hold(")
        assertNotEquals(CrashText.ofThrowable(crash).fingerprint, CrashText.ofThrowable(elsewhere).fingerprint)
        val otherType = crash.replace("IllegalStateException", "NullPointerException")
        assertNotEquals(CrashText.ofThrowable(crash).fingerprint, CrashText.ofThrowable(otherType).fingerprint)
    }

    @Test
    fun aLoggedErrorIsKnownByItsMessageWithoutNumbers() {
        val a = CrashText.ofMessage("Database check failed: page 1234 is damaged")
        val b = CrashText.ofMessage("Database check failed: page 77 is damaged")
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals("Database check failed: page # is damaged", a.title)
        val withTrace = CrashText.ofThrowable(crash, "Automatic backup failed")
        assertEquals("Automatic backup failed: IllegalStateException", withTrace.title)
        assertNotEquals(CrashText.ofThrowable(crash).fingerprint, withTrace.fingerprint)
    }

    @Test
    fun anAnrIsKnownByWhereTheMainThreadWasStuck() {
        val anr = """
            ----- pid 1234 at 2026-10-02 10:00:00 -----
            Cmd line: com.lekaspos.app

            "Signal Catcher" daemon prio=5 tid=2 Runnable
              at java.lang.Object.wait(Native method)

            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1 dsCount=0 obj=0x7 self=0x7
              at android.database.sqlite.SQLiteConnectionPool.waitForConnection(SQLiteConnectionPool.java:1012)
              - waiting to lock <0x0abc> (a java.lang.Object)
              at com.lekaspos.data.db.Db.read(SourceFile:120)
              at com.lekaspos.ui.sell.SellActivity.onCreate(SourceFile:88)
              at android.app.Activity.performCreate(Activity.java:8000)

            "DefaultDispatcher-worker-1" prio=5 tid=12 Runnable
              at com.lekaspos.domain.sell.CartSession.load(SourceFile:10)
        """.trimIndent()
        val key = CrashText.ofAnr(anr)
        assertEquals("App not responding in Db.read", key.title)
        assertEquals(CrashText.ofAnr(anr.replace("SourceFile:120", "SourceFile:121")).fingerprint, key.fingerprint)
        val main = CrashText.mainThreadText(anr)
        assertTrue(main.startsWith("\"main\""))
        assertFalse(main.contains("DefaultDispatcher"))
    }

    @Test
    fun personalDetailsAreTakenOut() {
        assertEquals("mail <email> now", CrashText.scrub("mail siti.aminah@gmail.com now"))
        assertEquals("printer <bt-address> off", CrashText.scrub("printer 00:11:22:AA:bb:CC off"))
        assertEquals("open content://… failed", CrashText.scrub("open content://com.android.externalstorage.documents/tree/primary%3AKedai%20Ali failed"))
        assertEquals("no /storage/… here", CrashText.scrub("no /storage/emulated/0/Kedai Ali/backup.zip here".replace("Kedai Ali", "KedaiAli")))
        assertEquals("For input string: \"…\"", CrashText.scrub("For input string: \"Ali 012\""))
        assertEquals("call <number> or <number>", CrashText.scrub("call 0123456789 or 9556001234567"))
        assertEquals("line 12 at SourceFile:812", CrashText.scrub("line 12 at SourceFile:812")) // short numbers stay
    }

    @Test
    fun aReportIsCutToTheRelaysLimits() {
        val r = ErrorReport(
            kind = ErrorReport.CRASH, fingerprint = "0123456789abcdef", title = "t".repeat(500), at = 2, app = "1.5.0",
            build = 90, signing = "release", android = "5.0", sdk = 21, device = "x", ramMb = 1, freeMb = 1, dbMb = 1,
            lang = "en", trace = "a".repeat(20_000), log = "old" + "b".repeat(10_000),
        ).clipped()
        assertEquals(ErrorReport.MAX_TITLE, r.title.length)
        assertEquals(ErrorReport.MAX_TRACE, r.trace.length)
        assertEquals(ErrorReport.MAX_LOG, r.log.length)
        assertFalse(r.log.startsWith("old")) // the newest lines are kept
        val again = r.again(1)
        assertEquals(2, again.count)
        assertEquals(1, again.firstAt)
        assertEquals(2, again.at)
    }
}
