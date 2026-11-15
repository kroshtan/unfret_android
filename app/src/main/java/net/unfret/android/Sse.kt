package net.unfret.android

import java.io.BufferedReader

/** One server-sent event. */
data class SseEvent(val event: String, val data: String)

/**
 * Reads `text/event-stream` (the WHATWG rules the extension's parser follows): `event:` and `data:` fields, data
 * lines joined with newlines, an event per blank line, comments (`:`) and unknown fields ignored.
 */
class SseReader(private val reader: BufferedReader) {
    /** The next event, or null at the end of the stream. */
    fun next(): SseEvent? {
        var event = ""
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = reader.readLine()
            if (line == null) {
                return if (hasData) SseEvent(event.ifEmpty { "message" }, data.toString()) else null
            }
            if (line.isEmpty()) {
                if (hasData) return SseEvent(event.ifEmpty { "message" }, data.toString())
                event = ""
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
    }
}
