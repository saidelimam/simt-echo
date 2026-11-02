package app.simt.echo.transfer

/**
 * Cryptographically secure random bytes (audit 0007 R1, closed in W14-04).
 *
 * The LAN offer token is a bearer credential: whoever presents it downloads
 * the sealed bundle. `kotlin.random.Random` is a seeded PRNG, not a CSPRNG,
 * so the token comes from the platform CSPRNG instead — `SecureRandom` on
 * Android/JVM, `SecRandomCopyBytes` on iOS. The bundle key was never
 * affected (platform-crypto generated inside `BundleCodec.seal`).
 */
expect fun secureRandomBytes(size: Int): ByteArray
