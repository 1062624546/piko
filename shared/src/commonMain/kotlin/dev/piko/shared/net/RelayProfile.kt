package dev.piko.shared.net

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.Serializable

/** 与 Worker 的 piko-relay-v1 协议配套。访问凭证只发往用户选择的 HTTPS 中继。 */
@Serializable
data class RelayProfile(val name: String, val endpoint: String, val token: String) {
    val isValid: Boolean get() = runCatching {
        normalizeEndpoint(endpoint) == endpoint && token.matches(Regex("[A-Za-z0-9_-]{24,256}")) &&
            name.isNotBlank() && name.length <= 80
    }.getOrDefault(false)

    override fun toString(): String = "RelayProfile(name=$name, endpoint=$endpoint, token=***)"

    companion object {
        fun normalizeEndpoint(raw: String): String {
            val url = Url(raw.trim())
            require(url.protocol.name == "https" && url.host.isNotBlank() && url.port == 443 &&
                url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
                url.parameters.isEmpty() && url.fragment.isEmpty() && url.encodedPath in listOf("", "/")) {
                "中继地址必须是 HTTPS 根地址，不能包含端口、路径、参数或账号"
            }
            return "https://${url.host.lowercase()}"
        }

        fun importLink(raw: String): RelayProfile {
            require(raw.length <= 4096) { "代理链接过长" }
            val url = Url(raw.trim())
            require(url.protocol.name == "piko" && url.host == "proxy" && url.user.isNullOrEmpty() &&
                url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.encodedPath in listOf("", "/")) { "请粘贴 piko://proxy 链接" }
            fun single(key: String): String? {
                val values = url.parameters.getAll(key) ?: return null
                require(values.size == 1) { "代理链接包含重复参数" }
                return values.single()
            }
            val endpoint = normalizeEndpoint(requireNotNull(single("url")) { "代理链接缺少中继地址" })
            val profile = RelayProfile(single("name") ?: Url(endpoint).host, endpoint,
                requireNotNull(single("token")) { "代理链接缺少访问凭证" })
            require(profile.isValid) { "访问凭证须为 24 至 256 位字母、数字、下划线或短横线" }
            return profile
        }

        fun exportLink(profile: RelayProfile): String {
            require(profile.isValid)
            return URLBuilder("piko://proxy").apply {
                parameters.append("url", profile.endpoint)
                parameters.append("token", profile.token)
                parameters.append("name", profile.name)
            }.buildString()
        }
    }
}
