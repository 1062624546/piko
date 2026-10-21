package dev.piko.shared.net

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal val testTunnelToken = "a".repeat(48)
internal data class DecodedTunnel(val salt: ByteArray, val metadata: JsonObject, val payload: ByteArray)
internal fun decodeTunnel(request: Request): DecodedTunnel {
    val raw = Buffer(); request.body!!.writeTo(raw)
    check(raw.readByteArray(6).contentEquals(PikoTunnel.magic))
    check(raw.readByteArray(16).contentEquals(PikoTunnel.keyId(testTunnelToken)))
    val salt = raw.readByteArray(32)
    val decoder = PikoTunnel.Decoder(raw, PikoTunnel.key(testTunnelToken, salt, "request"))
    val (type, metadata) = decoder.frame(); check(type == 0)
    val payload = Buffer(); val source = decoder.body()
    while (source.read(payload, 8192) >= 0) Unit
    return DecodedTunnel(salt, Json.parseToJsonElement(metadata.decodeToString()).jsonObject, payload.readByteArray())
}
internal fun encryptedResponse(request: Request, decoded: DecodedTunnel, status: Int = 200, headers: Headers = Headers.headersOf(), payload: ByteArray = "ok".toByteArray()): Response {
    val sink = Buffer(); val responseSalt = ByteArray(32) { 42 }; sink.write(responseSalt)
    val encoder = PikoTunnel.Encoder(sink, PikoTunnel.key(testTunnelToken, decoded.salt + responseSalt, "response"))
    encoder.frame(0, buildJsonObject { put("status", status); put("headers", PikoTunnel.headers(headers)) }.toString().toByteArray())
    var at = 0
    while (payload.size - at > PikoTunnel.CHUNK) { encoder.frame(1, payload.copyOfRange(at, at + PikoTunnel.CHUNK)); at += PikoTunnel.CHUNK }
    encoder.frame(2, payload.copyOfRange(at, payload.size))
    return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("X-Piko-Tunnel", "2").body(sink.readByteArray().toResponseBody()).build()
}
