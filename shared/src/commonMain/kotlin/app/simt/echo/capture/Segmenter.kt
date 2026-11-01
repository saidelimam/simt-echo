package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoIds
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic segmentation (plan §6.3): a [CaptureLog] becomes a draft
 * [EchoRoute]. Resamples the sensor series every 0.5 m, detects turns with
 * hysteresis, derives level changes from barometric pressure, merges user
 * marks into nearby turns, and emits nodes, legs and the walked path.
 *
 * Pure: the geometry always originates from the log; AI attaches to the
 * resulting nodes later and never moves them (plan §0).
 */
fun segment(log: CaptureLog, cfg: EchoConfig, routeId: String, nowMs: Long): EchoRoute {
    val samples = log.samples
    if (samples.isEmpty()) {
        val dest = log.destinationLabel.ifEmpty { DEFAULT_DESTINATION_LABEL }
        return EchoRoute(
            routeId = routeId,
            name = log.routeName,
            createdAtEpochMs = nowMs,
            creatorLabel = DEFAULT_CREATOR_LABEL,
            motionSource = log.motionSource,
            totalLengthM = 0.0,
            start = START_ANCHOR,
            nodes = listOf(
                node(0, NodeType.START, 0.0, "Start", null, cueFor(NodeType.START, null, null)),
                node(1, NodeType.DESTINATION, 0.0, dest, null, cueFor(NodeType.DESTINATION, dest, null)),
            ),
            legs = listOf(RouteLeg(EchoIds.nodeId(0), EchoIds.nodeId(1), 0.0, 0.0)),
            path = emptyList(),
        )
    }

    val geo = Geo(samples)
    val maxD = samples.last().d

    // Steps 1–2: resample every [cfg.resampleStepM] metres (yaw circularly
    // interpolated), then circularly smooth over ±[cfg.yawSmoothWindowM].
    val pts = geo.resampled(maxD, cfg.resampleStepM)
    val window = (cfg.yawSmoothWindowM / cfg.resampleStepM).roundToInt()
    val smooth = pts.mapIndexed { i, pt ->
        val lo = (i - window).coerceAtLeast(0)
        val hi = (i + window).coerceAtMost(pts.size - 1)
        pt.copy(yaw = circularMean(pts.subList(lo, hi + 1).map { it.yaw }))
    }

    // Step 3: turn detection with hysteresis (§6.3 step 3).
    val turns = detectTurns(smooth, cfg)

    // Step 5: node distances before any dropping — START, turns, user marks
    // (a mark within [cfg.markMergeDistanceM] of a turn merges into it and
    // supplies the label), DESTINATION.
    val mergedMarks = mutableSetOf<String>()
    val raw = mutableListOf<RawNode>()
    raw += RawNode(0.0, NodeType.START, "Start")
    for (t in turns) {
        val merged = log.marks.firstOrNull { abs(it.d - t.d) <= cfg.markMergeDistanceM }
        if (merged != null) {
            mergedMarks += merged.label
            raw += RawNode(t.d, NodeType.TURN, merged.label, t.dir)
        } else {
            raw += RawNode(t.d, NodeType.TURN, "Turn ${turnWord(t.dir)}", t.dir)
        }
    }
    for (mark in log.marks) {
        if (mark.label !in mergedMarks) {
            raw += RawNode(mark.d, NodeType.CHECKPOINT, mark.label)
        }
    }
    val dest = log.destinationLabel.ifEmpty { DEFAULT_DESTINATION_LABEL }
    raw += RawNode(maxD, NodeType.DESTINATION, dest)

    // Step 4: a leg whose |Δh| exceeds [cfg.levelThresholdM] gets a
    // LEVEL_CHANGE node at the half-change point (barometric, §6.3 step 4).
    for (i in 0 until raw.size - 1) {
        geo.levelChange(raw[i], raw[i + 1], cfg)?.let { raw += it }
    }
    raw.sortBy { it.d }

    // Step 5 (cont.): CHECKPOINT/LEVEL nodes closer than [cfg.minNodeSpacingM]
    // to the previous node drop; START/DESTINATION never do.
    val kept = mutableListOf<RawNode>()
    for (rn in raw) {
        if (rn.type == NodeType.CHECKPOINT || rn.type == NodeType.LEVEL_CHANGE) {
            val prev = kept.lastOrNull()
            if (prev != null && rn.d - prev.d < cfg.minNodeSpacingM) continue
        }
        kept += rn
    }

    // Steps 5–7: ids in distance order, legs carrying the circular-mean
    // heading of the smoothed yaw on each leg, capture times shifted onto
    // the log's video clock.
    val nodes = kept.mapIndexed { i, rn ->
        node(
            index = i,
            type = rn.type,
            d = rn.d,
            label = rn.label,
            turn = rn.turn,
            cue = cueFor(rn.type, if (rn.type == NodeType.DESTINATION) rn.label else null, rn.turn),
            levelDelta = if (rn.type == NodeType.LEVEL_CHANGE) rn.levelDelta else 0,
            captureTMs = geo.captureTMsAt(rn.d) - log.videoStartNs / 1_000_000L,
        )
    }
    val legs = nodes.zipWithNext { a, b ->
        RouteLeg(
            fromNode = a.id,
            toNode = b.id,
            lengthM = b.atDistanceM - a.atDistanceM,
            headingRelDeg = geo.legHeadingDeg(a.atDistanceM, b.atDistanceM, smooth, cfg),
        )
    }
    val path = smooth.map { PathPoint(it.d, it.x, it.y) }

    return EchoRoute(
        routeId = routeId,
        name = log.routeName,
        createdAtEpochMs = nowMs,
        creatorLabel = DEFAULT_CREATOR_LABEL,
        motionSource = log.motionSource,
        totalLengthM = maxD,
        start = START_ANCHOR,
        nodes = nodes,
        legs = legs,
        path = path,
    )
}

