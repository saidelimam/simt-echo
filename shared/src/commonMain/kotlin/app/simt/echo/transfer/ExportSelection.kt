package app.simt.echo.transfer

import app.simt.echo.model.AiProvenance
import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.EchoSchemaVersion

/**
 * Export preview + filter unit (plan W3-08). The single place that decides
 * what leaves the phone: the Share preview sheet and the TransferService
 * export path both go through [applySelection]. Fail-closed: a toggled-off
 * crop is nulled out of the exported route copy, so it can never be
 * encoded — there is no second toggle path per screen.
 */
enum class ExpiryOption {
    NONE,
    HOURS_24,
    DAYS_7,

    /** Keep the route's stored `privacy.expiresAtEpochMs` unchanged. */
    KEEP_STORED,
}

/** Typed refusal: an already-expired route must never be offered/exported. */
class RouteExpiredException(val expiresAtEpochMs: Long) :
    IllegalStateException("This route has expired and can no longer be shared.")

const val HOURS_24_MS: Long = 24L * 3_600_000L
const val DAYS_7_MS: Long = 7L * HOURS_24_MS

fun expiryAtEpochMs(option: ExpiryOption, nowMs: Long): Long? = when (option) {
    ExpiryOption.NONE -> null
    ExpiryOption.HOURS_24 -> nowMs + HOURS_24_MS
    ExpiryOption.DAYS_7 -> nowMs + DAYS_7_MS
    // Resolved against the route in applySelection, not against nowMs.
    ExpiryOption.KEEP_STORED -> null
}

/** Disclosure: landmark text exports even when its photo is toggled off. */
const val LANDMARK_TEXT_DISCLOSURE =
    "Landmark details (title, cue and sign text) are always included, even when a landmark photo is turned off."

/**
 * Disclosure (W10-05): on-device perception data (image fingerprints and
 * the magnetic profile) travels inside QR and relay transfers, which are
 * sealed and expire with the route. The plain `.echo` file is readable
 * without a key, so file export leaves perception out unless the user
 * opts in ([TransferService.exportFile]). A route that needs it private
 * must not be shared by QR or relay.
 */
const val PERCEPTION_DISCLOSURE =
    "On-device perception data (route fingerprints, magnetic and parallax profiles, and tiny image-derived view strips) travels inside QR and " +
        "relay transfers, which are encrypted and expire with the route. A shared file leaves it out unless " +
        "you choose to include it."

/** Label of the opt-in that puts perception data into a plain, unencrypted file. */
const val FILE_PERCEPTION_OPT_IN_LABEL = "Include on-device perception data in the file"

/**
 * Disclosure (W11-04): the on-device privacy pre-filter. Frames showing
 * faces are left out before upload, and per-frame quality flags travel in
 * the manifest so the worker skips bad frames before any SAM spend.
 * Minimal and honest: it states the exclude behavior, nothing more.
 */
const val FACE_FILTER_DISCLOSURE =
    "Frames showing faces are left out before upload."

/** One toggle row in the export preview sheet. */
data class ExportItem(
    /** Asset id (`start` sentinel for the start photo, else crop asset id). */
    val id: String,
    val label: String,
    val privacyRisk: Boolean,
    val includedByDefault: Boolean,
)

/** What the user chose in the preview sheet. */
data class ExportSelection(
    val includeStartPhoto: Boolean = true,
    val excludedCropIds: Set<String> = emptySet(),
    val expiry: ExpiryOption = ExpiryOption.NONE,
)

/** Sentinel id for the start photo in [describeExport]/toggles. */
const val START_PHOTO_ITEM_ID = "start"

/**
 * Lists the start photo (when present) plus every landmark crop, in a
 * stable order. Crops flagged [privacyRisk][app.simt.echo.model.Landmark.privacyRisk]
 * default OFF; everything else defaults ON.
 */
fun describeExport(route: EchoRoute): List<ExportItem> = buildList {
    if (route.start.photoAssetId != null) {
        add(ExportItem(START_PHOTO_ITEM_ID, "Start photo", privacyRisk = false, includedByDefault = true))
    }
    route.landmarks.mapNotNull { it.cropAssetId }.distinct().forEach { assetId ->
        val risky = route.landmarks.any { it.cropAssetId == assetId && it.privacyRisk }
        val title = route.landmarks.firstOrNull { it.cropAssetId == assetId }?.title ?: assetId
        add(ExportItem(assetId, "Crop: $title", privacyRisk = risky, includedByDefault = !risky))
    }
}

/** Default selection: privacy-risk crops OFF, everything else ON, stored expiry preserved. */
fun defaultSelection(route: EchoRoute): ExportSelection {
    val base = defaultSelection(describeExport(route))
    return if (route.privacy.expiresAtEpochMs != null) {
        base.copy(expiry = ExpiryOption.KEEP_STORED)
    } else {
        base
    }
}

