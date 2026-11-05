package app.simt.echo.data

import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.ViewStrip
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One follower-recorded view strip at a confirmed waypoint (plan W18-02).
 */
@Serializable
data class RouteMemoryEntry(
    val atDistanceM: Double,
    val dataB64: String,
    val recordedAtEpochMs: Long,
) {
    /** True when the entry has finite distance and decodes to [ViewStrip.BYTES] bytes. */
    fun isValid(): Boolean {
        if (!atDistanceM.isFinite() || atDistanceM < 0.0) return false
        val decoded = runCatching { Base64.decode(dataB64) }.getOrNull() ?: return false
        return decoded.size == ViewStrip.BYTES
    }

    fun toViewStrip(): ViewStrip = ViewStrip(atDistanceM = atDistanceM, dataB64 = dataB64)
}

/**
 * Local follower-side route memory overlay keyed by [routeId] (plan W18-02).
 *
 * Stored alongside the route under `routes/<routeId>/memory.json` so it
 * shares the route's lifecycle: deleted when the route is deleted, deleted by
 * "Delete all data", and bound to the route's exact `expiresAtEpochMs`.
 * Nothing leaves the device and `route.json` / the bundle is never rewritten.
 */
@Serializable
data class RouteMemory(
    val routeId: String,
    val expiresAtEpochMs: Long? = null,
    val entries: List<RouteMemoryEntry> = emptyList(),
)

class RouteMemoryRepository(private val store: FileStore) {

    /**
     * Reads the valid, non-expired [RouteMemory] for [routeId] at [nowMs], or
     * null when absent, expired, or corrupt. Corrupted individual entries are
     * filtered out rather than crashing.
     */
    fun get(routeId: String, nowMs: Long = 0L): RouteMemory? {
        if (!isSafeStorageId(routeId)) return null
        val text = store.readText(memoryFile(routeId)) ?: return null
        val decoded = runCatching { EchoJson.decodeFromString<RouteMemory>(text) }.getOrNull()
            ?: return null
        val routeExpiry = store.readText("$ROUTES_DIR/$routeId/$ROUTE_FILE")
            ?.let { runCatching { EchoJson.decodeFromString<EchoRoute>(it) }.getOrNull() }
            ?.privacy?.expiresAtEpochMs
        val effectiveExpiry = routeExpiry ?: decoded.expiresAtEpochMs
        if (effectiveExpiry != null && effectiveExpiry <= nowMs) {
            delete(routeId)
            return null
        }
        val validEntries = decoded.entries.filter { it.isValid() }
        return decoded.copy(expiresAtEpochMs = effectiveExpiry, entries = validEntries)
    }

    /**
     * Returns valid overlay [ViewStrip]s for [routeId] at [nowMs]; empty when
     * absent, expired, or corrupt.
     */
    fun loadOverlayStrips(routeId: String, nowMs: Long = 0L): List<ViewStrip> =
        get(routeId, nowMs)?.entries?.map { it.toViewStrip() }.orEmpty()

    /**
     * Encodes [strip] observed at [atDistanceM] into a storable overlay entry,
     * quantised to the same waypoint grid [recordFollow] prunes on. Returns
     * null for a position or a strip that cannot be stored.
     */
    fun encodeObservedStrip(atDistanceM: Double, strip: ByteArray): ViewStrip? {
        if (!atDistanceM.isFinite() || atDistanceM < 0.0) return null
        if (strip.size != ViewStrip.BYTES) return null
        return ViewStrip(atDistanceM = canonicalWaypointM(atDistanceM), dataB64 = Base64.encode(strip))
    }

