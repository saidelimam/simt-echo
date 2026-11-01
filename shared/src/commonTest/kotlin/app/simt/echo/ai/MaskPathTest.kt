package app.simt.echo.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W3-07 gate: the mask overlay parser reads worker polygons and never draws garbage. */
class MaskPathTest {

    @Test
    fun parsesAbsolutePolygon() {
        val parsed = MaskPath.parseSvgPath("M 10 20 L 30 20 L 30 40 Z")
        assertNotNull(parsed)
        assertEquals(1, parsed.size)
        assertEquals(
            listOf(SvgPoint(10f, 20f), SvgPoint(30f, 20f), SvgPoint(30f, 40f)),
            parsed[0].points,
        )
        assertTrue(parsed[0].closed)
    }

    @Test
    fun parsesRelativeAndImplicitLinetoAndHV() {
        val parsed = MaskPath.parseSvgPath("m 10 20 5 0 h 5 v 5 z")
        assertNotNull(parsed)
        assertEquals(
            listOf(SvgPoint(10f, 20f), SvgPoint(15f, 20f), SvgPoint(20f, 20f), SvgPoint(20f, 25f)),
            parsed[0].points,
        )
        assertTrue(parsed[0].closed)
    }

    @Test
    fun parsesMultipleSubpaths() {
        val parsed = MaskPath.parseSvgPath("M 0 0 L 1 0 Z M 5 5 L 6 5")
        assertNotNull(parsed)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].closed)
        assertTrue(!parsed[1].closed)
    }

    @Test
    fun rejectsCurvesGarbageAndEmpty() {
        assertNull(MaskPath.parseSvgPath(""))
        assertNull(MaskPath.parseSvgPath("not a path"))
        assertNull(MaskPath.parseSvgPath("M 10 20 C 30 30 40 40 50 50"))
        assertNull(MaskPath.parseSvgPath("M 10 L 20"))
        assertNull(MaskPath.parseSvgPath("M 10 20 # 30"))
    }
}
