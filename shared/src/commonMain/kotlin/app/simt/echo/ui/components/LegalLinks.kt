package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The two documents Google Play, the App Store and GDPR Art. 13 all require to
 * be reachable where data is collected (review 0012, R12-09).
 *
 * One definition, three surfaces: the Settings privacy section and both
 * consent sheets. A consent sheet that describes an upload without a way to
 * read the notice is the gap this closes.
 */
// The documents themselves, not the company site: simt.app carries the SIMT
// brand, not ECHO's privacy notice or terms, so linking there sent users to
// pages that never state this app's policy.
internal const val PrivacyPolicyUrl =
    "https://github.com/saidelimam/simt-echo/blob/main/docs/legal/privacy-policy.md"
internal const val TermsOfUseUrl =
    "https://github.com/saidelimam/simt-echo/blob/main/docs/legal/terms-of-use.md"

/** Test seam: the row, and each link inside it. */
internal const val LegalLinksTag = "LegalLinks"
internal const val PrivacyLinkTag = "PrivacyLink"
internal const val TermsLinkTag = "TermsLink"

/**
 * Two links plus the sentence that says why they are here. [onOpen] is the
 * test seam; production passes null and the platform URI handler opens them.
 */
@Composable
internal fun LegalLinks(
    modifier: Modifier = Modifier,
    onOpen: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier.testTag(LegalLinksTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Read the Privacy Policy and Terms of Use before you continue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The design system's text-only command, not a raw Material button:
            // it carries the touch-target floor and the Cosmos focus ring.
            CosmosTertiaryButton(
                label = "Privacy Policy",
                onClick = {
                    if (onOpen != null) onOpen(PrivacyPolicyUrl) else uriHandler.openUri(PrivacyPolicyUrl)
                },
                modifier = Modifier.testTag(PrivacyLinkTag),
            )
            CosmosTertiaryButton(
                label = "Terms of Use",
                onClick = {
                    if (onOpen != null) onOpen(TermsOfUseUrl) else uriHandler.openUri(TermsOfUseUrl)
                },
                modifier = Modifier.testTag(TermsLinkTag),
            )
        }
    }
}
