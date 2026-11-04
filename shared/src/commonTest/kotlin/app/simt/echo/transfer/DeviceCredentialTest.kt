package app.simt.echo.transfer

import app.simt.echo.data.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R12-02 (review 0012, F2) gate: the client authenticates with a per-install
 * device key that `POST /v1/device` minted once and that survives a process
 * restart — never with a compiled-in shared token.
 */
class DeviceCredentialTest {

    private fun fileStore() = FileStore(FakeFileSystem(), "/data".toPath())

    private fun key(seed: Char) = "dk_" + seed.toString().repeat(43)

    @Test
    fun mintsOnceAndPersistsAcrossInstances() = runTest {
        val store = fileStore()
        var minted = 0
        val credentials = FileDeviceCredentials(store) {
            minted++
            key('a')
        }

        assertEquals(key('a'), credentials.bearer())
        assertEquals(key('a'), credentials.bearer())
        assertEquals(1, minted, "a live process reuses the same key")

        // A new graph after process death reads the persisted key instead of
        // minting another device record.
        var reminted = 0
        val restarted = FileDeviceCredentials(store) {
            reminted++
            key('b')
        }
        assertEquals(key('a'), restarted.bearer())
        assertEquals(0, reminted)
    }

    @Test
    fun concurrentFirstUseMintsExactlyOnce() = runTest {
        val store = fileStore()
        var minted = 0
        val credentials = FileDeviceCredentials(store) {
            minted++
            key('c')
        }
        val keys = (1..8).map { async { credentials.bearer() } }.awaitAll()
        assertEquals(List(8) { key('c') }, keys)
        assertEquals(1, minted)
    }

    @Test
    fun aCorruptStoredKeyIsReplacedRatherThanSent() = runTest {
        val store = fileStore()
        store.writeText("device/credential", "not-a-device-key")
        var minted = 0
        val credentials = FileDeviceCredentials(store) {
            minted++
            key('d')
        }
        assertEquals(key('d'), credentials.bearer())
        assertEquals(1, minted)
        assertEquals(key('d'), store.readText("device/credential"))
    }

    @Test
    fun rotateReplacesThePersistedKey() = runTest {
        val store = fileStore()
        val keys = ArrayDeque(listOf(key('e'), key('f')))
        val credentials = FileDeviceCredentials(store) { keys.removeFirst() }
        assertEquals(key('e'), credentials.bearer())
        assertEquals(key('f'), credentials.rotate())
        assertEquals(key('f'), credentials.bearer())
        assertEquals(key('f'), store.readText("device/credential"))
    }

    @Test
    fun forgetDeletesThePersistedKeySoTheNextUseMints() = runTest {
        val store = fileStore()
        val keys = ArrayDeque(listOf(key('g'), key('h')))
        val credentials = FileDeviceCredentials(store) { keys.removeFirst() }
        assertEquals(key('g'), credentials.bearer())
        credentials.forget()
        assertFalse(store.exists("device/credential"))
        assertEquals(key('h'), credentials.bearer())
    }

    @Test
    fun aMalformedMintIsRejectedAndNotPersisted() = runTest {
        val store = fileStore()
        val credentials = FileDeviceCredentials(store) { "tok" }
        assertFailsWith<EchoApiError.Server> { credentials.bearer() }
        assertFalse(store.exists("device/credential"))
    }

    @Test
    fun mintPostsToTheDeviceRouteAndReturnsTheKey() = runTest {
        var method: HttpMethod? = null
        var path = ""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    method = request.method
                    path = request.url.encodedPath
                    assertEquals(null, request.headers[HttpHeaders.Authorization])
                    respond(
                        """{"ok":true,"device":"${key('i')}"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        assertEquals(key('i'), mintDeviceKey(http, "https://echo.test/"))
        assertEquals(HttpMethod.Post, method)
        assertEquals("/v1/device", path)
    }

    @Test
    fun mintRejectsAMalformedBody() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        """{"ok":true,"device":"nope"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        assertFailsWith<EchoApiError.Server> { mintDeviceKey(http, "https://echo.test") }
        assertFailsWith<EchoApiError.NotConfigured> { mintDeviceKey(http, "") }
    }

    @Test
    fun everyApiCallSendsTheCurrentPersistedBearer() = runTest {
        val store = fileStore()
        val keys = ArrayDeque(listOf(key('j'), key('m')))
        val credentials = FileDeviceCredentials(store) { keys.removeFirst() }
        val seen = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    seen += request.headers[HttpHeaders.Authorization] ?: ""
                    respond(
                        """{"ok":true,"status":"running","stage":"scouting"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val api = EchoApiClient(http, "https://echo.test", credentials::bearer)
        api.get("job-1")
        assertEquals(listOf("Bearer ${key('j')}"), seen)

        // After a rotation the very next request carries the new key.
        credentials.rotate()
        api.get("job-1")
        assertEquals("Bearer ${key('m')}", seen.last())
    }

    @Test
    fun relayCallsSendTheCurrentPersistedBearer() = runTest {
        val store = fileStore()
        val credentials = FileDeviceCredentials(store) { key('k') }
        val seen = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    seen += request.headers[HttpHeaders.Authorization] ?: ""
                    respond(
                        """{"code":"ABCDEFGHJ2","expiresAt":1}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        KtorRelayClient(http, "https://echo.test", credentials::bearer).upload(byteArrayOf(1))
        assertEquals(listOf("Bearer ${key('k')}"), seen)
        assertTrue(seen.single().startsWith("Bearer dk_"))
    }
}
