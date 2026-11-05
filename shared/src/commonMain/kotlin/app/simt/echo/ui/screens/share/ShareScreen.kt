package app.simt.echo.ui.screens.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.UnavailableNfcPairingController
import app.simt.echo.transfer.FILE_PERCEPTION_OPT_IN_LABEL
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingRole
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.NfcTapPanel
import app.simt.echo.ui.components.PairingLifecycle
import app.simt.echo.ui.components.PairingMethodSelector
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.components.TransferParticles
import app.simt.echo.ui.components.pairingFailureCopy
import app.simt.echo.ui.copy.PairingCopy
import app.simt.echo.ui.sigil.SigilGeometry
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24
import app.simt.echo.ui.theme.EchoSpace8

const val ShareQrTag = "ShareQr"
const val ShareQrImageTag = "ShareQrImage"
const val ShareFilePerceptionTag = "ShareFilePerception"

/**
 * Share screen in Cosmos (plan §8.3 / W2-04, slices W8-09 and W21-03).
 *
 * The method selector comes first, then the surface that method actually
 * uses: the tap panel for NFC, the high-contrast QR for QR. Both encode the
 * same sealed invitation, so switching never re-seals what is on offer.
 * Share opens in NFC (plan 0005 §1); a phone that cannot present shows the
 * QR it can still use, with the recovery that says why.
 *
 * "What others receive" and every export/perception control stay directly
 * below the pairing surface. "Share as file" (W2-07) raises the plain-file
 * privacy warning first and only writes the `.echo` container after
 * confirmation. Serving stops when the screen leaves.
 *
 * Presentation only: offer/selection/relay/file ViewModel behaviour is
 * owned by [ShareViewModel]; this screen renders it.
 */
