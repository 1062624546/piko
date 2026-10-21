package dev.piko.shared.net

import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.Headers
import java.net.Proxy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeMediaGatewayTest {
    private val profile = RelayProfile("test", "https://relay.example.com", "a".repeat(48))
    @AfterTest fun reset() { PikoProxySelector.apply(ProxySetting()) }
    private fun enable() { PikoProxySelector.apply(ProxySetting(mode = ProxyMode.RELAY, relays = listOf(profile), selectedRelay = profile.endpoint)) }
    private fun localClient() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @Test fun nativeRangeReadsUseTheRelayAndPreserve206() {
        enable()
        var calls = 0
        val upstream = OkHttpClient.Builder().addInterceptor(PikoRelayInterceptor()).addInterceptor { chain ->
            calls++
            assertEquals("relay.example.com", chain.request().url.host)
            val decoded = decodeTunnel(chain.request())
            assertEquals("https://video.example.com/movie.mp4?sign=ok", decoded.metadata["url"]!!.jsonPrimitive.content)
            assertEquals("bytes=3-5", PikoTunnel.readHeaders(decoded.metadata["headers"])["Range"])
            encryptedResponse(chain.request(), decoded, 206, Headers.headersOf("Content-Range", "bytes 3-5/9"), "345".toByteArray())
        }.build()
        val viewer = localClient()
        NativeMediaGateway(upstream).use { gateway ->
            val url = gateway.route("https://video.example.com/movie.mp4?sign=ok")
            assertEquals("127.0.0.1", url.toHttpUrl().host)
            assertFalse(url.contains(profile.token))
            viewer.newCall(Request.Builder().url(url).header("Range", "bytes=3-5").build()).execute().use {
                assertEquals(206, it.code); assertEquals("bytes 3-5/9", it.header("Content-Range")); assertEquals("345", it.body!!.string())
            }
            assertEquals(1, calls)
        }
        viewer.dispatcher.executorService.shutdown(); upstream.dispatcher.executorService.shutdown()
    }

    @Test fun nativeFailureReturns502WithoutDirectFallback() {
        enable()
        var calls = 0
        val upstream = OkHttpClient.Builder().addInterceptor(PikoRelayInterceptor()).addInterceptor { chain ->
            calls++; assertEquals("relay.example.com", chain.request().url.host); throw IOException("offline")
        }.build()
        val viewer = localClient()
        NativeMediaGateway(upstream).use { gateway ->
            viewer.newCall(Request.Builder().url(gateway.route("https://video.example.com/movie.mp4")).build()).execute().use {
                assertEquals(502, it.code)
            }
            assertEquals(1, calls)
        }
        viewer.dispatcher.executorService.shutdown(); upstream.dispatcher.executorService.shutdown()
    }

    @Test fun hlsSegmentsKeysAndVariantPlaylistsAreAllLocalUrls() {
        val upstream = localClient()
        NativeMediaGateway(upstream).use { gateway ->
            val result = gateway.rewriteHls("""#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/key"
#EXT-X-MAP:URI="init.mp4"
child/index.m3u8?sign=a
segment.ts
""", "https://video.example.com/path/master.m3u8".toHttpUrl())
            assertFalse(result.contains("https://keys.example.com"))
            assertTrue(result.contains("/https/keys.example.com/key"))
            assertTrue(result.contains("/https/video.example.com/path/init.mp4"))
            assertTrue(result.contains("/https/video.example.com/path/child/index.m3u8?sign=a"))
            assertTrue(result.contains("/https/video.example.com/path/segment.ts"))
            assertEquals("http://127.0.0.1:4567/sdk", gateway.route("http://127.0.0.1:4567/sdk"))
        }
        upstream.dispatcher.executorService.shutdown()
    }

    @Test fun dashTemplatesKeepTheirNumberPlaceholderAndUseLocalGateway() {
        val upstream = localClient()
        NativeMediaGateway(upstream).use { gateway ->
            val xml = """<MPD><Period><BaseURL>https://video.example.com/stream/</BaseURL><AdaptationSet><SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4"/></AdaptationSet></Period></MPD>"""
            val result = String(gateway.rewriteDash(xml.toByteArray(), "https://video.example.com/master.mpd".toHttpUrl()))
            assertTrue(result.contains("/https/video.example.com/stream/seg-${'$'}Number${'$'}.m4s"))
            assertFalse(result.contains(">https://video.example.com"))
        }
        upstream.dispatcher.executorService.shutdown()
    }
}
