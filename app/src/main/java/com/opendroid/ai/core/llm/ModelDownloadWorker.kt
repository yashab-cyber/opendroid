package com.opendroid.ai.core.llm

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.db.dao.markDownloadReady
import com.opendroid.ai.data.db.dao.markDownloadFailed
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Downloads only registry-owned catalog artifacts. URL, destination, size, and checksum are
 * deliberately resolved here rather than trusted from WorkManager input data.
 */
class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "ModelDownloadWorker"

    private val verifier = ModelArtifactVerifier()
    private val installer = ModelArtifactInstaller()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun modelDao(): ModelDao
        fun okHttpClient(): OkHttpClient
        fun providerCredentialStore(): ProviderCredentialStore
    }

    private val entryPoint = EntryPointAccessors.fromApplication(
        applicationContext,
        WorkerEntryPoint::class.java
    )

    private val modelDao = entryPoint.modelDao()
    private val okHttpClient = entryPoint.okHttpClient()
    private val providerCredentialStore = entryPoint.providerCredentialStore()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ModelDownloadForegroundInfoFactory.create(applicationContext, id)

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id") ?: return Result.failure()
        // Legacy plaintext credentials must be migrated before any token read below.
        providerCredentialStore.migrateLegacyCredentials()
        val spec = OnDeviceModelRegistry.findById(modelId)
            ?: return fail(modelId, "Unknown model.")
        if (!spec.isManagedDownloadAvailable) {
            return fail(
                modelId,
                "In-app download is unavailable until publisher integrity metadata is recorded."
            )
        }

        try {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(applicationContext, spec)
        } catch (error: IllegalStateException) {
            return fail(modelId, error.localizedMessage ?: "Insufficient device memory.")
        }

        return try {
            // Multi-GB downloads need a visible, long-running WorkManager foreground service;
            // a regular worker would be cut short by the JobScheduler runtime quota.
            setForeground(getForegroundInfo())
            performDownload(modelId, spec)
        } catch (error: CancellationException) {
            // WorkManager owns rescheduling after a stop. Do not turn a cancel/stop into a failure
            // or delete the partial file; the next run resumes it with a Range request.
            logStopReason(modelId)
            throw error
        } catch (error: Exception) {
            Log.e(tag, "Model download failed for model=$modelId", error)
            fail(modelId, "Could not download and verify this model.")
        }
    }

    private suspend fun performDownload(modelId: String, spec: OnDeviceModelSpec): Result {
        val artifact = spec.managedArtifact
            ?.takeIf { it.isComplete }
            ?: return fail(
                modelId,
                "In-app download is unavailable until publisher integrity metadata is recorded."
            )
        val expectedSize = artifact.expectedSize!!
        val downloadUrl = artifact.downloadUrl!!
        val temporaryFile = File(applicationContext.cacheDir, "$modelId.download")
        var retainTemporaryFileForResume = false

        try {
            if (temporaryFile.exists() && temporaryFile.length() > expectedSize) {
                temporaryFile.delete()
            }

            if (temporaryFile.length() < expectedSize) {
                val startBytes = temporaryFile.takeIf(File::exists)?.length() ?: 0L
                val response = when (val outcome = executeRequest(downloadUrl, startBytes)) {
                    is RequestOutcome.Received -> outcome.response
                    is RequestOutcome.Retryable -> {
                        Log.w(
                            tag,
                            "[RETRY] Transport failed for model=$modelId; retaining partial " +
                                "download (${outcome.error.javaClass.simpleName})."
                        )
                        retainTemporaryFileForResume = true
                        return Result.retry()
                    }
                    RequestOutcome.Failed ->
                        return fail(modelId, "Internet connection unavailable.")
                }

                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful && httpResponse.code != 206) {
                        if (ModelDownloadRetryPolicy.isRetryableHttpStatus(httpResponse.code)) {
                            Log.w(
                                tag,
                                "[RETRY] HTTP ${httpResponse.code} for model=$modelId; " +
                                    "retaining partial download."
                            )
                            retainTemporaryFileForResume = true
                            return Result.retry()
                        }
                        return fail(modelId, httpFailureMessage(httpResponse.code))
                    }

                    val append = startBytes > 0L && httpResponse.code == 206
                    if (append && httpResponse.header("Content-Range")
                            ?.startsWith("bytes $startBytes-") != true
                    ) {
                        return fail(modelId, "Server returned an invalid download range.")
                    }

                    httpResponse.body.byteStream().use { input ->
                        FileOutputStream(temporaryFile, append).use { output ->
                            copyResponse(
                                input = input,
                                output = output,
                                initialBytes = if (append) startBytes else 0L,
                                expectedSize = expectedSize,
                                modelId = modelId
                            )
                            output.fd.sync()
                        }
                    }
                }
            }

            if (isStopped) {
                retainTemporaryFileForResume = true
                logStopReason(modelId)
                return Result.retry()
            }

            val downloadedPayload = verifier.verifyManagedPayload(temporaryFile, spec)
            if (downloadedPayload is ArtifactVerificationResult.Invalid) {
                return fail(modelId, verificationFailureMessage(downloadedPayload.failure))
            }

            val targetDir = modelDirectory(spec)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return fail(modelId, "Could not prepare model storage.")
            }
            val install = installer.installManagedDownload(
                source = temporaryFile,
                target = ModelStoragePaths.targetFile(targetDir, spec),
                manifestFile = ModelStoragePaths.manifestFile(targetDir),
                spec = spec,
                verifyFormat = { LiteRtCompatibility.verify(it, applicationContext.cacheDir, spec.contextWindow) }
            )
            if (install is ArtifactVerificationResult.Invalid) {
                return fail(modelId, verificationFailureMessage(install.failure))
            }

            val refFile = File(applicationContext.filesDir, "litert_models/$modelId.litertlm")
            val refDirectory = requireNotNull(refFile.parentFile)
            if (!refDirectory.exists() && !refDirectory.mkdirs()) {
                return fail(modelId, "Could not finalize model installation.")
            }
            refFile.writeText(targetDir.absolutePath)

            modelDao.markDownloadReady(modelId, expectedSize)
            return Result.success()
        } catch (interrupted: RetryableTransportInterruption) {
            Log.w(
                tag,
                "[RETRY] Transport interrupted for model=$modelId; retaining partial download " +
                    "(${interrupted.cause?.javaClass?.simpleName})."
            )
            retainTemporaryFileForResume = true
            return Result.retry()
        } finally {
            if (!retainTemporaryFileForResume) {
                temporaryFile.delete()
            }
        }
    }

    private fun executeRequest(downloadUrl: String, startBytes: Long): RequestOutcome = try {
        val request = Request.Builder()
            .url(downloadUrl)
            .apply {
                val token = huggingFaceToken()
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
                if (startBytes > 0L) {
                    header("Range", "bytes=$startBytes-")
                }
            }
            .build()
        RequestOutcome.Received(okHttpClient.newCall(request).execute())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (ModelDownloadRetryPolicy.isRetryableTransport(error)) {
            RequestOutcome.Retryable(error)
        } else {
            RequestOutcome.Failed
        }
    }

    private fun huggingFaceToken(): String? {
        return when (
            val result = providerCredentialStore.read(ProviderCredentialId.HuggingFaceToken)
        ) {
            is CredentialStoreResult.Success -> result.value
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> null
        }
    }

    private suspend fun copyResponse(
        input: java.io.InputStream,
        output: FileOutputStream,
        initialBytes: Long,
        expectedSize: Long,
        modelId: String
    ) {
        modelDao.updateModelStatus(modelId, ModelStatus.DOWNLOADING)
        val buffer = ByteArray(64 * 1024)
        var totalRead = initialBytes
        var bytesSinceLastUpdate = 0L
        var lastUpdateAt = System.currentTimeMillis()

        while (true) {
            if (isStopped) return
            val bytesRead = try {
                input.read(buffer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A mid-stream drop keeps every byte already written; the retry resumes from it.
                if (ModelDownloadRetryPolicy.isRetryableTransport(error)) {
                    output.fd.sync()
                    throw RetryableTransportInterruption(error)
                }
                throw error
            }
            if (bytesRead < 0) break
            if (totalRead + bytesRead > expectedSize) {
                throw IllegalStateException("Downloaded payload exceeded its published size")
            }

            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            bytesSinceLastUpdate += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastUpdateAt >= 1_000L) {
                val speedBytesPerSecond = bytesSinceLastUpdate * 1_000L / (now - lastUpdateAt)
                val progress = ((totalRead * 100L) / expectedSize).toInt()
                val etaSeconds = if (speedBytesPerSecond > 0L) {
                    (expectedSize - totalRead) / speedBytesPerSecond
                } else {
                    0L
                }
                modelDao.updateDownloadProgressDetails(
                    modelId,
                    progress,
                    totalRead,
                    formatSpeed(speedBytesPerSecond),
                    formatEta(etaSeconds),
                    ModelStatus.DOWNLOADING
                )
                lastUpdateAt = now
                bytesSinceLastUpdate = 0L
            }
        }
    }

    private fun logStopReason(modelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(
                tag,
                "Model download stopped for model=$modelId; stop reason is unavailable before " +
                    "API 31. Partial data is retained for retry."
            )
            return
        }

        val reason = stopReason
        Log.w(
            tag,
            "Model download stopped for model=$modelId; " +
                "stopReason=${ModelDownloadStopReason.label(reason)} ($reason), " +
                "attempt=$runAttemptCount. Partial data is retained for retry."
        )
    }

    private fun modelDirectory(spec: OnDeviceModelSpec): File {
        val baseDir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        return ModelStoragePaths.modelDir(File(baseDir, "models"), spec.id)
    }

    private suspend fun fail(modelId: String, message: String): Result {
        modelDao.markDownloadFailed(modelId, message)
        return Result.failure()
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        401 -> "The Hugging Face token is invalid."
        403 -> "You do not have permission to access this model. Accept its license on Hugging Face first."
        404 -> "Model artifact not found."
        else -> "Download request failed (HTTP $code)."
    }

    companion object {
        internal fun verificationFailureMessage(failure: ArtifactVerificationFailure?): String = when (failure) {
            ArtifactVerificationFailure.METADATA_UNAVAILABLE ->
                "In-app download is unavailable until publisher integrity metadata is recorded."
            ArtifactVerificationFailure.SIZE_MISMATCH ->
                "Downloaded model size does not match the published artifact."
            ArtifactVerificationFailure.HASH_MISMATCH -> "Downloaded model failed its integrity check."
            ArtifactVerificationFailure.FORMAT_INVALID -> "Downloaded model is not compatible with LiteRT."
            ArtifactVerificationFailure.LITERT_RUNTIME_INCOMPATIBLE ->
                "This LiteRT model could not be initialized on this device: no supported backend could load it."
            else -> "Could not securely install the downloaded model."
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L ->
            String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSecond.toDouble() / (1024L * 1024L))
        bytesPerSecond >= 1024L ->
            String.format(Locale.getDefault(), "%.1f KB/s", bytesPerSecond.toDouble() / 1024L)
        else -> "$bytesPerSecond B/s"
    }

    private fun formatEta(seconds: Long): String = when {
        seconds >= 3_600L ->
            String.format(Locale.getDefault(), "%dh %dm", seconds / 3_600L, (seconds % 3_600L) / 60L)
        seconds >= 60L -> String.format(Locale.getDefault(), "%dm %ds", seconds / 60L, seconds % 60L)
        else -> "${seconds}s"
    }

    /** Distinguishes a resumable transport failure from an outright unreachable host. */
    private sealed interface RequestOutcome {
        data class Received(val response: Response) : RequestOutcome
        data class Retryable(val error: Throwable) : RequestOutcome
        data object Failed : RequestOutcome
    }

    /** Signals that a partially written download should be resumed rather than failed. */
    private class RetryableTransportInterruption(
        cause: Throwable
    ) : Exception(cause)
}
