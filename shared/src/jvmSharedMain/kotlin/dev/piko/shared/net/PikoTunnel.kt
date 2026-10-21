package dev.piko.shared.net

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.*

/** v2: HKDF-SHA256 + AES-256-GCM records; fresh 256-bit salt and separate directional keys. */
internal object PikoTunnel {
    const val CHUNK = 65536
    val magic = "PIKO2\n".toByteArray()
    val json = Json { ignoreUnknownKeys = true }
    fun keyId(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).copyOf(16)
    fun key(token: String, salt: ByteArray, direction: String): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        return hmac(hmac(salt, token.toByteArray()), "piko-tunnel-v2/$direction\u0001".toByteArray())
    }
    private fun cipher(mode: Int, key: ByteArray, sequence: Long, size: Int): Cipher {
        if (sequence < 0 || sequence > 0xffffffffL) throw IOException("加密流过长")
        val nonce = Buffer().writeInt(0).writeLong(sequence).readByteArray()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(Buffer().write(magic).writeLong(sequence).writeInt(size).readByteArray())
        }
    }
    class Encoder(private val output: BufferedSink, private val key: ByteArray) {
        private var sequence = 0L
        fun frame(type: Int, data: ByteArray) {
            require(data.size <= CHUNK)
            val plain = byteArrayOf(type.toByte()) + data
            val size = plain.size + 16
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key, sequence++, size).doFinal(plain)
            output.writeInt(size).write(encrypted)
        }
    }
    class Decoder(private val source: BufferedSource, private val key: ByteArray) {
        private var sequence = 0L
        var ended = false; private set
        fun frame(): Pair<Int, ByteArray> {
            if (ended) throw IOException("加密流已结束")
            try {
                val size = source.readInt()
                if (size !in 17..CHUNK + 17) throw IOException("加密记录长度无效")
                val plain = cipher(Cipher.DECRYPT_MODE, key, sequence++, size).doFinal(source.readByteArray(size.toLong()))
                val type = plain[0].toInt()
                if (type !in 0..2) throw IOException("加密记录类型无效")
                if (type == 2) ended = true
                return type to plain.copyOfRange(1, plain.size)
            } catch (e: Exception) { throw IOException("加密代理数据校验失败，连接可能中断或数据被修改", e) }
        }
        fun body(): Source = object : Source {
            private val pending = Buffer()
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0
                while (pending.size == 0L && !ended) {
                    val (type, data) = frame()
                    if (type == 0) throw IOException("加密流中出现重复元数据")
                    pending.write(data)
                }
                return if (pending.size == 0L) -1 else pending.read(sink, minOf(byteCount, pending.size))
            }
            override fun timeout() = source.timeout()
            override fun close() = source.close()
        }
    }
    fun headers(headers: Headers) = buildJsonArray {
        for (i in 0 until headers.size) add(buildJsonArray { add(headers.name(i)); add(headers.value(i)) })
    }
    fun readHeaders(value: JsonElement?): Headers = Headers.Builder().apply {
        value?.jsonArray?.forEach { item -> add(item.jsonArray[0].jsonPrimitive.content, item.jsonArray[1].jsonPrimitive.content) }
    }.build()
    class Exchange(val profile: RelayProfile, val original: Request) {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private val metadata = buildJsonObject {
            put("url", original.url.toString()); put("method", original.method)
            put("timestamp", System.currentTimeMillis()); put("headers", headers(original.headers))
            put("bodyLength", original.body?.contentLength() ?: 0)
            original.body?.contentType()?.let { put("contentType", it.toString()) }
        }.toString().toByteArray()
        fun request(): Request = Request.Builder().url("${profile.endpoint}/v2/tunnel")
            .tag(TunnelDestination::class.java, TunnelDestination(profile.endpoint))
            .post(object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun isOneShot() = true // A transport retry must never replay the same salt.
                override fun writeTo(sink: BufferedSink) {
                    sink.write(magic).write(keyId(profile.token)).write(salt)
                    val encoder = Encoder(sink, key(profile.token, salt, "request"))
                    encoder.frame(0, metadata)
                    val pending = Buffer()
                    val stream = object : Sink {
                        override fun write(source: Buffer, byteCount: Long) {
                            var remaining = byteCount
                            while (remaining > 0) {
                                val count = minOf(remaining, CHUNK.toLong() - pending.size)
                                if (count > 0) { pending.write(source, count); remaining -= count }
                                if (pending.size == CHUNK.toLong() && remaining > 0) encoder.frame(1, pending.readByteArray())
                            }
                        }
                        override fun flush() = sink.flush()
                        override fun timeout() = sink.timeout()
                        override fun close() = Unit
                    }.buffer()
                    original.body?.writeTo(stream)
                    stream.flush()
                    encoder.frame(2, pending.readByteArray())
                }
            }).build()
        fun response(response: Response): Response {
            try {
                if (response.code != 200 || response.header("X-Piko-Tunnel") != "2") throw IOException("加密代理不可用或版本不兼容")
                val encryptedSource = response.body.source()
                val responseSalt = encryptedSource.readByteArray(32)
                val decoder = Decoder(encryptedSource, key(profile.token, salt + responseSalt, "response"))
                val (type, bytes) = decoder.frame()
                if (type != 0) throw IOException("加密响应缺少元数据")
                val meta = json.parseToJsonElement(bytes.decodeToString()).jsonObject
                if (meta["error"] != null) throw IOException("加密代理无法完成请求：${meta["error"]!!.jsonPrimitive.content}")
                val status = meta["status"]!!.jsonPrimitive.int
                val originalHeaders = readHeaders(meta["headers"])
                val gzip = originalHeaders["Content-Encoding"].equals("gzip", true) && original.method != "HEAD"
                val headers = if (gzip) originalHeaders.newBuilder().removeAll("Content-Encoding").removeAll("Content-Length").build() else originalHeaders
                val source = (if (gzip) GzipSource(decoder.body().buffer()) else decoder.body()).buffer()
                val body = object : ResponseBody() {
                    override fun contentType() = headers["Content-Type"]?.let { runCatching { it.toMediaType() }.getOrNull() }
                    override fun contentLength(): Long = if (original.method == "HEAD" || status in listOf(204, 304)) 0 else headers["Content-Length"]?.toLongOrNull() ?: -1
                    override fun source() = source
                }
                return response.newBuilder().request(original).code(status).message("Relayed")
                    .headers(headers).body(body).build()
            } catch (e: Exception) { response.close(); throw IOException("加密代理连接失败；未切换为直连", e) }
        }
    }
}
internal data class TunnelDestination(val endpoint: String)
