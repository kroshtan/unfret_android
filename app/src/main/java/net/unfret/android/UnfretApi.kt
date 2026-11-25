package net.unfret.android

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream

/** An error answer from the API, in its `{detail, error_code, metadata}` shape. */
class ApiError(val status: Int, val code: String, detail: String, val metadata: JSONObject? = null) : Exception(detail) {
    /** How long to wait before trying again, when the server says (`metadata.retry_after_s`). */
    val retryAfterS: Int? get() = metadata?.optInt("retry_after_s", -1)?.takeIf { it >= 0 }
}

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

class HttpResponse(
    val status: Int,
    val reader: BufferedReader,
    private val onClose: () -> Unit = {},
    /** The raw body, for binary answers (a thumbnail); read either this or [reader], never both. */
    private val raw: InputStream? = null,
) : Closeable {
    /** The body as bytes, at most [MAX_BYTES]. */
    fun bytes(): ByteArray? {
        val stream = raw ?: return reader.readText().toByteArray()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    override fun close() {
        try {
            reader.close()
        } finally {
            onClose()
        }
    }

    private companion object {
        /** A thumbnail is a few kilobytes; nothing near this. */
        const val MAX_BYTES = 512 * 1024
    }
}

/** Sends one request; throws IOException when the server can't be reached. */
fun interface Transport {
    fun send(request: HttpRequest): HttpResponse
}

/**
 * The Unfret API as the app uses it: free checks, quick and deep, with no pass and nothing that names the user or the
 * phone (contract v1, "Free checks for shoppers"). A deep check solves a proof of work first ([deepCheck]).
 *
 * [donateOff]: the Google Play build sends `X-Unfret-Donate: off`, so the server's words and links never mention
 * donations ([Storefront]).
 */
class UnfretApi(private val base: String, private val transport: Transport, private val donateOff: Boolean) {
    init {
        require(base in BASES) { "not an Unfret server: $base" }
    }

    private fun headers(json: Boolean): Map<String, String> = buildMap {
        // The app is in English, like the extension; the API answers in English anyway.
        put("Accept-Language", "en")
        put("User-Agent", USER_AGENT)
        if (donateOff) put("X-Unfret-Donate", "off")
        if (json) put("Content-Type", "application/json")
    }

    private fun <T> get(path: String, parse: (JSONObject) -> T): T =
        transport.send(HttpRequest("GET", "$base$path", headers(false), null)).use { response ->
            if (response.status !in 200..299) throw errorFrom(response)
            parse(JSONObject(response.reader.readText()))
        }

    /** `GET /v1/pricing`'s `free`: whether free deep checks are paused (the month's spend ceiling). */
    fun freeChecks(): FreeChecks = get("/v1/pricing") { FreeChecks.parse(it.optJSONObject("free")) }

    /** `GET /v1/analyze/challenge`: the proof of work one free deep check needs (single use, 5 minutes). */
    fun challenge(): DeepChallenge = get("/v1/analyze/challenge", DeepChallenge::parse)

    /**
     * A free deep check of [page] (the analysed request without a tier): a challenge, solved by [solve] (blocking,
     * off the main thread), then the check with its `proof_of_work`. A refused or expired challenge (400
     * `BAD_CHALLENGE`, 428 `PROOF_OF_WORK_REQUIRED`) is fetched and solved once more. A paused challenge is never
     * solved: it throws [ApiError] `FREE_CHECKS_PAUSED`, as the check would. Returns the result and the request
     * exactly as sent.
     */
    fun deepCheck(
        page: JSONObject,
        solve: (DeepChallenge) -> String,
        onSolved: () -> Unit = {},
        onStep: (Step) -> Unit = {},
    ): Pair<AnalysisResult, JSONObject> {
        require(page.optString("tier") != "quick") { "a quick check needs no proof of work" }
        var attempt = 0
        while (true) {
            val challenge = challenge()
            if (challenge.paused) throw ApiError(429, PAUSED, "Free deep checks are paused.", challenge.pause)
            val solution = solve(challenge)
            onSolved()
            val body = JSONObject(page.toString()).put(
                "proof_of_work",
                JSONObject().put("challenge", challenge.challenge).put("solution", solution),
            )
            try {
                return analyze(body, onStep) to body
            } catch (e: ApiError) {
                if (attempt++ > 0 || e.code !in RETRY_CHALLENGE) throw e
            }
        }
    }

    /**
     * `POST /v1/analyze/stream`: calls [onStep] with each analysis step as it finishes, and returns the result.
     * Throws [ApiError] for an error answer or event, IOException when the connection fails.
     */
    fun analyze(body: JSONObject, onStep: (Step) -> Unit = {}): AnalysisResult {
        val request = HttpRequest(
            "POST",
            "$base/v1/analyze/stream",
            headers(true) + ("Accept" to "text/event-stream"),
            body.toString(),
        )
        transport.send(request).use { response ->
            if (response.status !in 200..299) throw errorFrom(response)
            val events = SseReader(response.reader)
            while (true) {
                val event = events.next() ?: break
                val data = try {
                    JSONObject(event.data)
                } catch (_: Exception) {
                    continue
                }
                when (event.event) {
                    "step" -> data.optString("skill").takeIf { it.isNotEmpty() }?.let {
                        val check = if (data.has("check") && !data.isNull("check")) data.optBoolean("check") else null
                        onStep(Step(it, check))
                    }
                    "result" -> return AnalysisResult.parse(data)
                    "error" -> throw ApiError(
                        data.optInt("status", 500),
                        data.optString("error_code", "INTERNAL_ERROR"),
                        data.optString("detail", "Analysis failed."),
                        data.optJSONObject("metadata"),
                    )
                }
            }
            throw ApiError(502, "STREAM_ENDED", "The analysis ended without a result.")
        }
    }

    companion object {
        const val PAUSED = "FREE_CHECKS_PAUSED"
        private val RETRY_CHALLENGE = setOf("BAD_CHALLENGE", "PROOF_OF_WORK_REQUIRED")

        private fun errorFrom(response: HttpResponse): ApiError {
            val body = try {
                JSONObject(response.reader.readText())
            } catch (_: Exception) {
                JSONObject()
            }
            return ApiError(
                response.status,
                body.optString("error_code").ifEmpty { "HTTP_${response.status}" },
                body.optString("detail").ifEmpty { "HTTP ${response.status}" },
                body.optJSONObject("metadata"),
            )
        }

        /**
         * A photo from the API's own thumbnail proxy ([apiImage] decides which URLs qualify). Returns null when it
         * can't be loaded.
         */
        fun thumbnail(url: String, base: String, transport: Transport): ByteArray? {
            if (base !in BASES || apiImage(url, base) == null) return null
            val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "image/jpeg")
            return try {
                transport.send(HttpRequest("GET", url, headers, null)).use { response ->
                    if (response.status != 200) null else response.bytes()
                }
            } catch (_: java.io.IOException) {
                null
            }
        }

        const val PRODUCTION = "https://unfret.net"
        const val DEV = "https://dev.unfret.net"

        /** The only servers the app talks to. */
        val BASES = setOf(PRODUCTION, DEV)

        /** The app and its version only: the platform's default would name the phone's model. */
        val USER_AGENT = "Unfret-Android/${BuildConfig.VERSION_NAME}"
    }
}
