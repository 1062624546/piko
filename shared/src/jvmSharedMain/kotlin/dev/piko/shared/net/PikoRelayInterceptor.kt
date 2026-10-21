package dev.piko.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.IOException
import java.net.Proxy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 在 TLS 建连前改路由；绝不关闭证书校验，不对失败请求回退直连。 */
class PikoRelayInterceptor(private val mediaClient: Boolean = false) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val setting = PikoProxySelector.currentSetting()
        val url = original.url
        if (setting.mode != ProxyMode.RELAY || isLoopbackHost(url.host)) return chain.proceed(original)
        val relay = setting.activeRelay ?: throw IOException("Web 代理未配置，请在网络代理中重新导入")
        val endpoint = relay.endpoint.toHttpUrl()
        // 只有中继本身与本机通信直接连接，其余 HTTP 请求必须经中继。
        if (url.scheme == endpoint.scheme && url.host == endpoint.host && url.port == endpoint.port)
            return chain.proceed(original)
        val routed = original.newBuilder()
            .url(relay.endpoint.toHttpUrl().newBuilder().encodedPath("/v1/relay")
                .addQueryParameter("url", url.toString()).build())
            .tag(RelayDestination::class.java, RelayDestination(relay.endpoint.toHttpUrl().host))
            .removeHeader("Host")
            .header("X-Piko-Relay-Token", relay.token)
            .build()
        val response = try { chain.proceed(routed) }
        catch (e: IOException) { throw IOException("Web 代理连接失败，请检查代理设置；未切换为直连", e) }
        if (response.header("X-Piko-Relay-Error") != null) {
            response.close()
            throw IOException("Web 代理无法完成请求，请检查凭证、目标地址或代理连接；未切换为直连")
        }
        return response
    }

    companion object {
        fun isLoopbackHost(host: String): Boolean = host.equals("localhost", true) ||
            host == "::1" || host == "[::1]" || Regex("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matches(host)

        private val roots = listOf("mypikpak.com", "mypikpak.net", "pikpak.me", "pikpakdrive.com")
        fun isPikPakHost(host: String): Boolean = roots.any { host == it || host.endsWith(".$it") }
        fun isApiHost(host: String): Boolean = roots.any { root ->
            listOf("user", "api-drive", "api-dav").any { host == "$it.$root" }
        }
    }
}

private data class RelayDestination(val host: String)

/** OkHttp 每次重定向后的网络请求也必须检查，禁止把中继凭证带到另一个源。 */
class PikoRelayCredentialGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("X-Piko-Relay-Token") != null) {
            val destination = request.tag(RelayDestination::class.java)
            if (destination == null || !request.url.isHttps || request.url.port != 443 || request.url.host != destination.host)
                throw IOException("中继跳转到其他地址，已拒绝发送访问凭证")
        }
        return chain.proceed(request)
    }
}

/** 应用进程共用。SDK 仍负责鉴权、重试和 JSON 解析，中继只改 HTTP 传输。 */
fun pikoRelayHttpClient(media: Boolean = false): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            addInterceptor(PikoRelayInterceptor(media))
            addNetworkInterceptor(PikoRelayCredentialGuard())
            eventListener(PikoNetworkCalls.listener)
            if (media) {
                protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
                dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 16; maxRequests = 64 })
            }
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = if (media) Long.MAX_VALUE else 60_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}

/** 检查凭证、Worker 协议及上游可达性；不会发送 PikPak 登录信息。 */
fun testPikoRelay(profile: RelayProfile): String {
    require(profile.isValid) { "请先填写完整的中继地址与访问凭证" }
    val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    try {
        val request = okhttp3.Request.Builder().url("${profile.endpoint}/v1/check")
            .header("X-Piko-Relay-Token", profile.token).build()
        client.newCall(request).execute().use { response ->
            return when {
                response.code == 401 -> "访问凭证不正确或已失效"
                response.code == 200 && response.header("X-Piko-Relay-Protocol") == "piko-relay-v1" -> "连接成功，凭证有效，上游可达"
                response.code == 502 -> "中继可达，但连接 PikPak 失败"
                else -> "连接失败（HTTP ${response.code}），请检查中继部署"
            }
        }
    } finally {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

suspend fun checkPikoRelay(profile: RelayProfile): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { testPikoRelay(profile) }

/** 所有网络客户端共用；切换代理时避免旧的直连下载继续传输。 */
object PikoNetworkCalls {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    val listener = object : okhttp3.EventListener() {
        override fun callStart(call: okhttp3.Call) { active.add(call) }
        override fun callEnd(call: okhttp3.Call) { active.remove(call) }
        override fun callFailed(call: okhttp3.Call, ioe: IOException) { active.remove(call) }
    }
    fun cancelActive() { active.toList().forEach { it.cancel() } }
}
