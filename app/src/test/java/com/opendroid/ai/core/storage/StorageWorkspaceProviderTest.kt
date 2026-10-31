package com.opendroid.ai.core.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.AdvancedControlActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageWorkspaceProviderTest {

    private lateinit var context: Context

    private fun workspace(): File = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StorageWorkspaceProvider.clearCustomFolder(context)
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        ws.deleteRecursively()
        ws.mkdirs()
    }

    @Test
    fun `default workspace is always accessible without permissions`() {
        assertTrue(StorageWorkspaceProvider.hasStorageAccess(context))
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(ws.exists())
        assertTrue(ws.isDirectory)
    }

    @Test
    fun `custom folder uri can be saved and cleared`() {
        assertFalse(StorageWorkspaceProvider.hasCustomFolder(context))
        assertNull(StorageWorkspaceProvider.getCustomFolderUri(context))

        val fakeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        StorageWorkspaceProvider.setCustomFolderUri(context, fakeUri)

        assertTrue(StorageWorkspaceProvider.hasCustomFolder(context))
        assertEquals(fakeUri, StorageWorkspaceProvider.getCustomFolderUri(context))

        StorageWorkspaceProvider.clearCustomFolder(context)
        assertFalse(StorageWorkspaceProvider.hasCustomFolder(context))
        assertNull(StorageWorkspaceProvider.getCustomFolderUri(context))
    }

    @Test
    fun `resolveFile resolves relative paths inside workspace`() {
        val file = StorageWorkspaceProvider.resolveFile(context, "my_folder/notes.txt")
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(file.canonicalPath.startsWith(ws.canonicalPath))
        assertEquals("notes.txt", file.name)
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects path traversal`() {
        StorageWorkspaceProvider.resolveFile(context, "../../etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects forbidden system directories`() {
        StorageWorkspaceProvider.resolveFile(context, "/data/system/users")
    }

    @Test
    fun `resolveFile maps inaccessible sdcard path into workspace`() {
        val file = StorageWorkspaceProvider.resolveFile(context, "/sdcard/OpenDroid/document.pdf")
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(file.canonicalPath.startsWith(ws.canonicalPath))
        assertTrue(file.canonicalPath.endsWith("OpenDroid/document.pdf"))
    }

    @Test
    fun `app workspace file operations execute successfully without permissions`() = runBlocking {
        // 1. Create directory
        val createDirResult = StorageWorkspaceProvider.createDirectory(context, "subfolder")
        assertTrue(createDirResult.success)

        // 2. Write file
        val writeResult = StorageWorkspaceProvider.writeFile(context, "subfolder/hello.txt", "Hello OpenDroid!")
        assertTrue(writeResult.success)

        // 3. Read file
        val readResult = StorageWorkspaceProvider.readFile(context, "subfolder/hello.txt")
        assertTrue(readResult.success)
        assertEquals("Hello OpenDroid!", readResult.data)

        // 4. List files
        val listResult = StorageWorkspaceProvider.listFiles(context, "subfolder")
        assertTrue(listResult.success)
        assertTrue(listResult.data?.contains("hello.txt") == true)

        // 5. Copy file
        val copyResult = StorageWorkspaceProvider.copyFile(context, "subfolder/hello.txt", "subfolder/hello_copy.txt")
        assertTrue(copyResult.success)
        val readCopy = StorageWorkspaceProvider.readFile(context, "subfolder/hello_copy.txt")
        assertEquals("Hello OpenDroid!", readCopy.data)

        // 6. Move file
        val moveResult = StorageWorkspaceProvider.moveFile(context, "subfolder/hello_copy.txt", "subfolder/hello_moved.txt")
        assertTrue(moveResult.success)
        val movedCheck = StorageWorkspaceProvider.readFile(context, "subfolder/hello_moved.txt")
        assertEquals("Hello OpenDroid!", movedCheck.data)
        assertFalse(StorageWorkspaceProvider.readFile(context, "subfolder/hello_copy.txt").success)

        // 7. Zip and Unzip
        val zipResult = StorageWorkspaceProvider.zipFiles(context, "subfolder", "backup.zip")
        assertTrue(zipResult.success)
        val unzipResult = StorageWorkspaceProvider.unzipFile(context, "backup.zip", "unzipped")
        assertTrue(unzipResult.success)
        val unzippedContent = StorageWorkspaceProvider.readFile(context, "unzipped/subfolder/hello.txt")
        assertTrue(unzippedContent.success)
        assertEquals("Hello OpenDroid!", unzippedContent.data)

        // 8. Delete file
        val deleteResult = StorageWorkspaceProvider.deleteFile(context, "subfolder/hello.txt")
        assertTrue(deleteResult.success)
        assertFalse(StorageWorkspaceProvider.readFile(context, "subfolder/hello.txt").success)
    }

    @Test
    fun `moving a folder into its own subfolder fails and keeps the folder`() {
        // renameTo refuses, and the copy fallback used to recurse into the new subfolder,
        // then delete the source - destination included - and report success (#89).
        StorageWorkspaceProvider.writeFile(context, "notes/a.txt", "keep")

        val result = StorageWorkspaceProvider.moveFile(context, "notes", "notes/backup")

        assertFalse(result.success)
        assertEquals("keep", StorageWorkspaceProvider.readFile(context, "notes/a.txt").data)
        assertFalse(File(workspace(), "notes/backup").exists())
    }

    @Test
    fun `copying a folder into its own subfolder fails without nesting copies`() {
        StorageWorkspaceProvider.writeFile(context, "notes/a.txt", "keep")

        val result = StorageWorkspaceProvider.copyFile(context, "notes", "notes/copy")

        assertFalse(result.success)
        assertFalse(File(workspace(), "notes/copy").exists())
        assertEquals("keep", StorageWorkspaceProvider.readFile(context, "notes/a.txt").data)
    }

    @Test
    fun `copying a file onto itself fails and keeps its content`() {
        // Both paths resolve to workspace/Download/report.txt; the copy used to
        // truncate the file before reading it (#89).
        StorageWorkspaceProvider.writeFile(context, "Download/report.txt", "important")

        val result = StorageWorkspaceProvider.copyFile(context, "/sdcard/Download/report.txt", "Download/report.txt")

        assertFalse(result.success)
        assertEquals("important", StorageWorkspaceProvider.readFile(context, "Download/report.txt").data)
    }

    @Test(timeout = 20_000)
    fun `zipping a folder into a zip inside it fails instead of reading its own output`() {
        // On the unfixed code this grows until storage is full, so it is only added
        // alongside the fix (#89).
        StorageWorkspaceProvider.writeFile(context, "p/a.txt", "x".repeat(50_000))

        val result = StorageWorkspaceProvider.zipFiles(context, "p", "p/p.zip")

        assertFalse(result.success)
        assertFalse(File(workspace(), "p/p.zip").exists())
    }

    @Test
    fun `moving and copying to a sibling still work`() {
        StorageWorkspaceProvider.writeFile(context, "notes/a.txt", "keep")

        assertTrue(StorageWorkspaceProvider.copyFile(context, "notes", "notes-copy").success)
        assertTrue(StorageWorkspaceProvider.moveFile(context, "notes", "notes2").success)

        assertEquals("keep", StorageWorkspaceProvider.readFile(context, "notes-copy/a.txt").data)
        assertEquals("keep", StorageWorkspaceProvider.readFile(context, "notes2/a.txt").data)
        assertFalse(File(workspace(), "notes").exists())
    }

    @Test
    fun `advanced control actions file operations execute seamlessly`() = runBlocking {
        val actions = AdvancedControlActions()
        val actionsMap = actions.getActions().associateBy { it.name }

        // Write
        val writeAction = actionsMap["WRITE_FILE"]!!
        val writeRes = writeAction.execute(
            mapOf("filePath" to "agent_log.txt", "content" to "Task completed successfully."),
            context
        )
        assertTrue(writeRes.success)

        // Read
        val readAction = actionsMap["READ_FILE"]!!
        val readRes = readAction.execute(
            mapOf("filePath" to "agent_log.txt"),
            context
        )
        assertTrue(readRes.success)
        assertEquals("Task completed successfully.", readRes.data)

        // List
        val listAction = actionsMap["LIST_FILES"]!!
        val listRes = listAction.execute(emptyMap(), context)
        assertTrue(listRes.success)
        assertTrue(listRes.data?.contains("agent_log.txt") == true)

        // Delete
        val deleteAction = actionsMap["DELETE_FILE"]!!
        val deleteRes = deleteAction.execute(
            mapOf("filePath" to "agent_log.txt"),
            context
        )
        assertTrue(deleteRes.success)
    }
}
