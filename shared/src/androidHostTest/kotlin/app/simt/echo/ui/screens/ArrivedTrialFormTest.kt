package app.simt.echo.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import app.simt.echo.ui.components.CosmosFieldTag
import app.simt.echo.ui.screens.arrived.ArrivedScreen
import app.simt.echo.ui.theme.EchoTheme
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * Review 0013 F8 (R13-08): the §11 field-trial form uses CosmosTextField —
 * visible labels, clear buttons, focus rings — and the mistaken-count field
 * asks for a number keypad and a Next action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    qualifiers = "w411dp-h891dp",
)
class ArrivedTrialFormTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun fieldTrialFormUsesCosmosFields() {
        val dir: Path = Files.createTempDirectory("r13-08-arrived").toString().toPath()
        val routes = RouteRepository(FileStore(FileSystem.SYSTEM, dir))
        val trials = TrialRepository(FileStore(FileSystem.SYSTEM, dir))
        routes.save(Fixtures.L_ROUTE)
        trials.save(
            Trial(
                trialId = "trial-1",
                routeId = Fixtures.L_ROUTE.routeId,
                mode = TrialMode.entries.first(),
                device = "host-test",
                startedAtEpochMs = 1L,
                durationS = 60.0,
                distanceM = 10.0,
                arrived = true,
            ),
        )
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ArrivedScreen(
                    routeId = Fixtures.L_ROUTE.routeId,
                    routes = routes,
                    onDone = {},
                    trials = trials,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Field trial details").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("$CosmosFieldTag:Lighting").assertIsDisplayed()
        compose.onNodeWithTag("$CosmosFieldTag:Holding style").assertIsDisplayed()
        compose.onNodeWithTag("$CosmosFieldTag:Notes").assertIsDisplayed()
        val mistaken = compose.onNodeWithTag("$CosmosFieldTag:Mistaken confirmations")
            .fetchSemanticsNode()
        assertEquals(ImeAction.Next, mistaken.config[SemanticsProperties.ImeAction])
    }
}
