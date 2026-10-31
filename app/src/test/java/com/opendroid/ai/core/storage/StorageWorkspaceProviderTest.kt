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
    fun `an absolute path into the app's model storage cannot be deleted`() {
        // Downloaded models live under getExternalFilesDir(null)/models; an LLM plan
        // could delete them by absolute path (#90).
        val models = File(context.getExternalFilesDir(null), "models/m1").apply { mkdirs() }
        File(models, "weights.bin").writeText("x")

        StorageWorkspaceProvider.deleteFile(context, models.parentFile!!.absolutePath)

        assertTrue(File(models, "weights.bin").exists())
    }

    @Test
    fun `an absolute path into the app's private files cannot be overwritten`() {
        val settings = File(context.filesDir, "datastore/settings.preferences_pb").apply {
            parentFile!!.mkdirs()
            writeText("original")
        }

        StorageWorkspaceProvider.writeFile(context, settings.absolutePath, "overwritten")
        StorageWorkspaceProvider.deleteFile(context, settings.absolutePath)

        assertEquals("original", settings.readText())
    }

    @Test
    fun `root-like paths never delete the whole workspace`() {
        StorageWorkspaceProvider.writeFile(context, "keep.txt", "x")

        for (path in listOf("", ".", "./", "/", "/sdcard", "/sdcard/", "/storage/emulated/0")) {
            assertFalse("deleteFile(\"$path\")", StorageWorkspaceProvider.deleteFile(context, path).success)
        }

        assertEquals("x", StorageWorkspaceProvider.readFile(context, "keep.txt").data)
    }

    @Test(expected = SecurityException::class)
    fun `a sibling folder that only shares the workspace's name prefix is rejected`() {
        StorageWorkspaceProvider.resolveFile(context, "../workspace-other/notes.txt")
    }

    @Test
    fun `absolute paths inside the workspace and to the agent's photos still resolve`() {
        val inWorkspace = File(workspace(), "docs/a.txt")
        assertEquals(inWorkspace.canonicalPath, StorageWorkspaceProvider.resolveFile(context, inWorkspace.absolutePath).path)

        val photo = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "background_photo_1.jpg")
        assertEquals(photo.canonicalPath, StorageWorkspaceProvider.resolveFile(context, photo.absolutePath).path)
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
