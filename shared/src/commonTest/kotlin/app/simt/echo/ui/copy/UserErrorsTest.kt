package app.simt.echo.ui.copy

import app.simt.echo.transfer.RouteExpiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Copy snapshot gate (plan W7-06): the typed user-error surface stays
 * human — no raw ids, no epoch millis, no exception/server text — and the
 * date formatter renders a human UTC date. Every screen error is what
 * happened, what is safe, and the next action (three sentences).
 */
class UserErrorsTest {

    private fun assertHuman(copy: String) {
        assertTrue(copy.isNotBlank(), "copy must not be blank")
        assertFalse(copy.contains("route-"), "raw route id in: $copy")
        assertFalse(copy.contains("draft-"), "raw draft id in: $copy")
        assertFalse(copy.contains("Exception"), "exception kind in: $copy")
    }

    private fun assertThreePart(copy: String) {
        // What happened. What is safe. Next action.
        assertEquals(3, copy.split(". ").size, "three-sentence shape in: $copy")
        assertTrue(copy.endsWith("."), "copy ends with a period: $copy")
    }

    @Test
    fun everyUserErrorIsThreePartHumanCopy() {
        listOf(
            MISSING_ROUTE_ERROR.message(),
            MISSING_DRAFT_ERROR.message(),
            shareErrorCopy(IllegalStateException("boom")),
            shareUpdateErrorCopy(),
            shareExportErrorCopy(),
            receiveSaveErrorCopy(),
            receiveFileErrorCopy(),
            processingErrorCopy(),
            processingJobErrorCopy(),
            processingJobGoneCopy(),
            processingJobTimeoutCopy(),
            processingNoDraftCopy(),
            reviewLoadErrorCopy(),
            reviewSuggestionsErrorCopy(),
            reviewLocalErrorCopy(),
            reviewUseSuggestionErrorCopy(),
            reviewSaveLandmarkErrorCopy(),
            reviewSaveErrorCopy(),
            recordStartErrorCopy(),
            recordStopErrorCopy(),
            recordSaveErrorCopy(),
            recordCameraErrorCopy(),
            recordResumeErrorCopy(),
            settingsExportErrorCopy(),
        ).forEach {
            assertHuman(it)
            assertThreePart(it)
        }
    }

    @Test
    fun shareExpiredNamesExpiryWithoutIds() {
        val copy = shareErrorCopy(RouteExpiredException(1_700_000_000_000L))
        assertHuman(copy)
        assertTrue(copy.contains("expired", ignoreCase = true))
        // The stored epoch never leaks into the message.
        assertFalse(copy.contains("1700000000000"))
    }

    @Test
    fun shareGenericNeverEchoesExceptionText() {
        val copy = shareErrorCopy(IllegalStateException("KMS-500 queue full, job abc-123"))
        assertFalse(copy.contains("KMS-500"), "server text leaked: $copy")
        assertFalse(copy.contains("abc-123"), "server id leaked: $copy")
        assertTrue(copy.contains("Nothing was shared"))
    }

    @Test
    fun missingRouteCopyNeverNamesAnId() {
        assertEquals("Route not found.", MISSING_ROUTE_ERROR.whatHappened)
        assertFalse(MISSING_ROUTE_ERROR.message().contains("uuid"))
    }

    @Test
    fun epochZeroFormatsAsHumanUtcDate() {
        assertEquals("1 Jan 1970, 0:00 UTC", formatEpochUtc(0L))
        assertEquals("2 Jan 1970, 0:00 UTC", formatEpochUtc(86_400_000L))
        assertEquals("31 Dec 1969, 23:59 UTC", formatEpochUtc(-60_000L))
    }

    @Test
    fun expiryAndCreatedLinesNeverShowRawEpochs() {
        assertEquals("No expiry", expiryCopy(null))
        val expiry = expiryCopy(1_700_000_000_000L)
        assertTrue(expiry.startsWith("Expires "))
        assertFalse(expiry.contains("1700000000000"), "raw epoch in: $expiry")
        val created = createdCopy(1_700_000_000_000L)
        assertTrue(created.startsWith("Created "))
        assertFalse(created.contains("1700000000000"), "raw epoch in: $created")
    }
}
