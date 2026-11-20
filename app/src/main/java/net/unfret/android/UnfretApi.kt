package net.unfret.android

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream

/** An error answer from the API, in its `{detail, error_code}` shape. */
class ApiError(val status: Int, val code: String, detail: String) : Exception(detail) {
    val badKey: Boolean get() = status == 401
    val needsChecks: Boolean get() = status == 402 || code == "INSUFFICIENT_CREDITS"
    val serviceOff: Boolean get() = status == 503

    /** Only free checks are left, and today's are all used (429): they are back tomorrow, quick checks still work. */
    val freePaused: Boolean get() = code == "FREE_CHECKS_PAUSED"
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
 * The Unfret API as the app uses it: remaining checks and the streamed analysis, with the user's API key as
 * `Authorization: Bearer ufk_…`. Requests carry nothing else about the user or the phone. Without a key ([key] null)
 * only the free quick check can be asked for: it goes without `Authorization`.
 */
class UnfretApi(private val base: String, private val key: String?, private val transport: Transport) {
    init {
        require(base in BASES) { "not an Unfret server: $base" }
    }

    private fun headers(json: Boolean): Map<String, String> = buildMap {
        if (key != null) put("Authorization", "Bearer $key")
        // The app is in English, like the extension; the API answers in English anyway.
        put("Accept-Language", "en")
        put("User-Agent", USER_AGENT)
        if (json) put("Content-Type", "application/json")
    }

    /** `GET /v1/credits`. */
    fun credits(): Credits =
        transport.send(HttpRequest("GET", "$base/v1/credits", headers(false), null)).use { response ->
            if (response.status !in 200..299) throw errorFrom(response)
            Credits.parse(JSONObject(response.reader.readText()))
        }

    /**
     * `POST /v1/analyze/stream`: calls [onStep] with each analysis step as it finishes, and returns the result.
     * Throws [ApiError] for an error answer or event, IOException when the connection fails.
     */
    fun analyze(body: JSONObject, onStep: (Step) -> Unit = {}): AnalysisResult {
        require(key != null || body.optString("tier") == "quick") { "a deep check needs a pass" }
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
                    )
                }
            }
            throw ApiError(502, "STREAM_ENDED", "The analysis ended without a result.")
        }
    }

    /**
     * `POST /v1/keys/move-free-checks`: this pass's free checks left go to the pass [toKeyId] (the app switching from
     * the pass it made for itself to one the user brought). Returns how many moved.
     */
    fun moveFreeChecks(toKeyId: String): Int {
        val body = JSONObject().put("to_key_id", toKeyId).toString()
        return transport.send(HttpRequest("POST", "$base/v1/keys/move-free-checks", headers(true), body)).use { response ->
            if (response.status !in 200..299) throw errorFrom(response)
            JSONObject(response.reader.readText()).optInt("moved", 0)
        }
    }

    /**
     * `POST /v1/invites/redeem`: a gift code (`ufi_…`, as typed; the server ignores case and surrounding spaces) adds
     * its checks to this pass. Returns how many it added. Throws [ApiError] for a refusal, by its error code
     * ([giftCodeRefusal]: not a code, unknown, used, from this pass's own pack, one this month already, too many tries).
     */
    fun redeemGiftCode(code: String): Int {
        val body = JSONObject().put("code", code.trim()).toString()
        return transport.send(HttpRequest("POST", "$base/v1/invites/redeem", headers(true), body)).use { response ->
            if (response.status !in 200..299) throw errorFrom(response)
            JSONObject(response.reader.readText()).optInt("checks_added", 0).coerceAtLeast(0)
        }
    }

    /**
     * `POST /v1/reports`: "Wrong result?". [request] is the analysed request exactly as sent, [verdict] and
     * [explanation] the result as returned; [reason] one of real_brand, scam, junk, other. The user agreed to share
     * this one check's page facts, page text and result for review (`consent`). Returns the outcome: queued,
     * already_reported or already_known. Throws [ApiError] for an error answer.
     */
    fun report(token: String, reason: String, request: JSONObject, explanation: String, verdict: JSONObject?): String {
        val body = JSONObject()
            .put("report_token", token)
            .put("reason", reason)
            .put("consent", true)
            .put("request", request)
            .put("explanation", explanation)
        if (verdict != null) body.put("verdict", verdict)
        return transport.send(HttpRequest("POST", "$base/v1/reports", headers(true), body.toString())).use { response ->
            if (response.status !in 200..299) throw errorFrom(response)
            JSONObject(response.reader.readText()).optString("status", "queued")
        }
    }

    companion object {
        /** `GET /v1/keys/challenge`: a proof-of-work challenge for a new pass (no pass needed). */
        fun keyChallenge(base: String, transport: Transport): KeyChallenge {
            require(base in BASES) { "not an Unfret server: $base" }
            val headers = mapOf("Accept-Language" to "en", "User-Agent" to USER_AGENT)
            return transport.send(HttpRequest("GET", "$base/v1/keys/challenge", headers, null)).use { response ->
                if (response.status !in 200..299) throw errorFrom(response)
                KeyChallenge.parse(JSONObject(response.reader.readText()))
            }
        }

        /**
         * `POST /v1/keys`: a new pass for a solved challenge, with free checks when [free] (the server gives them only
         * within its per-network daily limit: [IssuedKey.freeChecks] says how many came). Only the challenge, its
         * solution and that yes/no are sent: nothing about the phone.
         */
        fun createKey(base: String, transport: Transport, challenge: String, solution: String, free: Boolean): IssuedKey {
            require(base in BASES) { "not an Unfret server: $base" }
            val headers = mapOf("Accept-Language" to "en", "User-Agent" to USER_AGENT, "Content-Type" to "application/json")
            val body = JSONObject().put("challenge", challenge).put("solution", solution).put("free", free).toString()
            return transport.send(HttpRequest("POST", "$base/v1/keys", headers, body)).use { response ->
                if (response.status !in 200..299) throw errorFrom(response)
                IssuedKey.parse(JSONObject(response.reader.readText()))
            }
        }

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
        )
    }

        /**
         * A photo from the API's own thumbnail proxy ([apiImage] decides which URLs qualify), without the key: the
         * thumbnails are public. Returns null when it can't be loaded.
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
