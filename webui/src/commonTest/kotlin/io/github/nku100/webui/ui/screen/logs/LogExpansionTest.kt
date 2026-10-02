package io.github.nku100.webui.ui.screen.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogExpansionTest {
    @Test
    fun logBodyShowsOnlyTheMessageInsteadOfRepeatingTheRawTimestamp() {
        val line = parseLogLine("09-28 12:34:56.789  1234  5678 I SampleHook: module initialized")

        assertEquals("module initialized", line.displayText())
    }

    @Test
    fun processMetadataIsAvailableForShortLogsWithoutExpandingThem() {
        val line = parseLogLine("W/SampleHook(1234): warning")

        assertEquals("PID 1234", line.processMetadata())
    }

    @Test
    fun processMetadataShowsPidAndTidForThreadtimeLogs() {
        val line = parseLogLine("09-28 12:34:56.789  1234  5678 I SampleHook: initialized")

        assertEquals("PID 1234 · TID 5678", line.processMetadata())
    }

    @Test
    fun tappingAnotherLogExpandsItAndReplacesTheCurrentOne() {
        assertEquals(1, toggleExpandedLog(0, 1))
    }

    @Test
    fun tappingExpandedLogCollapsesIt() {
        assertNull(toggleExpandedLog(0, 0))
    }
}