/** Default selection from preview rows (Share sheet, which has no route). */
fun defaultSelection(items: List<ExportItem>): ExportSelection {
    val start = items.firstOrNull { it.id == START_PHOTO_ITEM_ID }
    return ExportSelection(
        includeStartPhoto = start?.includedByDefault ?: true,
        excludedCropIds = items.filter { it.id != START_PHOTO_ITEM_ID && !it.includedByDefault }
            .map { it.id }.toSet(),
    )
}

/**
 * Applies [selection] to a route copy: excluded crops are nulled (so no
 * export path can encode them), the start photo is nulled when toggled
 * off, and the expiry lands in `privacy.expiresAtEpochMs`. Raw video is
 * never part of a route — there is nothing to strip here.
 */
fun applySelection(route: EchoRoute, selection: ExportSelection, nowMs: Long): EchoRoute {
    // Fail-closed: unreviewed AI suggestions never export (review 0005 M2).
    val kept = route.landmarks.filter { it.confirmation != Confirmation.AI_SUGGESTED }
    val keptIds = kept.map { it.id }.toSet()
    return route.copy(
        start = route.start.copy(
            photoAssetId = route.start.photoAssetId?.takeIf { selection.includeStartPhoto },
        ),
        nodes = route.nodes.map { node ->
            node.copy(landmarkIds = node.landmarkIds.filter { it in keptIds })
        },
        landmarks = kept.map { landmark ->
            if (landmark.cropAssetId != null && landmark.cropAssetId in selection.excludedCropIds) {
                landmark.copy(cropAssetId = null)
            } else {
                landmark
            }
        },
        privacy = route.privacy.copy(
            expiresAtEpochMs = if (selection.expiry == ExpiryOption.KEEP_STORED) {
                route.privacy.expiresAtEpochMs
            } else {
                expiryAtEpochMs(selection.expiry, nowMs)
            },
        ),
    )
}

/** One landmark line in the honest export summary. */
data class ExportLandmarkSummary(
    val id: String,
    val title: String,
    val cue: String,
    val signText: String?,
    val hasCrop: Boolean,
    val cropIncluded: Boolean,
    val privacyRisk: Boolean,
)

/** Everything material the bundle carries, for the Share preview. */
data class ExportSummary(
    val routeName: String,
    val creatorLabel: String,
    val createdAtEpochMs: Long,
    val nodeCount: Int,
    val pathPointCount: Int,
    val totalLengthM: Double,
    val startDescription: String,
    val hasAlignmentHint: Boolean,
    val hasStartPhoto: Boolean,
    val landmarks: List<ExportLandmarkSummary>,
    val ai: AiProvenance?,
    val expiresAtEpochMs: Long?,
    /** True when the exported route carries v2 perception fingerprints. */
    val hasPerception: Boolean,
)

/**
 * Summary of exactly what [applySelection] will export for [selection]:
 * route identity, geometry, start, every kept landmark's text (included
 * even when its photo is off), AI provenance and effective expiry.
 */
fun exportSummary(route: EchoRoute, selection: ExportSelection, nowMs: Long): ExportSummary {
    val filtered = applySelection(route, selection, nowMs)
    val originalCropById = route.landmarks.associate { it.id to (it.cropAssetId != null) }
    return ExportSummary(
        routeName = filtered.name,
        creatorLabel = filtered.creatorLabel,
        createdAtEpochMs = filtered.createdAtEpochMs,
        nodeCount = filtered.nodes.size,
        pathPointCount = filtered.path.size,
        totalLengthM = filtered.totalLengthM,
        startDescription = filtered.start.description,
        hasAlignmentHint = filtered.start.alignmentHint.isNotBlank(),
        hasStartPhoto = filtered.start.photoAssetId != null,
        landmarks = filtered.landmarks.map { landmark ->
            ExportLandmarkSummary(
                id = landmark.id,
                title = landmark.title,
                cue = landmark.cue,
                signText = landmark.signText,
                hasCrop = originalCropById[landmark.id] == true,
                cropIncluded = landmark.cropAssetId != null,
                privacyRisk = landmark.privacyRisk,
            )
        },
        ai = filtered.ai,
        expiresAtEpochMs = filtered.privacy.expiresAtEpochMs,
        hasPerception = filtered.perception != null,
    )
}

/**
 * The route as a pre-perception writer saved it: no perception, v1 stamp.
 * What a plain file export carries by default, so image-derived tracking
 * data never lands in an unencrypted file the user did not opt into.
 */
fun EchoRoute.withoutPerception(): EchoRoute =
    if (perception == null) this else copy(perception = null, schemaVersion = EchoSchemaVersion.V1)

/** Asset ids the export path may encode for this selection. */
fun selectedAssetIds(route: EchoRoute, selection: ExportSelection): List<String> {
    val filtered = applySelection(route, selection, nowMs = 0L)
    return buildList {
        filtered.start.photoAssetId?.let { add(it) }
        filtered.landmarks.mapNotNullTo(this) { it.cropAssetId }
    }.distinct()
}
