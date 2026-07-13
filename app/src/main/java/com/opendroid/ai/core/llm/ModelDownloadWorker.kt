package com.opendroid.ai.core.llm

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.ModelStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "ModelDownloadWorker"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun modelDao(): ModelDao
        fun okHttpClient(): OkHttpClient
    }

    private val entryPoint = EntryPointAccessors.fromApplication(
        applicationContext,
        WorkerEntryPoint::class.java
    )

    private val modelDao = entryPoint.modelDao()
    private val okHttpClient = entryPoint.okHttpClient()

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id") ?: return Result.failure()
        val downloadUrl = inputData.getString("download_url") ?: return Result.failure()
        val targetPath = inputData.getString("target_path") ?: return Result.failure()
        val size = inputData.getLong("size", 0L)
        val expectedSha = inputData.getString("sha256") ?: ""
        val simulate = inputData.getBoolean("simulate", false)

        Log.i(tag, "Starting download task for model: $modelId, url=$downloadUrl, size=$size, simulate=$simulate")

        try {
            if (simulate) {
                return performSimulation(modelId, targetPath, size)
            }

            return performDownload(modelId, downloadUrl, targetPath, size, expectedSha)
        } catch (e: Exception) {
            Log.e(tag, "Failed to download model $modelId", e)
            modelDao.updateModelStatus(modelId, ModelStatus.FAILED)
            return Result.failure()
        }
    }

    private suspend fun performSimulation(modelId: String, targetPath: String, totalSize: Long): Result {
        val steps = 20
        val sleepTime = 500L // 0.5s per step, 10s total
        val baseSpeed = 25 * 1024 * 1024L // 25 MB/s

        // Find existing progress to support resume
        val existingEntity = modelDao.getModelById(modelId)
        val startProgress = if (existingEntity?.status == ModelStatus.PAUSED) existingEntity.downloadProgress else 0
        val startBytes = (startProgress * totalSize) / 100

        modelDao.updateModelStatus(modelId, ModelStatus.DOWNLOADING)

        for (i in (startProgress / 5)..steps) {
            if (isStopped) {
                modelDao.updateModelStatus(modelId, ModelStatus.PAUSED)
                return Result.retry()
            }

            val progress = (i * 100) / steps
            val currentDownloaded = (progress * totalSize) / 100
            val speedBytesPerSec = baseSpeed + (-5 * 1024 * 1024..5 * 1024 * 1024).random()
            val speedString = formatSpeed(speedBytesPerSec)
            val etaSeconds = if (speedBytesPerSec > 0) (totalSize - currentDownloaded) / speedBytesPerSec else 0L
            val etaString = formatEta(etaSeconds)

            modelDao.updateDownloadProgressDetails(
                modelId,
                progress,
                currentDownloaded,
                speedString,
                etaString,
                ModelStatus.DOWNLOADING
            )

            kotlinx.coroutines.delay(sleepTime)
        }

        // Create mock files
        val targetDir = File(targetPath)
        if (!targetDir.exists()) targetDir.mkdirs()

        // Create mock manifest.json
        val manifestFile = File(targetDir, "manifest.json")
        val manifest = JSONObject().apply {
            put("model_id", modelId)
            put("status", "ready")
            put("format", "litertlm")
        }
        manifestFile.writeText(manifest.toString())

        // Create mock config.json
        val configFile = File(targetDir, "config.json")
        configFile.writeText("{\"model_type\": \"gemma\"}")

        // Create mock tokenizer.model
        val tokenizerFile = File(targetDir, "tokenizer.model")
        tokenizerFile.writeText("mock_tokenizer_data")

        // Create mock model.task (or model.litertlm)
        val taskFile = File(targetDir, "model.task")
        taskFile.writeText("mock_model_task_binary_data")

        // Let's also create the model file that LiteRTLMProvider checks
        // In LiteRTLMProvider.kt, getModelFilePath returns: files/litert_models/{modelId}.litertlm
        // Let's create it as a reference file pointing to targetDir/model.task
        val refFile = File(applicationContext.filesDir, "litert_models/${modelId}.litertlm")
        refFile.parentFile?.mkdirs()
        refFile.writeText(targetDir.absolutePath)

        modelDao.updateDownloadProgressDetails(
            modelId,
            100,
            totalSize,
            "",
            "",
            ModelStatus.READY
        )

        return Result.success()
    }

    private suspend fun performDownload(
        modelId: String,
        downloadUrl: String,
        targetPath: String,
        expectedSize: Long,
        expectedSha: String
    ): Result {
        val tempFile = File(applicationContext.cacheDir, "$modelId.tmp")
        val isResume = tempFile.exists() && tempFile.length() > 0
        val startBytes = if (isResume) tempFile.length() else 0L

        Log.i(tag, "[DOWNLOAD FLOW] Requesting HTTP Download from model URL: $downloadUrl")
        Log.i(tag, "[DOWNLOAD FLOW] Resume status: $isResume, starting from $startBytes bytes")

        val requestBuilder = Request.Builder().url(downloadUrl)
        if (isResume) {
            requestBuilder.header("Range", "bytes=$startBytes-")
        }
        val request = requestBuilder.build()

        modelDao.updateModelStatus(modelId, ModelStatus.DOWNLOADING)

        val response = okHttpClient.newCall(request).execute()
        Log.i(tag, "[DOWNLOAD FLOW] Received Response. Code: ${response.code}, Msg: ${response.message}")
        if (!response.isSuccessful && response.code != 206) {
            Log.e(tag, "[FAILURE] HTTP request failed with code ${response.code}")
            response.close()
            modelDao.updateModelStatus(modelId, ModelStatus.FAILED)
            return Result.failure()
        }

        val append = isResume && response.code == 206
        val responseBody = response.body ?: throw Exception("Response body is null")
        val totalBytes = (if (append) startBytes else 0L) + responseBody.contentLength()
        Log.i(tag, "[DOWNLOAD FLOW] Content-Length of this response chunk: ${responseBody.contentLength()} bytes. Total expected model file size: $totalBytes bytes")

        val outputStream = FileOutputStream(tempFile, append)
        val inputStream: InputStream = responseBody.byteStream()
        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        var totalRead = if (append) startBytes else 0L
        var lastUpdate = System.currentTimeMillis()
        var bytesInLastSecond = 0L

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isStopped) {
                    outputStream.close()
                    responseBody.close()
                    Log.i(tag, "[DOWNLOAD FLOW] Worker stopped. Saving progress at $totalRead bytes.")
                    modelDao.updateModelStatus(modelId, ModelStatus.PAUSED)
                    return Result.retry()
                }

                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                bytesInLastSecond += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 1000) {
                    val progress = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                    val speedBytesPerSec = bytesInLastSecond * 1000 / (now - lastUpdate)
                    val speedString = formatSpeed(speedBytesPerSec)
                    val etaSeconds = if (speedBytesPerSec > 0) (totalBytes - totalRead) / speedBytesPerSec else 0L
                    val etaString = formatEta(etaSeconds)

                    Log.i(tag, "[DOWNLOAD FLOW] Progress Update: downloaded $totalRead/$totalBytes bytes ($progress%). Speed: $speedString. ETA: $etaString")

                    modelDao.updateDownloadProgressDetails(
                        modelId,
                        progress,
                        totalRead,
                        speedString,
                        etaString,
                        ModelStatus.DOWNLOADING
                    )

                    lastUpdate = now
                    bytesInLastSecond = 0
                }
            }
            outputStream.flush()
        } finally {
            outputStream.close()
            responseBody.close()
        }

        if (expectedSha.isNotEmpty()) {
            Log.i(tag, "[DOWNLOAD FLOW] Verifying SHA-256 checksum...")
            val actualSha = calculateSha256(tempFile)
            if (actualSha.lowercase() != expectedSha.lowercase()) {
                Log.e(tag, "[FAILURE] SHA-256 verification failed for $modelId. Expected $expectedSha, got $actualSha")
                tempFile.delete()
                modelDao.updateModelStatus(modelId, ModelStatus.FAILED)
                return Result.failure()
            }
            Log.i(tag, "[DOWNLOAD FLOW] SHA-256 checksum verified successfully.")
        }

        // Extract files
        val targetDir = File(targetPath)
        if (!targetDir.exists()) targetDir.mkdirs()

        Log.i(tag, "[DOWNLOAD FLOW] Extracting model payload to target directory: $targetPath")
        try {
            if (downloadUrl.endsWith(".zip") || isZipFile(tempFile)) {
                extractZip(tempFile, targetDir)
            } else {
                // If it's a single file (like model.task), copy it to targetDir/model.task
                val modelTaskFile = File(targetDir, "model.task")
                tempFile.copyTo(modelTaskFile, overwrite = true)
                
                // Write a dummy manifest.json and config.json
                val manifestFile = File(targetDir, "manifest.json")
                val manifest = JSONObject().apply {
                    put("model_id", modelId)
                    put("status", "ready")
                    put("format", "litertlm")
                }
                manifestFile.writeText(manifest.toString())
            }
        } catch (e: Exception) {
            Log.e(tag, "[FAILURE] Extraction failed", e)
            modelDao.updateModelStatus(modelId, ModelStatus.FAILED)
            return Result.failure()
        }

        // Verify final file size and existence
        val finalModelFile = File(targetDir, "model.task")
        if (!finalModelFile.exists()) {
            Log.e(tag, "[FAILURE] Download failed: final model.task file does not exist after copy/extraction at ${finalModelFile.absolutePath}")
            modelDao.updateModelStatus(modelId, ModelStatus.FAILED)
            return Result.failure()
        }
        val finalSize = finalModelFile.length()
        Log.i(tag, "[DOWNLOAD FLOW] Final model file verified. Path: ${finalModelFile.absolutePath}, Size: $finalSize bytes")
        if (finalSize <= 0) {
            Log.e(tag, "[FAILURE] Download failed: final model file is empty (0 bytes)")
            modelDao.updateModelStatus(modelId, ModelStatus.FAILED)
            return Result.failure()
        }

        // Create references for LiteRTLMProvider
        val refFile = File(applicationContext.filesDir, "litert_models/${modelId}.litertlm")
        refFile.parentFile?.mkdirs()
        refFile.writeText(targetDir.absolutePath)

        // Mark ready
        modelDao.updateDownloadProgressDetails(
            modelId,
            100,
            totalBytes,
            "",
            "",
            ModelStatus.READY
        )

        tempFile.delete()
        Log.i(tag, "[DOWNLOAD FLOW] Model task completed successfully.")
        return Result.success()
    }

    private fun isZipFile(file: File): Boolean {
        return file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) == 4) {
                // Check ZIP local file header signature: 0x04034b50 ("PK\u0003\u0004")
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            } else {
                false
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Path traversal attempt detected: ${entry.name}")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        zipInput.copyTo(output)
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec.toDouble() / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec.toDouble() / 1024)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds >= 3600 -> String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60)
            seconds >= 60 -> String.format("%dm %ds", seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }
    }
}
