package dev.piko.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayProfileTest {
    private val profile = RelayProfile("我的中继", "https://relay.example.com", "a".repeat(48))

    @Test fun linkRoundTripAndOldSettingsMigration() {
        assertEquals(profile, RelayProfile.importLink(RelayProfile.exportLink(profile)))
        val old = ProxySetting.decode("""{"mode":"MANUAL","protocol":"SOCKS","host":"localhost","port":1080}""")
        assertEquals(ProxyMode.MANUAL, old.mode)
        assertTrue(old.relays.isEmpty())
        val setting = old.copy(mode = ProxyMode.RELAY, relays = listOf(profile), selectedRelay = profile.endpoint)
        assertEquals(profile, ProxySetting.decode(setting.encode()).activeRelay)
        assertEquals(profile, setting.copy(mode = ProxyMode.NONE).relays.single())
        assertFalse(setting.toString().contains(profile.token))
    }

    @Test fun rejectsAmbiguousOrUnsafeImport() {
        for (endpoint in listOf("http://relay.example.com", "https://x:y@relay.example.com", "https://relay.example.com/path",
            "https://relay.example.com?token=x", "https://relay.example.com:444", "https://relay.example.com#x")) {
            assertFailsWith<IllegalArgumentException> { RelayProfile.normalizeEndpoint(endpoint) }
        }
        assertFailsWith<IllegalArgumentException> { RelayProfile.importLink("tg://proxy?server=x") }
        assertFailsWith<IllegalArgumentException> {
            RelayProfile.importLink(RelayProfile.exportLink(profile) + "&token=another")
        }
        assertFalse(profile.copy(token = "short").isValid)
        assertFalse(profile.copy(token = "a".repeat(30) + "\r\n").isValid)
    }
}
