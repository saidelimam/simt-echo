package app.simt.echo.transfer

import app.simt.echo.model.EchoRoute

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
}

const val HOURS_24_MS: Long = 24L * 3_600_000L
const val DAYS_7_MS: Long = 7L * HOURS_24_MS

fun expiryAtEpochMs(option: ExpiryOption, nowMs: Long): Long? = when (option) {
    ExpiryOption.NONE -> null
    ExpiryOption.HOURS_24 -> nowMs + HOURS_24_MS
    ExpiryOption.DAYS_7 -> nowMs + DAYS_7_MS
}

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

/** Default selection: privacy-risk crops OFF, everything else ON. */
fun defaultSelection(route: EchoRoute): ExportSelection =
    defaultSelection(describeExport(route))

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
fun applySelection(route: EchoRoute, selection: ExportSelection, nowMs: Long): EchoRoute =
    route.copy(
        start = route.start.copy(
            photoAssetId = route.start.photoAssetId?.takeIf { selection.includeStartPhoto },
        ),
        landmarks = route.landmarks.map { landmark ->
            if (landmark.cropAssetId != null && landmark.cropAssetId in selection.excludedCropIds) {
                landmark.copy(cropAssetId = null)
            } else {
                landmark
            }
        },
        privacy = route.privacy.copy(expiresAtEpochMs = expiryAtEpochMs(selection.expiry, nowMs)),
    )

/** Asset ids the export path may encode for this selection. */
fun selectedAssetIds(route: EchoRoute, selection: ExportSelection): List<String> {
    val filtered = applySelection(route, selection, nowMs = 0L)
    return buildList {
        filtered.start.photoAssetId?.let { add(it) }
        filtered.landmarks.mapNotNullTo(this) { it.cropAssetId }
    }.distinct()
}
