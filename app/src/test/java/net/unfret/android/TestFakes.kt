package net.unfret.android

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

/** Stands in for the Keystore: reversible, and visibly not the plain text. */
class XorBox : SecretBox {
    override fun seal(plain: ByteArray) = byteArrayOf(42) + plain.map { (it.toInt() xor 0x5a).toByte() }

    override fun open(sealed: ByteArray): ByteArray {
        check(sealed.first() == 42.toByte())
        return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}

class MapStore : StringStore {
    val values = mutableMapOf<String, String>()

    override fun get(name: String) = values[name]

    override fun put(name: String, value: String) {
        values[name] = value
    }

    override fun remove(name: String) {
        values.remove(name)
    }
}

/** Answers by method and path ("POST /v1/keys"); an unrouted request is "unreachable" (IOException). */
class RoutedTransport(private val routes: Map<String, Pair<Int, String>>) : Transport {
    val sent = mutableListOf<HttpRequest>()

    override fun send(request: HttpRequest): HttpResponse {
        sent += request
        val path = request.url.substringAfter("://").substringAfter('/')
        val (status, body) = routes["${request.method} /$path"] ?: throw IOException("unreachable")
        return HttpResponse(status, BufferedReader(StringReader(body)))
    }

    fun paths() = sent.map { "${it.method} /${it.url.substringAfter("://").substringAfter('/')}" }
}

class MemoryFlag(private var value: Boolean = false) : FreeChecksFlag {
    override fun received() = value

    override fun markReceived() {
        value = true
    }
}

const val KEY_A = "ufk_abcdefgh23_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const val KEY_B = "ufk_zyxwvuts23_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
