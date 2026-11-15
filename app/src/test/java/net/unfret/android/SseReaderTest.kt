package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class SseReaderTest {
    private fun events(text: String): List<SseEvent> {
        val reader = SseReader(BufferedReader(StringReader(text)))
        return generateSequence { reader.next() }.toList()
    }

    @Test
    fun `fields, multi-line data, comments and CRLF`() {
        assertEquals(
            listOf(SseEvent("step", "a\nb"), SseEvent("message", "plain"), SseEvent("result", "{}")),
            events(": hi\r\nevent: step\r\ndata: a\r\ndata:b\r\nid: 1\r\n\r\ndata: plain\n\nevent: result\ndata: {}"),
        )
    }

    @Test
    fun `an event without data is skipped`() {
        assertEquals(listOf(SseEvent("result", "x")), events("event: step\n\nevent: result\ndata: x\n\n"))
        assertNull(SseReader(BufferedReader(StringReader(""))).next())
    }
}
