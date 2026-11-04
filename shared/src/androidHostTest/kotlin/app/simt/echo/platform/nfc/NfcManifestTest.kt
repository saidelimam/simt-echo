package app.simt.echo.platform.nfc

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W20-04 gate for the shipped configuration (plan 0005 §4.4).
 *
 * NFC must be optional in both directions — a phone without the radio or
 * without card emulation still installs and keeps QR and file sharing — and
 * the HCE component must be exactly one proprietary, non-payment AID,
 * exported only behind `BIND_NFC_SERVICE`, with no NFC intent filter
 * anywhere that a tap could dispatch through.
 */
class NfcManifestTest {

    private val root = File(
        System.getProperty("simt.repositoryRoot") ?: System.getProperty("user.dir"),
    )

    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File): Element {
        assertTrue(file.isFile, "missing ${file.path}")
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.elements(tag: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private fun Element.descendants(tag: String): List<Element> {
        val found = mutableListOf<Element>()
        fun walk(element: Element) {
            for (index in 0 until element.childNodes.length) {
                val child = element.childNodes.item(index) as? Element ?: continue
                if (child.tagName == tag) found += child
                walk(child)
            }
        }
        walk(this)
        return found
    }

    private fun Element.android(name: String): String? =
        getAttributeNS(androidNamespace, name).takeIf { it.isNotEmpty() }

    private fun manifest(): Element = parse(File(root, "androidApp/src/main/AndroidManifest.xml"))

    private fun apduService(): Element = parse(File(root, "androidApp/src/main/res/xml/echo_apdu_service.xml"))

    @Test
    fun nfcIsDeclaredOptionalSoQrAndFileStillInstall() {
        val permissions = manifest().elements("uses-permission").mapNotNull { it.android("name") }
        assertTrue("android.permission.NFC" in permissions, "NFC permission missing: $permissions")

        val features = manifest().elements("uses-feature").associate {
            assertNotNull(it.android("name"))
            it.android("name") to it.android("required")
        }
        // Both features remain optional: absence must not filter the app.
        assertEquals("false", features["android.hardware.nfc"])
        assertEquals("false", features["android.hardware.nfc.hce"])
        // The camera precedent is untouched by this slice.
        assertEquals("false", features["android.hardware.camera"])
    }

    @Test
    fun theHostedComponentIsProtectedAndIsNotAPaymentApp() {
        val service = manifest().descendants("service")
            .singleOrNull { it.android("name")?.endsWith("EchoHostApduService") == true }
        assertNotNull(service, "EchoHostApduService is not declared")

        assertEquals("true", service.android("exported"), "the OS must be able to bind it")
        assertEquals(
            "android.permission.BIND_NFC_SERVICE",
            service.android("permission"),
            "only the NFC service may bind this component",
        )

        val actions = service.descendants("action").mapNotNull { it.android("name") }
        assertEquals(listOf("android.nfc.cardemulation.action.HOST_APDU_SERVICE"), actions)

        val metadata = service.descendants("meta-data")
            .singleOrNull { it.android("name") == "android.nfc.cardemulation.host_apdu_service" }
        assertNotNull(metadata)
        assertEquals("@xml/echo_apdu_service", metadata.android("resource"))
    }

    @Test
    fun onlyTheProprietaryAidIsRegisteredOutsidePayments() {
        val apdu = apduService()
        assertEquals("true", apdu.android("requireDeviceUnlock"), "a locked phone must not answer")
        assertTrue(
            apdu.android("description")?.startsWith("@string/") == true,
            "the service needs a platform description",
        )

        val groups = apdu.descendants("aid-group")
        assertEquals(1, groups.size)
        val group = groups.single()
        // Never CATEGORY_PAYMENT: this is not a wallet application.
        assertEquals("other", group.android("category"))

        val aids = group.descendants("aid-filter").map { it.android("name") }
        assertEquals(listOf("F04543484F01"), aids, "exactly one six-byte proprietary AID")
    }

    /**
     * R14-01: a sealed LAN bundle is fetched over plain HTTP, and Android 9+
     * refuses that unless the shipped configuration says so. The permission
     * is cleartext for the peer dial only — every cloud call stays https
     * inside `EchoApiClient`, which this file cannot weaken.
     */
    @Test
    fun lanSharingMayUseCleartextUnderAShippedPolicy() {
        val application = manifest().descendants("application").single()
        assertEquals(
            "@xml/network_security_config",
            application.android("networkSecurityConfig"),
            "the manifest must point at the shipped policy",
        )

        val config = parse(File(root, "androidApp/src/main/res/xml/network_security_config.xml"))
        val base = config.descendants("base-config").singleOrNull()
        assertNotNull(base, "the policy needs a base-config")
        assertEquals(
            "true",
            base.getAttribute("cleartextTrafficPermitted"),
            "the LAN fetch to a peer's hotspot address is plain HTTP",
        )
    }

    @Test
    fun noTapCanLaunchAnAppOrAWallet() {
        val manifest = manifest()
        // No activity declares an NFC intent filter, so a tap cannot
        // dispatch a URI or an application record into ECHO or anything else.
        val activityFilters = manifest.descendants("activity").flatMap { it.descendants("intent-filter") }
        val nfcActions = activityFilters.flatMap { it.descendants("action") }.mapNotNull { it.android("name") }
        assertTrue(
            nfcActions.none { it.startsWith("android.nfc.") },
            "an activity must not be reachable by a tap: $nfcActions",
        )
        // And the only launcher entry stays the ordinary MAIN/LAUNCHER one.
        assertNull(
            activityFilters.flatMap { it.descendants("category") }
                .mapNotNull { it.android("name") }
                .firstOrNull { it == "android.intent.category.DEFAULT" && nfcActions.isNotEmpty() },
        )
    }
}
