package dev.piko.shared.net

import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import kotlin.test.*

class PikoTunnelTest {
    @Test fun nodeWebCryptoVectorMatchesJvm() {
        val output = Buffer()
        PikoTunnel.Encoder(output, PikoTunnel.key(testTunnelToken, ByteArray(32) { it.toByte() }, "request"))
            .frame(0, "{\"test\":\"互通\"}".toByteArray())
        assertEquals("000000228cc4442a1db5f25b0efc928d3869b3f18e56af169ef1feee5c589a42f5e46f781546", output.readByteString().hex())
    }
    @Test fun multiChunkUploadAndResponseAreEncryptedAndBoundToRequest() {
        val payload = ByteArray(240000) { (it % 251).toByte() }
        val original = Request.Builder().url("https://private.example.com/upload?secret=value")
            .header("Authorization", "Bearer sensitive").post(payload.toRequestBody("application/octet-stream".toMediaType())).build()
        val profile = RelayProfile("test", "https://relay.example.com", testTunnelToken)
        val exchange = PikoTunnel.Exchange(profile, original)
        val outer = exchange.request()
        assertEquals("/v2/tunnel", outer.url.encodedPath); assertNull(outer.url.query); assertNull(outer.header("Authorization"))
        val wire = Buffer(); outer.body!!.writeTo(wire)
        val encoded = wire.readByteArray().decodeToString()
        for (value in listOf("private.example.com", "secret=value", "Bearer sensitive", testTunnelToken)) assertFalse(encoded.contains(value))
        val decoded = decodeTunnel(outer)
        assertContentEquals(payload, decoded.payload)
        assertEquals("Bearer sensitive", PikoTunnel.readHeaders(decoded.metadata["headers"])["Authorization"])
        exchange.response(encryptedResponse(outer, decoded, 206, Headers.headersOf("Content-Length", payload.size.toString()), payload)).use {
            assertEquals(206, it.code); assertContentEquals(payload, it.body.bytes())
        }
        val another = PikoTunnel.Exchange(profile, original)
        assertFailsWith<IOException> { another.response(encryptedResponse(outer, decoded)) }
    }
    @Test fun truncationModificationAndDirectionSwapAreRejected() {
        val key = PikoTunnel.key(testTunnelToken, ByteArray(32), "request")
        val output = Buffer(); PikoTunnel.Encoder(output, key).frame(2, "data".toByteArray())
        val wire = output.readByteArray()
        for (bad in listOf(wire.copyOf(wire.size - 1), wire.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })) {
            assertFailsWith<IOException> { PikoTunnel.Decoder(Buffer().write(bad), key).frame() }
        }
        assertFailsWith<IOException> { PikoTunnel.Decoder(Buffer().write(wire), PikoTunnel.key(testTunnelToken, ByteArray(32), "response")).frame() }
    }
    @Test fun missingFinalRecordDoesNotBecomeSuccessfulEof() {
        val key = PikoTunnel.key(testTunnelToken, ByteArray(32), "request")
        val output = Buffer(); PikoTunnel.Encoder(output, key).frame(1, "partial".toByteArray())
        val decoder = PikoTunnel.Decoder(output, key)
        assertEquals(1, decoder.frame().first)
        assertFailsWith<IOException> { decoder.frame() }
    }
}
