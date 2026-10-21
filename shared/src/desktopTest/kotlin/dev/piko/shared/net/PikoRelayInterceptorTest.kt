package dev.piko.shared.net

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PikoRelayInterceptorTest {
    private val relay = RelayProfile("测试", "https://relay.example.com", "a".repeat(48))
    private val setting = ProxySetting(mode = ProxyMode.RELAY, relays = listOf(relay), selectedRelay = relay.endpoint)

    @AfterTest fun reset() { PikoProxySelector.apply(ProxySetting()) }

    private fun request(url: String, media: Boolean = false, check: (Request) -> Unit) {
        val client = OkHttpClient.Builder().addInterceptor(PikoRelayInterceptor(media))
            .addInterceptor(PikoRelayCredentialGuard())
            .addInterceptor { chain ->
                check(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("ok".toResponseBody()).build()
            }.build()
        try { client.newCall(Request.Builder().url(url).header("Authorization", "Bearer original").build()).execute().close() }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun routesApiWithOriginalTargetAndSeparateCredentials() {
        PikoProxySelector.apply(setting)
        val original = "https://api-drive.mypikpak.net/drive/v1/files?page_size=10"
        request(original) {
            assertEquals("relay.example.com", it.url.host)
            assertEquals(original, it.url.queryParameter("url"))
            assertEquals(relay.token, it.header("X-Piko-Relay-Token"))
            assertEquals("Bearer original", it.header("Authorization"))
        }
    }

    @Test fun allExternalRequestsUseRelayButLocalAndRelayItselfStayDirect() {
        PikoProxySelector.apply(setting)
        for (url in listOf("https://dl.mypikpak.com/file", "https://api.github.com/repos/x/releases", "https://metatube.example.com/v1/search", "https://storage.example.com/upload")) {
            request(url) { assertEquals("relay.example.com", it.url.host); assertEquals(url, it.url.queryParameter("url")) }
        }
        for (url in listOf("http://127.0.0.1:3456/file", "http://localhost/file", "http://[::1]/file", "https://relay.example.com/v1/check")) {
            request(url) { assertEquals(null, it.header("X-Piko-Relay-Token")) }
        }
        PikoProxySelector.apply(setting.copy(mode = ProxyMode.NONE))
        request("https://user.mypikpak.com/v1/auth/signin") { assertEquals("user.mypikpak.com", it.url.host) }
    }

    @Test fun relayFailureNeverRetriesOriginalDestination() {
        PikoProxySelector.apply(setting)
        var count = 0
        val client = OkHttpClient.Builder().addInterceptor(PikoRelayInterceptor()).addInterceptor { chain ->
            count++
            assertEquals("relay.example.com", chain.request().url.host)
            throw IOException("offline")
        }.build()
        try {
            assertFailsWith<IOException> { client.newCall(Request.Builder().url("https://api.github.com/").build()).execute() }
            assertEquals(1, count)
        } finally { client.dispatcher.executorService.shutdown() }
    }

    @Test fun missingCredentialsFailClosed() {
        PikoProxySelector.apply(setting.copy(selectedRelay = "missing"))
        assertFailsWith<IOException> { request("https://user.mypikpak.com/v1/auth/signin") { error("must not send") } }
    }

    @Test fun credentialGuardRejectsCrossOriginRedirect() {
        PikoProxySelector.apply(setting)
        val client = OkHttpClient.Builder().addInterceptor(PikoRelayInterceptor())
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url("https://evil.test/").build()) }
            .addInterceptor(PikoRelayCredentialGuard()).build()
        try {
            assertFailsWith<IOException> { client.newCall(Request.Builder().url("https://user.mypikpak.com/").build()).execute() }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
