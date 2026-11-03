package app.simt.echo.transfer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * QR pairing payload (plan 0005 §4.2):
 * `simt-echo://r?v=1&h=<ipv4>&p=<port>&t=<token>&k=<key>&s=<sha8>&n=<name>[&c=<relay>]`
 *
 * The URI carries the AES key and a short body fingerprint, never bundle
 * bytes. NFC carries this exact UTF-8 URI — there is no second token, no
 * second key, and no schema change.
 *
 * Parsing is strict because the URI is untrusted input from a camera or a
 * radio: bounded bytes, no duplicates, no fragments, no malformed escapes.
 * Every failure message names a field, never its value — an invitation is a
 * bearer capability and must not leak into logs through an exception.
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
        require(token.size == TOKEN_BYTES) { "t must be $TOKEN_BYTES bytes" }
        require(key.size == KEY_BYTES) { "k must be $KEY_BYTES bytes" }
        require(sha8Hex.matches(SHA8_PATTERN)) { "s must be 8 bytes as lowercase hex" }
        require(isDialableIpv4(host)) { "h must be a dialable IPv4 address" }
        require(port in 1..65535) { "p must be a port" }
        require(relayCode == null || relayCode.matches(RELAY_CODE_PATTERN)) {
            "c must be $RELAY_CODE_CHARS characters of the relay alphabet"
        }
        val builder = StringBuilder(SCHEME)
        builder.append("?v=1")
        builder.append("&h=").append(host)
        builder.append("&p=").append(port)
        builder.append("&t=").append(base64Url(token))
        builder.append("&k=").append(base64Url(key))
        builder.append("&s=").append(sha8Hex)
        builder.append("&n=").append(percentEncode(truncateToChars(name, MAX_NAME_CHARS)))
        if (relayCode != null) builder.append("&c=").append(relayCode)
        val uri = builder.toString()
        // The old "QRs are ≤ 200 chars" note is wrong for percent-encoded
        // Unicode; the byte bound is what the wire actually carries. Never
        // truncate silently — the caller offers file recovery instead.
        require(uri.encodeToByteArray().size <= PairingInvitation.MAX_UTF8_BYTES) {
            "invitation exceeds ${PairingInvitation.MAX_UTF8_BYTES} bytes"
        }
        return uri
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

    /** Redacted: a data-class dump must never print the key or the token. */
    override fun toString(): String =
        "QrPayload(host=$host, port=$port, token=<redacted>, key=<redacted>, " +
            "sha8=${sha8Hex.take(4)}…, name=<redacted>, relayCode=${relayCode != null})"

    companion object {
        const val TOKEN_BYTES = 16
        const val KEY_BYTES = 32
        const val MAX_NAME_CHARS = 40
        const val SCHEME = "simt-echo://r"

        /** Relay code shape, matching `worker/src/relay.ts` exactly. */
        const val RELAY_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val RELAY_CODE_CHARS = 8

        val SHA8_PATTERN = Regex("[0-9a-f]{16}")
        val RELAY_CODE_PATTERN = Regex("^[$RELAY_CODE_ALPHABET]{$RELAY_CODE_CHARS}$")

        /** Fields this version understands; duplicates of these always fail. */
        private val KNOWN_KEYS = setOf("v", "h", "p", "t", "k", "s", "n", "c")

        /** Parses a scanned or tapped URI; any malformation is a typed failure. */
        fun parse(uri: String): Result<QrPayload> = runCatching { parseStrict(uri) }

        internal fun parseStrict(uri: String): QrPayload {
            require(uri.encodeToByteArray().size <= PairingInvitation.MAX_UTF8_BYTES) {
                "not an ECHO invitation: over the byte bound"
            }
            require(!uri.contains('#')) { "not an ECHO invitation: fragment" }
            require(uri.startsWith("$SCHEME?")) { "not an ECHO invitation" }
            val fields = parseQuery(uri.substring(SCHEME.length + 1))
            require(fields["v"] == "1") { "unsupported invitation version" }

            val host = fields["h"] ?: error("missing h")
            require(isDialableIpv4(host)) { "bad h" }
            val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("bad p")
            val token = fields["t"]?.let { base64UrlDecode(it) }
                ?.takeIf { it.size == TOKEN_BYTES } ?: error("bad t")
            val key = fields["k"]?.let { base64UrlDecode(it) }
                ?.takeIf { it.size == KEY_BYTES } ?: error("bad k")
            val sha = fields["s"]?.takeIf { SHA8_PATTERN.matches(it) } ?: error("bad s")
            // Decoded exactly once, then bounded in characters (never bytes),
            // so non-Latin names survive a round trip whole.
            val name = fields["n"]
                ?.let { percentDecode(it) }
                ?.let { truncateToChars(it, MAX_NAME_CHARS) }
                ?: error("bad n")
            val relayCode = fields["c"]?.also {
                require(RELAY_CODE_PATTERN.matches(it)) { "bad c" }
            }
            return QrPayload(
                host = host,
                port = port,
                token = token,
                key = key,
                sha8Hex = sha,
                name = name,
                relayCode = relayCode,
            )
        }

        /**
         * Splits the query, rejecting duplicates of every known field.
         * Unknown fields are tolerated for legacy compatibility — only
         * within the total byte bound enforced by the caller.
         */
        private fun parseQuery(query: String): Map<String, String> {
            val fields = mutableMapOf<String, String>()
            val seenKnown = mutableSetOf<String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                require(part.contains('=')) { "malformed field" }
                val key = part.substringBefore('=')
                val value = part.substringAfter('=')
                require(key.isNotEmpty()) { "malformed field" }
                if (key in KNOWN_KEYS) {
                    require(seenKnown.add(key)) { "duplicate $key" }
                }
                fields[key] = value
            }
            return fields
        }
    }
}

