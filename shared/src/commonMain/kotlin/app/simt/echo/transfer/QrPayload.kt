package app.simt.echo.transfer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * QR pairing payload (plan §5.4):
 * `simt-echo://r?v=1&h=<ipv4>&p=<port>&t=<token>&k=<key>&s=<sha8>&n=<name>[&c=<relay>]`
 *
 * The URI carries the AES key and a short body fingerprint, never bundle
 * bytes. Total length stays ≤ 200 chars so any QR scanner reads it.
 */
data class QrPayload(
    val host: String,
    val port: Int,
    val token: ByteArray,
    val key: ByteArray,
    val sha8Hex: String,
    val name: String,
    val relayCode: String? = null,
) {
    fun build(): String {
        require(token.size == TOKEN_BYTES) { "token must be $TOKEN_BYTES bytes" }
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(sha8Hex.matches(Regex("[0-9a-f]{16}"))) { "s must be 8 bytes as lowercase hex" }
        val builder = StringBuilder(SCHEME)
        builder.append("?v=1")
        builder.append("&h=").append(host)
        builder.append("&p=").append(port)
        builder.append("&t=").append(base64Url(token))
        builder.append("&k=").append(base64Url(key))
        builder.append("&s=").append(sha8Hex)
        builder.append("&n=").append(percentEncode(name.take(MAX_NAME_CHARS)))
        if (relayCode != null) builder.append("&c=").append(relayCode)
        return builder.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrPayload) return false
        return host == other.host && port == other.port &&
            token.contentEquals(other.token) && key.contentEquals(other.key) &&
            sha8Hex == other.sha8Hex && name == other.name && relayCode == other.relayCode
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + token.contentHashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + sha8Hex.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (relayCode?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val TOKEN_BYTES = 16
        const val KEY_BYTES = 32
        const val MAX_NAME_CHARS = 40
        private const val SCHEME = "simt-echo://r"

        /** Parses a scanned URI; any malformation is a typed failure. */
        fun parse(uri: String): Result<QrPayload> = runCatching {
            require(uri.startsWith("$SCHEME?")) { "not an ECHO QR URI" }
            val params = uri.substringAfter('?')
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { part ->
                    val key = part.substringBefore('=')
                    key to part.substringAfter('=', "")
                }
            require(params["v"] == "1") { "unsupported QR version ${params["v"]}" }
            val host = params["h"]?.takeIf { it.isNotEmpty() }
                ?: error("missing h")
            require(isIpv4(host)) { "h is not an IPv4 address: $host" }
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: error("bad p")
            val token = params["t"]?.let { base64UrlDecode(it) }
                ?.takeIf { it.size == TOKEN_BYTES } ?: error("bad t")
            val key = params["k"]?.let { base64UrlDecode(it) }
                ?.takeIf { it.size == KEY_BYTES } ?: error("bad k")
            val sha = params["s"]?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }
                ?: error("bad s")
            val name = params["n"]?.let { percentDecode(it) }?.take(MAX_NAME_CHARS)
                ?: error("missing n")
            QrPayload(
                host = host,
                port = port,
                token = token,
                key = key,
                sha8Hex = sha,
                name = name,
                relayCode = params["c"]?.takeIf { it.isNotEmpty() },
            )
        }

        private fun isIpv4(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { part ->
                part.length in 1..3 && part.all { it.isDigit() } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun base64Url(bytes: ByteArray): String =
    Base64.UrlSafe.encode(bytes).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
internal fun base64UrlDecode(text: String): ByteArray {
    val padded = text + "=".repeat((4 - text.length % 4) % 4)
    return Base64.UrlSafe.decode(padded)
}

/** Percent-encodes everything outside the RFC 3986 unreserved set. */
internal fun percentEncode(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val ch = b.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~'
        ) {
            out.append(ch)
        } else {
            out.append('%').append(b.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return out.toString()
}

internal fun percentDecode(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        if (value[i] == '%' && i + 2 <= value.lastIndex) {
            bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        } else {
            bytes += value[i].code.toByte()
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
