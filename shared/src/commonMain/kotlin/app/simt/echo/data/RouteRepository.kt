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

    fun get(routeId: String): EchoRoute? {
        val text = store.readText(routeFile(routeId)) ?: return null
        return EchoJson.decodeFromString<EchoRoute>(text)
    }

    /**
     * Writes `route.json` and copies [assets] (asset id → source path)
     * into `assets/`. Asset files keep their id as filename, with `.jpg`
     * appended when the id carries no extension (plan §5.3 layout).
     */
    fun save(route: EchoRoute, assets: Map<String, Path> = emptyMap()) {
        store.writeText(routeFile(route.routeId), EchoJson.encodeToString(route))
        for ((assetId, source) in assets) {
            val bytes = store.fileSystem.read(source) { readByteArray() }
            store.writeBytes(assetFile(route.routeId, assetId), bytes)
        }
    }

    /** Raw bytes of one saved asset, or null (received-bundle import). */
    fun readAsset(routeId: String, assetId: String): ByteArray? =
        store.readBytes(assetFile(routeId, assetId))

    /** Saves [route] with asset bytes directly (received-bundle import). */
    fun saveWithAssetBytes(route: EchoRoute, assetBytes: Map<String, ByteArray>) {
        store.writeText(routeFile(route.routeId), EchoJson.encodeToString(route))
        for ((assetId, bytes) in assetBytes) {
            store.writeBytes(assetFile(route.routeId, assetId), bytes)
        }
    }

    fun delete(routeId: String) {
        store.deleteRecursively("$ROUTES_DIR/$routeId")
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
    }
}
