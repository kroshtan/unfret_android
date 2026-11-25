package net.unfret.android

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/**
 * Answers by method and path ("POST /v1/analyze/stream"), in turn when a route has several answers; an unrouted
 * request is "unreachable" (IOException).
 */
class RoutedTransport(routes: Map<String, List<Pair<Int, String>>>) : Transport {
    private val queues = routes.mapValues { ArrayDeque(it.value) }
    val sent = mutableListOf<HttpRequest>()

    override fun send(request: HttpRequest): HttpResponse {
        sent += request
        val queue = queues["${request.method} /${path(request)}"] ?: throw IOException("unreachable")
        val (status, body) = if (queue.size > 1) queue.removeFirst() else queue.first()
        return HttpResponse(status, BufferedReader(StringReader(body)))
    }

    private fun path(request: HttpRequest) = request.url.substringAfter("://").substringAfter('/')

    fun paths() = sent.map { "${it.method} /${path(it)}" }
}