private const val DEFAULT_CREATOR_LABEL = "creator"
private const val DEFAULT_DESTINATION_LABEL = "Destination"
private val START_ANCHOR = StartAnchor(
    description = "Start",
    photoAssetId = EchoIds.assetId("start"),
    alignmentHint = "Stand where the photo was taken and face the same way",
)

/** One resampled point: distance, integrated position, yaw. */
internal data class SamplePoint(
    val d: Double,
    val x: Double,
    val y: Double,
    val yaw: Double,
)

/** A node before id assignment and dropping; [levelDelta] for LEVEL nodes. */
private data class RawNode(
    val d: Double,
    val type: NodeType,
    val label: String,
    val turn: TurnDir? = null,
    val levelDelta: Int = 0,
)

/** One detected turn: its midpoint distance and classified direction. */
private data class Turn(
    val d: Double,
    val dir: TurnDir,
)

/**
 * Integrated geometry over the sample series. Positions follow §6.2
 * (`x += L·sin(yaw)`, `y += L·cos(yaw)`); everything else interpolates
 * between the bracketing samples.
 */
private class Geo(private val samples: List<CaptureSample>) {
    private val n = samples.size
    private val xs = DoubleArray(n)
    private val ys = DoubleArray(n)
    private val dir = DoubleArray(n)

    init {
        var x = 0.0
        var y = 0.0
        for (i in 1 until n) {
            val len = (samples[i].d - samples[i - 1].d).coerceAtLeast(0.0)
            dir[i] = if (len > 0.0) {
                circularMean(listOf(samples[i - 1].yaw, samples[i].yaw))
            } else {
                samples[i].yaw
            }
            x += len * sin(dir[i])
            y += len * cos(dir[i])
            xs[i] = x
            ys[i] = y
        }
    }

