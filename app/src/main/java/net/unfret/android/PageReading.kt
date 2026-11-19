package net.unfret.android

import org.json.JSONObject
import org.json.JSONTokener

/**
 * The page reader's side of the WebView: the scripts the app evaluates in the page and what their answers mean.
 * The reader itself is the extension's extractor, bundled into `assets/unfret-extract.js` (see extractor/).
 */
object PageReading {
    /** The bundle's file in the app's assets. */
    const val BUNDLE_ASSET = "unfret-extract.js"

    /** Starts the extraction the extension runs (`extractPage(document, location.href, { live: true })`). */
    const val START = ";window.__unfretAndroid.start({ live: true });"

    /** Asks how it's going; a page that navigated away has lost the reader. */
    const val POLL = "(function(){var u=window.__unfretAndroid;return u?u.poll():'{\"status\":\"gone\"}';})()"

    sealed interface State {
        data object NotStarted : State

        data object Running : State

        /** The page went away (it navigated, or reloaded) while being read. */
        data object Gone : State

        data class Done(val extraction: JSONObject) : State

        data class Failed(val message: String) : State
    }

    /**
     * What [POLL] answered. `evaluateJavascript` hands back the script's value as JSON, so the reader's JSON
     * string arrives JSON-encoded a second time.
     */
    fun parsePoll(raw: String?): State {
        val inner = try {
            JSONTokener(raw ?: "null").nextValue()
        } catch (_: Exception) {
            return State.Failed("unreadable answer")
        }
        if (inner !is String) return State.Gone
        val state = try {
            JSONTokener(inner).nextValue()
        } catch (_: Exception) {
            return State.Failed("unreadable answer")
        }
        if (state !is JSONObject) return State.NotStarted
        return when (state.optString("status")) {
            "running" -> State.Running
            "gone" -> State.Gone
            "error" -> State.Failed(state.optString("message"))
            "done" -> state.optJSONObject("result")?.let { State.Done(it) } ?: State.Failed("no result")
            else -> State.Failed("unknown state")
        }
    }
}

/** The body of `POST /v1/analyze[/stream]`, built as the extension's background builds it. */
object AnalyzeRequest {
    /** The server reads the first 20,000 characters; more only costs bandwidth (the extension's cap). */
    const val MAX_UPLOAD_TEXT_CHARS = 24_000

    /**
     * `{url: facts.url, page_facts: facts, page_content?: text}` from the reader's `{facts, text}`, and `tier: "quick"`
     * for the free quick check ([quick]; a deep check sends no tier, the server's default). Nothing is added: no
     * device, no language, nothing about the user.
     */
    fun fromExtraction(extraction: JSONObject, quick: Boolean = false): JSONObject {
        val facts = extraction.optJSONObject("facts") ?: throw IllegalArgumentException("no page facts")
        val url = facts.optString("url")
        require(SharedLink.isWebAddress(url)) { "the page has no web address" }
        val body = JSONObject().put("url", url).put("page_facts", facts)
        val upload = capped(extraction.optString("text"))
        if (upload.isNotEmpty()) body.put("page_content", upload)
        if (quick) body.put("tier", "quick")
        return body
    }

    private fun capped(text: String): String {
        if (text.length <= MAX_UPLOAD_TEXT_CHARS) return text
        // Don't cut a character in two.
        val end = if (Character.isHighSurrogate(text[MAX_UPLOAD_TEXT_CHARS - 1])) MAX_UPLOAD_TEXT_CHARS - 1 else MAX_UPLOAD_TEXT_CHARS
        return text.substring(0, end)
    }
}
