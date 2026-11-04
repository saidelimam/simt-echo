package app.simt.echo.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * Review 0013 F7 (R13-07): a sheet scrolls its own content at large text,
 * and a field forwards the caller's keyboard contract instead of always
 * opening the generic text keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    qualifiers = "w411dp-h891dp",
)
class ComponentsErgonomicsTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun sheetScrollsToItsLastFieldAtLargeText() {
        val big = Density(compose.density.density, fontScale = 2.0f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
                EchoTheme(darkTheme = true) {
                    CosmosSheet(
                        title = "Name this walk",
                        onDismiss = {},
                        primaryLabel = "Continue",
                        onPrimary = {},
                    ) {
                        repeat(14) { index -> Text(text = "Field $index") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Field 13").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun textFieldForwardsTheImeAction() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosTextField(
                    label = "Measured metres",
                    value = "10",
                    onValueChange = {},
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("$CosmosFieldTag:Measured metres").fetchSemanticsNode()
        assertEquals(ImeAction.Done, node.config[SemanticsProperties.ImeAction])
    }
}