/**
 * Whether [host] may be dialled for a LAN fetch (plan 0005 §4.2).
 *
 * Legitimate private LAN and hotspot addresses stay valid; loopback,
 * unspecified, broadcast and multicast are refused so a crafted invitation
 * cannot aim the client at the phone itself or at a fan-out address.
 */
internal fun isDialableIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part ->
        if (part.isEmpty() || part.length > 3) return false
        if (!part.all { it in '0'..'9' }) return false
        // Leading zeros are ambiguous (octal on some stacks) and never minted.
        if (part.length > 1 && part[0] == '0') return false
        part.toInt()
    }
    if (octets.any { it !in 0..255 }) return false
    val first = octets[0]
    if (first == 0) return false // 0.0.0.0/8 "this network"
    if (first == 127) return false // loopback
    if (first in 224..239) return false // multicast
    if (octets.all { it == 255 }) return false // limited broadcast
    return true
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

/**
 * Strict inverse of [percentEncode]: every `%` must introduce two hex
 * digits, only ASCII may appear raw, and the decoded bytes must be valid
 * UTF-8 — a malformed name is a failed invitation, not a replacement
 * character.
 */
internal fun percentDecode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '%') {
            require(i + 2 <= value.lastIndex) { "malformed escape" }
            val hi = hexDigit(value[i + 1])
            val lo = hexDigit(value[i + 2])
            require(hi >= 0 && lo >= 0) { "malformed escape" }
            bytes.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            require(ch.code < 0x80) { "raw non-ASCII byte" }
            bytes.add(ch.code.toByte())
            i++
        }
    }
    return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
}

/** -1 for anything outside `0-9a-fA-F`. */
private fun hexDigit(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}

/**
 * Bounds [value] to [max] characters, counting code points so a surrogate
 * pair is never split — an emoji name must stay valid Unicode.
 */
internal fun truncateToChars(value: String, max: Int): String {
    var chars = 0
    var i = 0
    while (i < value.length) {
        if (chars == max) return value.substring(0, i)
        val high = value[i].isHighSurrogate()
        val paired = high && i + 1 < value.length && value[i + 1].isLowSurrogate()
        i += if (paired) 2 else 1
        chars++
    }
    return value
}