    /**
     * Last sample index with distance ≤ [d] (inclusive); -1 before the
     * series. The inclusive bound means [at] interpolates *from* the
     * bracketing sample forward, so a resampled point that lands exactly
     * on a sample sees that sample's yaw as its lower bound.
     */
    private fun upper(d: Double): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].d <= d) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    private fun at(d: Double): SamplePoint {
        if (n == 1 || d <= samples[0].d) {
            return SamplePoint(d.coerceAtLeast(0.0), 0.0, 0.0, samples.first().yaw)
        }
        val i = upper(d).coerceIn(1, n - 1)
        val d0 = samples[i - 1].d
        val d1 = samples[i].d
        val f = if (d1 > d0) ((d - d0) / (d1 - d0)).coerceIn(0.0, 1.0) else 0.0
        val yaw = wrapPi(samples[i - 1].yaw + f * wrapPi(samples[i].yaw - samples[i - 1].yaw))
        return SamplePoint(
            d = d,
            x = xs[i - 1] + (d - d0) * sin(dir[i]),
            y = ys[i - 1] + (d - d0) * cos(dir[i]),
            yaw = yaw,
        )
    }

    /** Resampled series every [stepM], ending at [maxD]. */
    fun resampled(maxD: Double, stepM: Double): List<SamplePoint> {
        val out = mutableListOf<SamplePoint>()
        var i = 0
        var d = 0.0
        while (d <= maxD - stepM / 2) {
            out += at(d)
            i++
            d = i * stepM
        }
        out += at(maxD)
        return out
    }

    /** tMs at [d] by linear interpolation; the caller shifts to video time. */
    fun captureTMsAt(d: Double): Long {
        if (n == 1 || d <= samples[0].d) return samples.first().tNs / 1_000_000L
        val i = upper(d).coerceIn(1, n - 1)
        val d0 = samples[i - 1].d
        val d1 = samples[i].d
        val f = if (d1 > d0) ((d - d0) / (d1 - d0)).coerceIn(0.0, 1.0) else 0.0
        return ((samples[i - 1].tNs + f * (samples[i].tNs - samples[i - 1].tNs)).toLong()) / 1_000_000L
    }

    /**
     * Circular-mean heading, degrees, of the smoothed yaw on leg [a]→[b].
     * Excludes [cfg.yawSmoothWindowM] from each end so the turn transition
     * does not bias the leg heading.
     */
    fun legHeadingDeg(a: Double, b: Double, smooth: List<SamplePoint>, cfg: EchoConfig): Double {
        val lo = ((a + cfg.yawSmoothWindowM) / cfg.resampleStepM).toInt().coerceIn(0, smooth.size - 1)
        val hi = ((b - cfg.yawSmoothWindowM) / cfg.resampleStepM).toInt().coerceIn(lo, smooth.size - 1)
        return Math.toDegrees(circularMean(smooth.subList(lo, hi + 1).map { it.yaw }))
    }

    /**
     * A LEVEL_CHANGE [RawNode] when leg [a]→[b] climbs or drops more than
     * [cfg.levelThresholdM]; the node sits at the half-change distance and
     * `levelDelta = round(Δh / [cfg.levelPerFloorM])`. Null when the leg has
     * no pressure readings.
     */
    fun levelChange(a: RawNode, b: RawNode, cfg: EchoConfig): RawNode? {
        val i0 = upper(a.d).coerceIn(0, n - 1)
        val i1 = upper(b.d).coerceIn(i0, n - 1)
        val p0 = samples[i0].p
        val p1 = samples[i1].p
        if (p0 == null || p1 == null) return null
        val base = basePressure()
        val dh = hPaToM(p1, base) - hPaToM(p0, base)
        if (abs(dh) <= cfg.levelThresholdM) return null
        val levelDelta = (dh / cfg.levelPerFloorM).roundToInt()
        val mid = (a.d + b.d) / 2
        return RawNode(
            d = mid,
            type = NodeType.LEVEL_CHANGE,
            label = "Level ${if (levelDelta > 0) "+" else ""}$levelDelta",
            levelDelta = levelDelta,
        )
    }

    /** Pressure at the start of the capture; the barometric formula needs one. */
    private fun basePressure(): Double =
        samples.mapNotNull { it.p }.firstOrNull() ?: 1013.25
}

/** Standard atmosphere: h = 44330 · (1 − (p/p0)^(1/5.255)), metres. */
private fun hPaToM(p: Double, p0: Double): Double =
    44330.0 * (1.0 - (p / p0).pow(1.0 / 5.255))

/**
 * Step 3 walk over the smoothed series. Keeps the rolling leg heading
 * (circular mean of the last [cfg.legHeadingWindowM]); opens a turn once the
 * smoothed yaw deviates more than [cfg.turnOpenDeviationDeg] for
 * [cfg.turnOpenDistanceM]; closes it when the yaw has moved less than
 * [cfg.turnCloseChangeDeg] over [cfg.turnOpenDistanceM]. A closed turn is
 * kept when the leg-to-leg angle clears [cfg.turnMinAngleDeg] and classified
 * per §6.3: ≥ [cfg.uTurnAngleDeg] → U_TURN, else by sign.
 */