@Composable
fun ShareScreen(
    routeId: String,
    service: TransferService,
    haptics: Haptics,
    sharing: FileSharing,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The route's sigil for the QR centre and the tap panel (W13-07). */
    sigil: SigilGeometry? = null,
    /** Route-scoped NFC controller; null keeps the honest unavailable one. */
    pairing: NfcPairingController? = null,
    /** Opens the system NFC settings screen (never toggles the adapter). */
    onOpenNfcSettings: () -> Unit = {},
) {
    val controller = pairing ?: remember { UnavailableNfcPairingController() }
    val viewModel = remember(routeId, sharing, controller) {
        ShareViewModel(routeId, service, haptics, sharing, pairing = controller)
    }
    val ui by viewModel.ui.collectAsState()
    // NFC only runs while this screen is resumed; disposal ends the visit
    // so a late callback cannot republish a replaced offer.
    PairingLifecycle(
        onResume = viewModel::onResumed,
        onPause = viewModel::onPaused,
        onDispose = viewModel::close,
    )

    val qrUri = ui.qrUri
    val nfcUnavailable = ui.nfcReadiness == NfcReadiness.UNAVAILABLE
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PairingMethodSelector(
            role = PairingRole.SHARE,
            selected = ui.method,
            enabled = true,
            onSelect = viewModel::selectMethod,
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui.method == PairingMethod.NFC) {
            NfcTapPanel(
                role = PairingRole.SHARE,
                readiness = ui.nfcReadiness,
                listening = ui.readyToTap,
                tapRead = ui.tapRead,
                failure = ui.tapFailure,
                onOpenNfcSettings = onOpenNfcSettings,
                onUseQrInstead = { viewModel.selectMethod(PairingMethod.QR) },
                sigil = sigil,
                modifier = Modifier.fillMaxWidth(),
                // A phone that cannot present gets the short form: the code
                // it can serve stays visible instead of sitting under a
                // panel for a tap that could never happen.
                compact = nfcUnavailable,
            )
        }
        // QR is the sibling surface in QR mode, and the honest fallback on a
        // phone whose NFC cannot present at all.
        if (qrUri != null && (ui.method == PairingMethod.QR || nfcUnavailable)) {
            ShareQrCode(
                qrUri = qrUri,
                listening = !ui.sent && ui.error == null,
                sent = ui.sent,
                sigil = sigil,
                modifier = Modifier.testTag(ShareQrTag),
            )
        }
        ShareExportSection(
            items = ui.exportItems,
            selection = ui.selection,
            summary = ui.summary,
            onToggle = viewModel::toggleAsset,
            onExpiry = viewModel::setExpiry,
            modifier = Modifier.fillMaxWidth(),
        )
        val error = ui.error
        // The ten-minute window ran out: the offer is gone, not "starting".
        val expired = qrUri == null && ui.tapFailure == PairingFailure.OfferExpired
        when {
            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            ui.sent -> StatusChip(text = PairingCopy.SHARE_DOWNLOADED)
            expired -> StatusChip(
                text = pairingFailureCopy(PairingRole.SHARE, PairingFailure.OfferExpired),
            )

            qrUri != null -> StatusChip(text = PairingCopy.SHARE_WAITING)
            else -> StatusChip(text = PairingCopy.SHARE_STARTING)
        }
        // W13-14: particle flow between the two phones, driven only by
        // measured bytes — frozen until the first byte moves.
        val progress = ui.progress
        if (progress != null) {
            TransferParticles(
                progress = progress,
                bytesPerSecond = ui.bytesPerSecond,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // A completed download consumed this invitation: another receiver
        // needs a freshly sealed one (R14-07).
        if (ui.sent) {
            CosmosPrimaryButton(
                label = PairingCopy.SHARE_AGAIN,
                onClick = viewModel::shareAgain,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if ((error != null || expired) && qrUri == null) {
            CosmosPrimaryButton(
                label = PairingCopy.SHARE_TRY_AGAIN,
                onClick = viewModel::retryOffer,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = PairingCopy.SAME_NETWORK + " " + PairingCopy.MATCHING_MODE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Relay is a network option, not a third pairing tab, and it only
        // appears when this backend can actually carry a peer's route.
        if (ui.relayAvailable && !ui.relayOffered && qrUri != null) {
            CosmosPrimaryButton(
                label = "Use internet relay",
                onClick = viewModel::useInternetRelay,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.relayOffered) {
            StatusChip(text = "Relay code in QR — works off this Wi-Fi")
        }
        if (ui.fileWarning) {
            CanopyPanel(
                variant = CanopyVariant.Floating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(EchoSpace16),
                    verticalArrangement = Arrangement.spacedBy(EchoSpace8),
                ) {
                    Text(
                        text = "Share as file?",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = FILE_WARNING_COPY,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (ui.summary?.hasPerception == true) {
                        FilePerceptionOptIn(
                            include = ui.filePerception,
                            onChange = viewModel::setFilePerception,
                        )
                    }
                    CosmosPrimaryButton(
                        label = "Share anyway",
                        onClick = viewModel::confirmFileShare,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CosmosSecondaryButton(
                        label = "Cancel",
                        onClick = viewModel::dismissFileShare,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (ui.fileShared) {
            StatusChip(text = "File shared ✓")
        }
        // The trigger hides while the warning card is open: re-firing it
        // would just re-raise the card that is already on screen.
        if (!ui.fileShared && !ui.fileWarning) {
            CosmosSecondaryButton(
                label = PairingCopy.SHARE_AS_FILE,
                onClick = viewModel::requestFileShare,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Opt-in row inside the plain-file warning: perception data stays out of
 * an unencrypted file unless the user turns this on. The whole row toggles
 * (switch role, 48 dp minimum) so the label is the control's name.
 */
@Composable
private fun FilePerceptionOptIn(
    include: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = include, role = Role.Switch, onValueChange = onChange)
            .testTag(ShareFilePerceptionTag),
        horizontalArrangement = Arrangement.spacedBy(EchoSpace8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = FILE_PERCEPTION_OPT_IN_LABEL,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = include, onCheckedChange = null)
    }
}
