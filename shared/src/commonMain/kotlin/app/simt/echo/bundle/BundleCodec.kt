package app.simt.echo.bundle

import app.simt.echo.model.Asset
import app.simt.echo.model.BundleBody
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.encodeToString

/** Failure kinds for [BundleCodec.open] (plan W2-02, closed set). */
enum class BundleError {
    BAD_MAGIC,
    NEEDS_KEY,
    DECRYPT_FAILED,
    HASH_MISMATCH,
    UNSUPPORTED_VERSION,
    EXPIRED,
}

class BundleCodecException(val error: BundleError, message: String) :
    IllegalArgumentException("$error: $message")

/**
 * Bundle wire codec (plan §5.4).
 *
 * Plain container `C = "ECHO" ‖ 0x01 ‖ SHA-256(body) ‖ body`; encrypted
 * file `E = "ECX1" ‖ AES-256-GCM(key, C)`. The hash is verified over the
 * received bytes — the body is never re-encoded before hashing, so the
 * check is stable across platforms. Assets are sorted by id on encode.
 *
 * Expiry is only enforced when the caller passes `nowMs` to [open]:
 * the codec stays clock-free so tests and receivers decide what "now" is.
 *
 * W10-05: [encode] runs the perception producer gate
 * (`RoutePerception.validate`), so oversize or malformed perception never
 * ships. Decode stays lenient by design — see [RoutePerception].
 */
object BundleCodec {

    private const val CONTAINER_VERSION: Byte = 0x01
    private val MAGIC = "ECHO".encodeToByteArray()
    private val ENCRYPTED_MAGIC = "ECX1".encodeToByteArray()
    private const val HASH_BYTES = 32
    private const val HEADER_BYTES = 4 + 1 + HASH_BYTES

    suspend fun encode(route: EchoRoute, assets: List<Asset>): ByteArray {
        // W10-05 producer gate: oversize/malformed perception never ships.
        route.perception?.validate(route.legs.size)
        val body = EchoJson.encodeToString(
            BundleBody(route = route, assets = assets.sortedBy { it.id }),
        ).encodeToByteArray()
        val hash = sha256(body)
        return MAGIC + byteArrayOf(CONTAINER_VERSION) + hash + body
    }

    /** Seals [container] with a fresh AES-256 key; returns (E, raw key). */
    suspend fun seal(container: ByteArray): Pair<ByteArray, ByteArray> {
        val aes = CryptographyProvider.Default.get(AES.GCM)
        val key = aes.keyGenerator(AES.Key.Size.B256).generateKey()
        val sealed = key.cipher().encrypt(container)
        return (ENCRYPTED_MAGIC + sealed) to key.encodeToByteArray(AES.Key.Format.RAW)
    }

    suspend fun open(
        bytes: ByteArray,
        key: ByteArray? = null,
        nowMs: Long? = null,
    ): Result<BundleBody> = runCatching {
        val container = when {
            bytes.startsWith(ENCRYPTED_MAGIC) -> {
                val rawKey = key
                    ?: throw BundleCodecException(BundleError.NEEDS_KEY, "encrypted bundle without key")
                decrypt(bytes.copyOfRange(ENCRYPTED_MAGIC.size, bytes.size), rawKey)
            }

            bytes.startsWith(MAGIC) -> bytes
            else -> throw BundleCodecException(BundleError.BAD_MAGIC, "not an ECHO/ECX1 bundle")
        }
        val body = parseContainer(container)
        if (nowMs != null) {
            val expiry = body.route.privacy.expiresAtEpochMs
            if (expiry != null && expiry <= nowMs) {
                throw BundleCodecException(BundleError.EXPIRED, "route expired at $expiry")
            }
        }
        body
    }

    private suspend fun decrypt(ciphertext: ByteArray, rawKey: ByteArray): ByteArray {
        val aes = CryptographyProvider.Default.get(AES.GCM)
        val key = try {
            aes.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, rawKey)
        } catch (e: Exception) {
            throw BundleCodecException(BundleError.DECRYPT_FAILED, "bad key: ${e.message}")
        }
        return try {
            key.cipher().decrypt(ciphertext)
        } catch (e: Exception) {
            throw BundleCodecException(BundleError.DECRYPT_FAILED, "decrypt failed: ${e.message}")
        }
    }

    private suspend fun parseContainer(container: ByteArray): BundleBody {
        if (!container.startsWith(MAGIC)) {
            throw BundleCodecException(BundleError.BAD_MAGIC, "decrypted payload is not a container")
        }
        if (container.size < HEADER_BYTES) {
            throw BundleCodecException(BundleError.HASH_MISMATCH, "container truncated")
        }
        val version = container[MAGIC.size]
        if (version != CONTAINER_VERSION) {
            throw BundleCodecException(BundleError.UNSUPPORTED_VERSION, "container version $version")
        }
        val expectedHash = container.copyOfRange(MAGIC.size + 1, HEADER_BYTES)
        val body = container.copyOfRange(HEADER_BYTES, container.size)
        if (!sha256(body).contentEquals(expectedHash)) {
            throw BundleCodecException(BundleError.HASH_MISMATCH, "body does not match header hash")
        }
        return try {
            EchoJson.decodeFromString<BundleBody>(body.decodeToString())
        } catch (e: Exception) {
            // Hash matched, so the bytes are authentic but unreadable here:
            // a newer writer's shape, i.e. a version problem, not corruption.
            throw BundleCodecException(BundleError.UNSUPPORTED_VERSION, "body undecodable: ${e.message}")
        }
    }

    /**
     * First 8 bytes of SHA-256 over the body's JSON bytes, as lowercase
     * hex — the `s` fingerprint carried in the QR URI (§5.4). Recomputed
     * from the decoded body on receipt; [EchoJson] encoding is
     * deterministic for a given body, so the fingerprint is stable.
     */
    suspend fun sha8Hex(body: BundleBody): String =
        sha256(EchoJson.encodeToString(body).encodeToByteArray())
            .copyOfRange(0, 8)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private suspend fun sha256(bytes: ByteArray): ByteArray =
        CryptographyProvider.Default.get(SHA256).hasher().hash(bytes)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