private fun detectTurns(smooth: List<SamplePoint>, cfg: EchoConfig): List<Turn> {
    val turns = mutableListOf<Turn>()
    if (smooth.size < 2) return turns

    val openDist = (cfg.turnOpenDistanceM / cfg.resampleStepM).roundToInt()
    val legWin = (cfg.legHeadingWindowM / cfg.resampleStepM).roundToInt()
    val openThresh = Math.toRadians(cfg.turnOpenDeviationDeg)
    val closeThresh = Math.toRadians(cfg.turnCloseChangeDeg)
    val minAngle = Math.toRadians(cfg.turnMinAngleDeg)
    val uTurnAngle = Math.toRadians(cfg.uTurnAngleDeg)

    var i = legWin
    while (i < smooth.size) {
        val legHeading = circularMean(
            smooth.subList((i - legWin).coerceAtLeast(0), i + 1).map { it.yaw },
        )
        val dev = abs(wrapPi(smooth[i].yaw - legHeading))
        if (dev <= openThresh) {
            i++
            continue
        }

        // Deviation confirmed at [i]: the turn already began up to
        // [openDist] points earlier. Walk backwards to the last point that
        // still agrees with the pre-turn heading, then look ahead for the
        // settle (close) point where the yaw has stopped moving.
        var openIdx = i
        while (openIdx > 0 && abs(wrapPi(smooth[openIdx - 1].yaw - legHeading)) > openThresh) {
            openIdx--
        }
        var closeIdx = smooth.size - 1
        var j = i + openDist
        while (j < smooth.size) {
            if (abs(wrapPi(smooth[j].yaw - smooth[j - openDist].yaw)) < closeThresh) {
                closeIdx = j
                break
            }
            j++
        }

        val newLeg = circularMean(
            smooth.subList((closeIdx - legWin).coerceAtLeast(0), closeIdx + 1).map { it.yaw },
        )
        val alpha = wrapPi(newLeg - legHeading)
        if (abs(alpha) >= minAngle) {
            val dir = when {
                abs(alpha) >= uTurnAngle -> TurnDir.U_TURN
                alpha > 0 -> TurnDir.RIGHT
                else -> TurnDir.LEFT
            }
            turns += Turn(d = turnMidpointD(smooth, openIdx, closeIdx, legHeading, newLeg, legWin), dir = dir)
            i = closeIdx + 1
        } else {
            // Wobble that came back: resume just past the opening point.
            i++
        }
    }
    return turns
}

/**
 * Distance where the smoothed yaw crosses the circular midpoint of the
 * old and new leg headings — the 50 %-transition point of the turn.
 * Falls back to the arithmetic midpoint of [openIdx] and [closeIdx] when
 * the crossing is not found (e.g. a very wide, gentle curve).
 */
private fun turnMidpointD(
    smooth: List<SamplePoint>,
    openIdx: Int,
    closeIdx: Int,
    oldHeading: Double,
    newHeading: Double,
    legWin: Int,
): Double {
    val fallback = (smooth[openIdx].d + smooth[closeIdx].d) / 2.0
    val midAngle = wrapPi(oldHeading + wrapPi(newHeading - oldHeading) / 2.0)
    // The smoothed yaw can cross the midpoint before the detection point
    // (the smoothing window spreads the transition), so search a few
    // points before [openIdx] as well.
    val lo = (openIdx - legWin).coerceAtLeast(0)
    for (k in lo until closeIdx) {
        val y0 = smooth[k].yaw
        val y1 = smooth[k + 1].yaw
        val dy = wrapPi(y1 - y0)
        if (dy == 0.0) continue
        val t0 = wrapPi(midAngle - y0)
        val t1 = wrapPi(midAngle - y1)
        if (t0 * t1 < 0.0) {
            val f = t0 / (t0 - t1)
            return smooth[k].d + f * (smooth[k + 1].d - smooth[k].d)
        }
    }
    return fallback
}

private fun turnWord(dir: TurnDir): String = when (dir) {
    TurnDir.LEFT -> "left"
    TurnDir.RIGHT -> "right"
    TurnDir.U_TURN -> "U-turn"
}

/** Default cues (plan §8.4): sentence case, ≤ 6 words, no certainty. */
private fun cueFor(type: NodeType, destinationLabel: String?, turn: TurnDir?): String = when (type) {
    NodeType.START -> "Start here"
    NodeType.TURN -> "Turn ${turnWord(turn ?: TurnDir.RIGHT)}"
    NodeType.CHECKPOINT -> "Pass the checkpoint"
    NodeType.LEVEL_CHANGE -> "Change level"
    NodeType.DESTINATION -> "Arrive at ${destinationLabel ?: DEFAULT_DESTINATION_LABEL}"
}

private fun node(
    index: Int,
    type: NodeType,
    d: Double,
    label: String,
    turn: TurnDir?,
    cue: String,
    levelDelta: Int = 0,
    captureTMs: Long? = null,
): RouteNode = RouteNode(
    id = EchoIds.nodeId(index),
    type = type,
    atDistanceM = d,
    label = label,
    turn = turn,
    cue = cue,
    levelDelta = levelDelta,
    captureTMs = captureTMs,
)
