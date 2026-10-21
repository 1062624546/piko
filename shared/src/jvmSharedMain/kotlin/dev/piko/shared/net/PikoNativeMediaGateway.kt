package dev.piko.shared.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element

/**
 * 原生播放器/MediaExtractor 不使用 Ktor。只给它们本机 URL，远端读取统一交给带代理策略的 OkHttp。
 * 仅监听回环；随机能力令牌只用于本机，不包含中继访问凭证。清单内的分片、密钥和字幕地址也一并改写。
 */
class NativeMediaGateway(private val client: OkHttpClient) : AutoCloseable {
    private val MAX_MANIFEST = 2 * 1024 * 1024
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val workers = Executors.newFixedThreadPool(16) { r -> Thread(r, "Piko-media-http").apply { isDaemon = true } }
    private val serverDelegate = lazy {
        val task = java.util.concurrent.FutureTask {
            ServerSocket(0, 32, InetAddress.getByName("127.0.0.1")).also { socket ->
                Thread({
                    while (!socket.isClosed) {
                        val connection = try { socket.accept() } catch (_: IOException) { break }
                        workers.execute { serve(connection) }
                    }
                }, "Piko-media-accept").apply { isDaemon = true; start() }
            }
        }
        Thread(task, "Piko-media-start").apply { isDaemon = true; start() }
        task.get()
    }

    private val server: ServerSocket by serverDelegate

    override fun close() {
        if (serverDelegate.isInitialized()) server.close()
        workers.shutdownNow()
    }

    /** 不依赖当前模式，保证播放期间切换代理后，新分片与 Range 读取也会遵守新策略。 */
    fun route(raw: String): String {
        val url = raw.toHttpUrlOrNull()
        if (url == null) {
            if (raw.contains("://") && !raw.startsWith("file://") && !raw.startsWith("content://"))
                throw IOException("播放器不支持通过 Web 代理读取此协议")
            return raw
        }
        if (PikoRelayInterceptor.isLoopbackHost(url.host)) return raw
        return localUrl(url)
    }

    private fun localUrl(url: HttpUrl): String = "http://127.0.0.1:${server.localPort}/$token/${url.scheme}/${url.toString().substringAfter("://")}"

    private fun reference(base: HttpUrl, raw: String): String {
        if (raw.startsWith("data:")) return raw
        val resolved = base.resolve(raw) ?: throw IOException("媒体清单包含无效地址")
        if (PikoRelayInterceptor.isLoopbackHost(resolved.host)) throw IOException("媒体清单不能指向本机服务")
        return localUrl(resolved)
    }

    internal fun rewriteHls(text: String, base: HttpUrl): String = text.lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.trimStart().startsWith("#") -> URI_ATTRIBUTE.replace(line) { match ->
                "URI=\"${reference(base, match.groupValues[1])}\""
            }
            else -> reference(base, line.trim())
        }
    }
    private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")

    internal fun rewriteDash(bytes: ByteArray, base: HttpUrl): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        fun walk(element: Element, parentBase: HttpUrl) {
            val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            val bases = children.filter { (it.localName ?: it.tagName) == "BaseURL" }
            val effective = bases.firstOrNull()?.textContent?.trim()?.let { parentBase.resolve(it) } ?: parentBase
            bases.forEach { it.textContent = reference(parentBase, it.textContent.trim()) }
            for (attribute in listOf("media", "initialization", "sourceURL", "index", "href")) {
                if (element.hasAttribute(attribute)) element.setAttribute(attribute, reference(effective, element.getAttribute(attribute)))
            }
            children.filterNot { it in bases }.forEach { walk(it, effective) }
        }
        walk(document.documentElement, base)
        return ByteArrayOutputStream().also {
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it))
        }.toByteArray()
    }

    private fun serve(socket: Socket) = socket.use {
        var headersSent = false
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (bytes.size() < 16_384) {
                    val next = input.read()
                    if (next < 0) throw IOException("本机请求中断")
                    if (next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                    bytes.write(next)
                }
                throw IOException("本机请求头过长")
            }
            val first = line().split(' ')
            require(first.size == 3 && first[0] in listOf("GET", "HEAD"))
            val path = first[1]
            val prefix = "/$token/"
            require(path.startsWith(prefix))
            val target = path.removePrefix(prefix)
            val scheme = target.substringBefore('/')
            require(scheme == "https" || scheme == "http")
            val remote = (scheme + "://" + target.substringAfter('/')).toHttpUrlOrNull() ?: throw IOException("媒体地址无效")
            require(!PikoRelayInterceptor.isLoopbackHost(remote.host))
            val builder = Request.Builder().url(remote).method(first[0], null)
            var total = 0
            while (true) {
                val value = line()
                total += value.length
                require(total <= 32_768)
                if (value.isEmpty()) break
                val name = value.substringBefore(':').trim()
                if (name.lowercase() in listOf("range", "if-range", "user-agent", "accept")) builder.header(name, value.substringAfter(':').trim())
            }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body
                val actual = response.header("X-Piko-Upstream-Url")?.toHttpUrlOrNull() ?: remote
                val type = response.header("Content-Type").orEmpty().lowercase()
                val hls = type.contains("mpegurl") || actual.encodedPath.endsWith(".m3u8", true)
                val dash = type.contains("dash+xml") || actual.encodedPath.endsWith(".mpd", true)
                val manifest = if (response.isSuccessful && first[0] == "GET" && body != null && (hls || dash)) {
                    val data = body.byteStream().readNBytesCompat(MAX_MANIFEST + 1)
                    require(data.size <= MAX_MANIFEST) { "媒体清单过大" }
                    if (hls) rewriteHls(String(data, StandardCharsets.UTF_8), actual).toByteArray(StandardCharsets.UTF_8)
                    else rewriteDash(data, actual)
                } else null
                val output = socket.getOutputStream().buffered()
                val headers = buildString {
                    append("HTTP/1.1 ${response.code} OK\r\nConnection: close\r\n")
                    for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
                        response.header(name)?.let { append("$name: $it\r\n") }
                    }
                    val size = manifest?.size?.toLong() ?: response.header("Content-Length")?.toLongOrNull() ?: body?.contentLength()?.takeIf { it >= 0 }
                    if (size != null) append("Content-Length: $size\r\n")
                    append("\r\n")
                }
                output.write(headers.toByteArray(StandardCharsets.US_ASCII))
                headersSent = true
                if (first[0] != "HEAD") {
                    if (manifest != null) output.write(manifest) else body?.byteStream()?.copyTo(output, 64 * 1024)
                }
                output.flush()
            }
        } catch (_: Exception) {
            if (!headersSent) runCatching {
                val body = "媒体连接失败；代理启用时不会切换直连".toByteArray(StandardCharsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

object PikoNativeMediaGateway {
    private val gateway by lazy {
        NativeMediaGateway(
            OkHttpClient.Builder().addInterceptor(PikoRelayInterceptor())
                .addNetworkInterceptor(PikoRelayCredentialGuard()).eventListener(PikoNetworkCalls.listener)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        )
    }
    fun route(raw: String): String = gateway.route(raw)
}
