package io.github.antomys.azdoprreview.azdo

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Local HTTP server that replays canned responses and records requests. No live calls. */
class FixtureServer : AutoCloseable {
    data class Request(val method: String, val path: String, val query: String?, val body: String, val headers: Map<String, String>)

    class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** Replies per "METHOD /raw/path", used in order; the last one repeats. */
    private val routes = ConcurrentHashMap<String, ArrayDeque<Reply>>()
    val requests = CopyOnWriteArrayList<Request>()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val key = "${exchange.requestMethod} ${exchange.requestURI.rawPath}"
            requests += Request(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                exchange.requestURI.rawQuery,
                exchange.requestBody.readBytes().decodeToString(),
                exchange.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() },
            )
            val queue = routes[key]
            val reply = synchronized(this) { queue?.let { if (it.size > 1) it.removeFirst() else it.firstOrNull() } }
                ?: Reply(404, """{"message":"no fixture for $key"}""".toByteArray())
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, if (reply.body.isEmpty()) -1 else reply.body.size.toLong())
            exchange.responseBody.use { it.write(reply.body) }
        }
        server.start()
    }

    fun on(method: String, path: String, vararg replies: Reply) {
        routes["$method $path"] = ArrayDeque(replies.toList())
    }

    fun on(method: String, path: String, json: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
        on(method, path, Reply(status, json.toByteArray(), headers))

    fun requests(method: String, path: String) = requests.filter { it.method == method && it.path == path }

    override fun close() = server.stop(0)

    companion object {
        fun fixture(name: String): String =
            requireNotNull(FixtureServer::class.java.getResource("/fixtures/azdo/$name")) { "missing fixture $name" }.readText()
    }
}