    /**
     * Records follower-captured [observedStrips] after a follow completes.
     *
     * Gate (§8 W18-02): stores only when `arrivalSigmaM < 2.0` AND
     * `visualLockRatio01 >= 0.60`. Maintains at most [MAX_STRIPS_PER_WAYPOINT]
     * (3) extra strips per waypoint using LRU (evicting the oldest
     * `recordedAtEpochMs`), and stamps the route's exact `expiresAtEpochMs`.
     */
    fun recordFollow(
        route: EchoRoute,
        arrivalSigmaM: Double,
        visualLockRatio01: Double,
        observedStrips: List<ViewStrip>,
        nowMs: Long,
    ): Boolean {
        if (!isSafeStorageId(route.routeId)) return false
        if (!arrivalSigmaM.isFinite() || arrivalSigmaM >= MAX_ARRIVAL_SIGMA_M) return false
        if (!visualLockRatio01.isFinite() || visualLockRatio01 < MIN_VISUAL_LOCK_RATIO) return false

        val expiry = route.privacy.expiresAtEpochMs
        if (expiry != null && expiry <= nowMs) {
            delete(route.routeId)
            return false
        }

        val incoming = observedStrips
            .map { RouteMemoryEntry(canonicalWaypointM(it.atDistanceM), it.dataB64, nowMs) }
            .filter { it.isValid() }
        if (incoming.isEmpty()) return false

        val existing = get(route.routeId, nowMs)?.entries.orEmpty()
        val combined = ArrayList<RouteMemoryEntry>(existing.size + incoming.size)
        combined.addAll(existing)
        combined.addAll(incoming)

        // Group by canonical waypoint distance (0.5 m resolution) and keep up to 3 most recent (LRU eviction).
        val byWaypoint = LinkedHashMap<Int, MutableList<RouteMemoryEntry>>()
        for (entry in combined) {
            val key = (entry.atDistanceM / WAYPOINT_QUANTUM_M).roundToInt()
            byWaypoint.getOrPut(key) { ArrayList() }.add(entry)
        }

        val pruned = ArrayList<RouteMemoryEntry>()
        for ((_, list) in byWaypoint) {
            val kept = list
                .sortedBy { it.recordedAtEpochMs }
                .takeLast(MAX_STRIPS_PER_WAYPOINT)
            pruned.addAll(kept)
        }
        pruned.sortWith(compareBy<RouteMemoryEntry> { it.atDistanceM }.thenBy { it.recordedAtEpochMs })

        val updated = RouteMemory(
            routeId = route.routeId,
            expiresAtEpochMs = expiry,
            entries = pruned,
        )
        store.writeText(memoryFile(route.routeId), EchoJson.encodeToString(updated))
        return true
    }

    /** Deletes the overlay for [routeId]. */
    fun delete(routeId: String) {
        if (!isSafeStorageId(routeId)) return
        store.deleteRecursively(memoryFile(routeId))
    }

    /** Deletes every route memory overlay across all routes. */
    fun deleteAll(): Int {
        var removed = 0
        for (dir in store.list(ROUTES_DIR)) {
            val path = "$ROUTES_DIR/${dir.name}/$MEMORY_FILE"
            if (store.exists(path)) {
                store.deleteRecursively(path)
                removed++
            }
        }
        return removed
    }

    /** Purges expired route memory overlays at [nowMs]. */
    fun purgeExpired(nowMs: Long): Int {
        var purged = 0
        for (dir in store.list(ROUTES_DIR)) {
            val routeId = dir.name
            val path = memoryFile(routeId)
            if (!store.exists(path)) continue
            val text = store.readText(path) ?: continue
            val decoded = runCatching { EchoJson.decodeFromString<RouteMemory>(text) }.getOrNull()
            val routeExpiry = store.readText("$ROUTES_DIR/$routeId/$ROUTE_FILE")
                ?.let { runCatching { EchoJson.decodeFromString<EchoRoute>(it) }.getOrNull() }
                ?.privacy?.expiresAtEpochMs
            val expiry = routeExpiry ?: decoded?.expiresAtEpochMs
            if (expiry != null && expiry <= nowMs) {
                delete(routeId)
                purged++
            }
        }
        return purged
    }

    private fun canonicalWaypointM(d: Double): Double =
        ((d / WAYPOINT_QUANTUM_M).roundToInt() * WAYPOINT_QUANTUM_M * 100.0).roundToInt() / 100.0

    private fun memoryFile(routeId: String): String = "$ROUTES_DIR/$routeId/$MEMORY_FILE"

    companion object {
        const val MAX_STRIPS_PER_WAYPOINT = 3
        const val MAX_ARRIVAL_SIGMA_M = 2.0
        const val MIN_VISUAL_LOCK_RATIO = 0.60
        private const val WAYPOINT_QUANTUM_M = 0.5
        private const val ROUTES_DIR = "routes"
        private const val ROUTE_FILE = "route.json"
        const val MEMORY_FILE = "memory.json"
    }
}
