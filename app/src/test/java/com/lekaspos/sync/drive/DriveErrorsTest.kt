package com.lekaspos.sync.drive

import com.lekaspos.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-10 review: a full Google Drive showed "Drive HTTP 403: { "error": … }" in English with "it is
 * tried again automatically". Drive's reasons now become codes the screens translate.
 */
class DriveErrorsTest {

    private fun error(code: Int, reason: String) = DriveProvider.HttpError(
        code,
        """{ "error": { "errors": [ { "domain": "usageLimits", "reason": "$reason", "message": "x" } ], "code": $code } }""",
    )

    /** Google's own answer: a long message before the reason (it was cut off at 300 characters). */
    @Test
    fun theReasonIsReadFromTheWholeAnswer() {
        val message = "The user has exceeded their Drive storage quota. ".repeat(10)
        val body = """{
 "error": {
  "code": 403,
  "message": "$message",
  "errors": [ { "message": "$message", "domain": "usageLimits", "reason": "storageQuotaExceeded" } ]
 }
}"""
        val e = DriveProvider.HttpError.of(403, body)
        assertEquals("storageQuotaExceeded", e.reason)
        assertEquals(SyncEngine.ERROR_DRIVE_FULL, SyncEngine.errorCode(e))
        assertTrue((e.message?.length ?: 0) < 400) // the shown text stays short
    }

    @Test
    fun drivesReasonsBecomeCodes() {
        assertEquals(SyncEngine.ERROR_DRIVE_FULL, SyncEngine.errorCode(error(403, "storageQuotaExceeded")))
        assertEquals(SyncEngine.ERROR_DRIVE_BUSY, SyncEngine.errorCode(error(403, "userRateLimitExceeded")))
        assertEquals(SyncEngine.ERROR_DRIVE_BUSY, SyncEngine.errorCode(error(429, "rateLimitExceeded")))
        assertEquals(SyncEngine.ERROR_DRIVE_BUSY, SyncEngine.errorCode(DriveProvider.HttpError(503, "")))
        assertEquals(SyncEngine.ERROR_SIGN_IN, SyncEngine.errorCode(error(403, "insufficientPermissions")))
        assertEquals("Drive HTTP 404: download failed", SyncEngine.errorCode(DriveProvider.HttpError(404, "download failed")))
        assertEquals(SyncEngine.ERROR_OFFLINE, SyncEngine.errorCode(javax.net.ssl.SSLHandshakeException("portal")))
    }
}
