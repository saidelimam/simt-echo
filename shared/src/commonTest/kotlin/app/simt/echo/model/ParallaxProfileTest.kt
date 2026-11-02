package app.simt.echo.model

import app.simt.echo.Fixtures
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

object ParallaxFixtures {
    val profile = ParallaxProfile(
        inverseDepth = Base64.encode(byteArrayOf(0, -32, 1, 127, -128)),
        trackability = Base64.encode(byteArrayOf(0, -1, 127, 80, 60)),
        viewStrips = listOf(ViewStrip(2.0, Base64.encode(ByteArray(ViewStrip.BYTES) { (it - 48).toByte() }))),
        legSigmaRad = listOf(0.1, 0.2, 0.3),
        estimatorId = "echo-parallax-01",
        intrinsicsClass = "DEFAULT",
    )
    val route = Fixtures.V2_ROUTE.withPerception(
        requireNotNull(Fixtures.V2_ROUTE.perception).copy(parallax = profile),
    )
}

/** Frozen pre-parallax perception reader: no new model in its field graph. */
@Serializable
data class V20PerceptionView(
    val fingerprints: List<NodeFingerprint> = emptyList(),
    val waypointPrints: List<WaypointPrint> = emptyList(),
    val magProfile: MagProfile? = null,
    val sigilSeed: Long = 0L,
    val health: RouteHealth? = null,
)

class ParallaxProfileTest {
    @Test
    fun onlyMatchingEstimatorAndKnownVersionAreCompatible() {
        val profile = ParallaxFixtures.profile
        assertTrue(profile.compatibleWith("echo-parallax-01"))
        assertFalse(profile.compatibleWith("different-estimator"))
        assertFalse(profile.copy(estimatorId = "").compatibleWith(""))
        assertFalse(profile.copy(parallaxVersion = 2).compatibleWith(profile.estimatorId))
        assertFalse(profile.copy(stepM = 1.0).compatibleWith(profile.estimatorId))
    }

    @Test
    fun invalidProducerShapesAreRejected() {
        val good = ParallaxFixtures.profile
        val invalid = listOf(
            good.copy(parallaxVersion = 2),
            good.copy(stepM = 1.0),
            good.copy(estimatorId = ""),
            good.copy(intrinsicsClass = ""),
            good.copy(inverseDepth = "!"),
            good.copy(trackability = "!"),
            good.copy(trackability = ""),
            good.copy(legSigmaRad = listOf(-0.1)),
            good.copy(legSigmaRad = listOf(Double.NaN)),
            good.copy(legSigmaRad = listOf(Double.POSITIVE_INFINITY)),
            good.copy(legSigmaRad = listOf(PI + 0.1)),
            good.copy(viewStrips = listOf(good.viewStrips.single().copy(atDistanceM = -1.0))),
            good.copy(viewStrips = listOf(good.viewStrips.single().copy(atDistanceM = Double.NaN))),
            good.copy(viewStrips = listOf(good.viewStrips.single().copy(dataB64 = "AA=="))),
            good.copy(viewStrips = good.viewStrips + good.viewStrips),
        )
        invalid.forEach { profile ->
            assertFailsWith<IllegalArgumentException>("accepted $profile") { profile.validate() }
        }
        assertEquals(good, good.validate(3))
        assertFailsWith<IllegalArgumentException> { good.validate(2) }
        assertEquals(emptyList(), good.copy(legSigmaRad = emptyList()).validate(3).legSigmaRad)
    }

    @Test
    fun blobsPreserveAllSignedAndUnsignedBytePatternsCompactly() {
        val bytes = ByteArray(256) { it.toByte() }
        val profile = ParallaxFixtures.profile.copy(
            inverseDepth = Base64.encode(bytes),
            trackability = Base64.encode(bytes),
        ).validate()
        assertTrue(bytes.contentEquals(Base64.decode(profile.inverseDepth)))
        assertEquals(255, Base64.decode(profile.trackability).last().toInt() and 255)
        assertTrue(profile.inverseDepth.length < bytes.joinToString(",").length)
        assertEquals(96, Base64.decode(profile.viewStrips.single().dataB64).size)
    }

    @Test
    fun oneDimensionalMarginalsDoNotUniquelyDetermineAnImage() {
        val first = listOf(listOf(10, 20), listOf(30, 40))
        val second = listOf(listOf(15, 15), listOf(25, 45))
        assertNotEquals(first, second)
        assertEquals(first.map { it.sum() }, second.map { it.sum() })
        assertEquals((0..1).map { x -> first.sumOf { it[x] } }, (0..1).map { x -> second.sumOf { it[x] } })
    }
}
