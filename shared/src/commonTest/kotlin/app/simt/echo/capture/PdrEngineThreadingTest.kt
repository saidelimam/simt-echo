package app.simt.echo.capture

import app.simt.echo.capture.visual.VisualEgo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R15-05: visual evidence crosses threads only through [VisualEgoInbox]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PdrEngineThreadingTest {

    @Test
    fun visualEgoIsAppliedOnlyOnImuThread() {
        val engine = PdrEngine(visualAssist = true)
        engine.start()
        val ego = VisualEgo().apply {
            expansionPerSec = 0.20
            expansionSigmaPerSec = 0.001
            confidence01 = 0.9
            inliers = 12
        }

        engine.onVisualEgo(ego, 1_000_000_000L)
        assertEquals(0, engine.stepGate.confidentFrameCount, "analysis-thread enqueue must not touch the gate")
        assertEquals(VisualMotionEvidence.UNKNOWN, engine.stepGate.lastEvidence)

        engine.onAccelerometer(ImuSample(1_020_000_000L, 0.0, 0.0, 9.81))
        assertEquals(1, engine.stepGate.confidentFrameCount, "the IMU sample must drain the inbox")
        assertEquals(VisualMotionEvidence.MOVING, engine.stepGate.lastEvidence)
    }

    @Test
    fun concurrentFeedsKeepUpdatesMonotonic() = runTest {
        val engine = PdrEngine(visualAssist = true)
        engine.start()
        val updates = mutableListOf<app.simt.echo.platform.MotionUpdate>()
        val collector = launch { engine.updates.collect { updates += it } }
        runCurrent()

        val start = CompletableDeferred<Unit>()
        val visual = launch(Dispatchers.Default) {
            start.await()
            val ego = VisualEgo().apply {
                expansionPerSec = 0.20
                expansionSigmaPerSec = 0.001
                confidence01 = 0.9
                inliers = 12
            }
            repeat(2_000) { i ->
                engine.onVisualEgo(ego, i * 100_000_000L)
            }
        }
        val imu = launch(Dispatchers.Default) {
            start.await()
            repeat(10_000) { i ->
                val tNs = i * 20_000_000L
                val tSec = i / 50.0
                engine.onAccelerometer(
                    ImuSample(tNs, 0.0, 0.0, 9.81 + 2.0 * sin(2.0 * PI * 2.0 * tSec)),
                )
            }
        }
        start.complete(Unit)
        imu.join()
        visual.join()
        runCurrent()
        collector.cancel()

        assertTrue(updates.isNotEmpty(), "the synthetic walk must emit updates")
        assertTrue(
            updates.zipWithNext().all { (a, b) -> b.tNs >= a.tNs },
            "update time must never rewind: ${updates.zipWithNext().firstOrNull { (a, b) -> b.tNs < a.tNs }}",
        )
        assertTrue(engine.stepGate.acceptedStepCount + engine.stepGate.rejectedPhantomCount > 0)
    }
}
