package app.simt.echo.data

import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import kotlinx.serialization.encodeToString
import okio.Path

/**
 * Saved and imported routes under `routes/<routeId>/` (plan §5.3).
 * One writer per route id; no partial route is ever listed.
 */
class RouteRepository(private val store: FileStore) {

    fun list(): List<EchoRoute> =
        store.list(ROUTES_DIR)
            .filter { store.exists("$ROUTES_DIR/${it.name}/$ROUTE_FILE") }
            .mapNotNull { get(it.name) }
            .sortedByDescending { it.createdAtEpochMs }

    /** One saved route, or null when absent/corrupt — a torn or
     * hand-edited `route.json` must never throw into list/screens/
     * startup purge (W7-10; same seam as TrialRepository). */
    fun get(routeId: String): EchoRoute? {
        val text = store.readText(routeFile(routeId)) ?: return null
        return runCatching { EchoJson.decodeFromString<EchoRoute>(text) }.getOrNull()
    }

    /**
     * Writes `route.json` and copies [assets] (asset id → source path)
     * into `assets/`. Asset files keep their id as filename, with `.jpg`
     * appended when the id carries no extension (plan §5.3 layout).
     */
    fun save(route: EchoRoute, assets: Map<String, Path> = emptyMap()) {
        require(isSafeStorageId(route.routeId)) { "unsafe route id: ${route.routeId}" }
        store.writeText(routeFile(route.routeId), EchoJson.encodeToString(route))
        for ((assetId, source) in assets) {
            require(isSafeStorageId(assetId)) { "unsafe asset id: $assetId" }
            val bytes = store.fileSystem.read(source) { readByteArray() }
            store.writeBytes(assetFile(route.routeId, assetId), bytes)
        }
    }

    /** Raw bytes of one saved asset, or null (received-bundle import). */
    fun readAsset(routeId: String, assetId: String): ByteArray? =
        store.readBytes(assetFile(routeId, assetId))

    /** Saves [route] with asset bytes directly (received-bundle import). */
    fun saveWithAssetBytes(route: EchoRoute, assetBytes: Map<String, ByteArray>) {
        require(isSafeStorageId(route.routeId)) { "unsafe route id: ${route.routeId}" }
        store.writeText(routeFile(route.routeId), EchoJson.encodeToString(route))
        for ((assetId, bytes) in assetBytes) {
            require(isSafeStorageId(assetId)) { "unsafe asset id: $assetId" }
            store.writeBytes(assetFile(route.routeId, assetId), bytes)
        }
    }

    fun delete(routeId: String) {
        store.deleteRecursively("$ROUTES_DIR/$routeId")
    }

    /**
     * Deletes every saved route (W8-11 Delete all). Returns how many
     * route directories were removed.
     */
    fun deleteAll(): Int {
        val count = store.list(ROUTES_DIR).size
        store.deleteRecursively(ROUTES_DIR)
        return count
    }

    /** Persists the cloud job id for a saved route (post-save delete offer). */
    fun saveRouteJobId(routeId: String, jobId: String) {
        require(isSafeStorageId(routeId)) { "unsafe route id: $routeId" }
        store.writeText("$ROUTES_DIR/$routeId/$JOB_ID_FILE", jobId)
    }

    /** The persisted cloud job id for [routeId], or null. */
    fun routeJobId(routeId: String): String? =
        store.readText("$ROUTES_DIR/$routeId/$JOB_ID_FILE")?.trim()?.takeIf { it.isNotEmpty() }

    /** Clears the persisted cloud job id after a successful cloud delete. */
    fun clearRouteJobId(routeId: String) {
        store.deleteRecursively("$ROUTES_DIR/$routeId/$JOB_ID_FILE")
    }

    /**
     * Deletes routes whose privacy expiry has passed.
     * @return how many routes were purged.
     */
    fun purgeExpired(nowMs: Long): Int {
        var purged = 0
        for (route in list()) {
            val expiry = route.privacy.expiresAtEpochMs
            if (expiry != null && expiry <= nowMs) {
                delete(route.routeId)
                purged++
            }
        }
        return purged
    }

    private fun routeFile(routeId: String) = "$ROUTES_DIR/$routeId/$ROUTE_FILE"

    private fun assetFile(routeId: String, assetId: String): String {
        val name = if ('.' in assetId) assetId else "$assetId.jpg"
        return "$ROUTES_DIR/$routeId/$ASSETS_DIR/$name"
    }

    private companion object {
        const val ROUTES_DIR = "routes"
        const val ROUTE_FILE = "route.json"
        const val ASSETS_DIR = "assets"
        const val JOB_ID_FILE = "cloud-job.txt"
    }
}

/**
 * A storage id is safe when every path component is a plain name: no
 * empty components, no `.`/`..`, no leading slash. Imported bundles are
 * untrusted input — a crafted `routeId`/`assetId` must never write
 * outside the route's own directory (review 0003 R1). App ids satisfy
 * this, including nested crop names like `kf/manual_<id>.jpg`.
 */
fun isSafeStorageId(id: String): Boolean {
    if (id.isEmpty() || id.length > 256) return false
    return id.split('/').all { part ->
        part.isNotEmpty() &&
            part != "." &&
            part != ".." &&
            part.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
