package app.simt.echo.data

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Atomic write (W4-03): a torn temp write never truncates the original. */
class FileStoreTest {

    private val fileSystem = FakeFileSystem()
    private val store = FileStore(fileSystem, "/data".toPath())

    @Test
    fun atomicWriteReplacesContent() {
        store.writeText("drafts/d1/capture.json", "old")
        store.writeTextAtomic("drafts/d1/capture.json", "new")
        assertEquals("new", store.readText("drafts/d1/capture.json"))
        assertFalse(store.exists("drafts/d1/capture.json.tmp"))
    }

    @Test
    fun tornTempWriteLeavesPreviousFileIntact() {
        store.writeText("drafts/d1/capture.json", "complete")
        // Simulate a kill mid-autosave: only the temp file got partial data.
        store.writeText("drafts/d1/capture.json.tmp", "{partial")
        assertEquals("complete", store.readText("drafts/d1/capture.json"))
    }
}
