package app.simt.echo.transfer

import app.simt.echo.data.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R12-09: a stored key whose server record is gone must not lock the install
 * out. The credential store re-mints when the server rejects the key, keeps a
 * key the server accepts, and leaves the key alone when it cannot ask.
 */
class DeviceCredentialTest {

    private val store = FileStore(FakeFileSystem(), "/data".toPath())
    private val stored = "dk_" + "a".repeat(43)
    private val fresh = "dk_" + "b".repeat(43)

    @Test
    fun reMintsAKeyTheServerRejects() = runTest {
        store.writeText("device/credential", stored)
        var mints = 0
        val credentials = FileDeviceCredentials(
            store,
            mint = { mints++; fresh },
            verify = { false },
        )

        assertEquals(fresh, credentials.bearer())
        assertEquals(1, mints)
        assertEquals(fresh, store.readText("device/credential")?.trim())
    }

    @Test
    fun keepsAKeyTheServerAccepts() = runTest {
        store.writeText("device/credential", stored)
        var mints = 0
        val credentials = FileDeviceCredentials(
            store,
            mint = { mints++; fresh },
            verify = { true },
        )

        assertEquals(stored, credentials.bearer())
        assertEquals(0, mints)
    }

    @Test
    fun anUnauthorizedProbeRejectsTheKey() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer $stored", request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.Unauthorized)
        })

        assertFalse(deviceKeyAccepted(http, "https://api.echo.simt.app", stored))
    }

    @Test
    fun aMissingSessionMeansTheKeyIsGood() = runTest {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })

        assertTrue(deviceKeyAccepted(http, "https://api.echo.simt.app", stored))
    }

    @Test
    fun anOfflineProbeLeavesTheKeyAlone() = runTest {
        val http = HttpClient(MockEngine { throw IllegalStateException("offline") })

        assertTrue(deviceKeyAccepted(http, "https://api.echo.simt.app", stored))
    }
}
