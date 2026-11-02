package app.simt.echo.transfer

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be >= 0, got $size" }
    return ByteArray(size).also { secureRandom.nextBytes(it) }
}
