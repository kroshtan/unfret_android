package net.unfret.android

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** [Transport] over the platform's HttpURLConnection: HTTPS only, no redirects (the key goes nowhere else), no cache. */
class HttpUrlTransport : Transport {
    override fun send(request: HttpRequest): HttpResponse {
        val url = URL(request.url)
        if (url.protocol != "https") throw IOException("refusing a non-HTTPS request")
        val connection = url.openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 15_000
        // An analysis streams a step every few seconds; a minute of silence means the connection is gone.
        connection.readTimeout = 90_000
        connection.requestMethod = request.method
        for ((name, value) in request.headers) connection.setRequestProperty(name, value)
        try {
            if (request.body != null) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = (if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream)
                ?: "".byteInputStream()
            return HttpResponse(
                status,
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)),
                { connection.disconnect() },
                stream,
            )
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }
}
