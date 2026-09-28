package io.github.nku100.webui.ui.screen.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogParsingTest {
    @Test
    fun threadtimeFormatKeepsTimestampAndProcessIdsAsFields() {
        val raw = "09-28 12:34:56.789  1234  5678 I SampleHook: module initialized"

        val line = parseLogLine(raw)

        assertEquals("09-28 12:34:56.789", line.timestamp)
        assertEquals("1234", line.pid)
        assertEquals("5678", line.tid)
        assertEquals("module initialized", line.message)
        assertEquals(raw, line.raw)
    }

    @Test
    fun briefFormatKeepsItsPidWithoutInventingTimestampOrTid() {
        val line = parseLogLine("W/SampleHook(1234): warning")

        assertNull(line.timestamp)
        assertEquals("1234", line.pid)
        assertNull(line.tid)
    }
}
