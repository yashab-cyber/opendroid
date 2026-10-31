package com.opendroid.ai.data.repository

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.db.dao.markDownloadReady
import com.opendroid.ai.data.db.dao.markDownloadFailed
import com.opendroid.ai.data.db.dao.clearDownloadState
import com.opendroid.ai.data.models.modelsInUse
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val context: Context,
    private val modelDao: ModelDao,
    private val settingsRepository: SettingsRepository
) : ModelManager {

    private val tag = "ModelRepository"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val workManager = WorkManager.getInstance(context)
    private val artifactManifestStore = ModelArtifactManifestStore()
    private val artifactVerifier = ModelArtifactVerifier()
    private val artifactInstaller = ModelArtifactInstaller()

    // Coordinate initialization to ensure it runs exactly once
    private val initMutex = Mutex()
    private var isInitialized = false

    init {
        scope.launch {
            initModelsInDatabase()
        }
    }

    val allModelsFlow: Flow<List<ModelEntity>> = modelDao.getAllModels()
        .onStart { initModelsInDatabase() }

    private fun getModelsDirectory(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelsDir = File(baseDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return modelsDir
    }

    private fun getModelDir(modelId: String): File =
        ModelStoragePaths.modelDir(getModelsDirectory(), modelId)

    private suspend fun initModelsInDatabase() {
        initMutex.withLock {
            if (isInitialized) return@withLock
            isInitialized = true
        }

        val registeredModels = OnDeviceModelRegistry.liteRTOnly
        registeredModels.forEach { spec ->
            val existing = modelDao.getModelById(spec.id)
            val dir = getModelDir(spec.id)
            val manifestFile = ModelStoragePaths.manifestFile(dir)
            val candidate = ModelStoragePaths.resolveExistingFile(dir, spec)
            val hasFiles = candidate?.let { file ->
                when (artifactVerifier.verifyForStartup(file, manifestFile, spec)) {
                    ArtifactVerificationResult.Valid -> true
                    is ArtifactVerificationResult.Invalid -> {
                        // Pre-manifest catalog installs are repaired only after a full hash check
                        // against registry-owned metadata. Unknown/local legacy files fail closed.
                        val repairedManifest = artifactVerifier
                            .manifestForVerifiedLegacyCatalogFile(file, spec)
                        if (repairedManifest == null) {
                            false
                        } else {
                            runCatching {
                                artifactManifestStore.writeAtomically(manifestFile, repairedManifest)
                            }.isSuccess
                        }
                    }
                }
            } ?: false

            val currentStatus = when {
                hasFiles -> ModelStatus.READY
                existing != null && (existing.status == ModelStatus.DOWNLOADING || existing.status == ModelStatus.PAUSED) -> existing.status
                else -> ModelStatus.NOT_DOWNLOADED
            }

            val currentProgress = when {
                hasFiles -> 100
                existing != null && (existing.status == ModelStatus.DOWNLOADING || existing.status == ModelStatus.PAUSED) -> existing.downloadProgress
                else -> 0
            }
 
            val entity = ModelEntity(
                id = spec.id,
                name = spec.displayName,
                version = spec.version,
                size = spec.expectedSize,
                downloadUrl = getModelDownloadUrl(spec),
                localPath = dir.absolutePath,
                status = currentStatus,
                downloadProgress = currentProgress,
                lastUsed = existing?.lastUsed ?: 0L,
                installedAt = existing?.installedAt ?: (if (hasFiles) System.currentTimeMillis() else 0L),
                downloadedSize = existing?.downloadedSize
                    ?: (if (hasFiles) candidate.length() else 0L)
            )
 
            modelDao.insertModel(entity)
        }
    }

    private fun getModelDownloadUrl(spec: OnDeviceModelSpec): String {
        return spec.managedArtifact
            ?.takeIf { it.isComplete }
            ?.downloadUrl
            .orEmpty()
    }

    override suspend fun download(model: OnDeviceModel) {
        startDownload(model)
    }

    suspend fun startDownload(model: OnDeviceModel) {
        val spec = OnDeviceModelRegistry.findById(model.id) ?: return
        modelDao.getModelById(spec.id) ?: return
        if (!spec.isManagedDownloadAvailable) {
            modelDao.markDownloadFailed(
                spec.id,
                "In-app download is unavailable until publisher integrity metadata is recorded."
            )
            return
        }
        
        val inputData = Data.Builder()
            // The worker resolves URL, path, byte size, and checksum from the immutable registry.
            // WorkManager input is transport data, not an integrity trust boundary.
            .putString("model_id", spec.id)
            .build()

        val downloadRequest = ModelDownloadWorkRequest.create(inputData, model.id)

        workManager.enqueueUniqueWork(
            "download_${spec.id}",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )
    }

    /**
     * Resolves a LiteRT model: catalog entry first, then a freestanding custom import on disk.
     * Used by the inference provider so custom models work after import without restart.
     */
    fun resolveLiteRTSpec(modelId: String): OnDeviceModelSpec? {
        OnDeviceModelRegistry.findById(modelId)
            ?.takeIf { it.backend == OnDeviceBackend.LITERT_LM }
            ?.let { return it }
        if (!OnDeviceModelRegistry.isCustomId(modelId)) return null

        val dir = getModelDir(modelId)
        val manifestFile = ModelStoragePaths.manifestFile(dir)
        val manifest = artifactManifestStore.read(manifestFile) ?: return null
        if (manifest.modelId != modelId) return null
        val artifact = File(dir, manifest.filename)
        if (!artifact.isFile || artifact.length() <= 0L) return null

        // Display name for inference is non-critical; the settings list uses ModelEntity.name.
        return OnDeviceModelRegistry.customSpec(
            id = modelId,
            displayName = manifest.filename,
            modelFilename = manifest.filename,
            expectedSize = manifest.byteSize
        )
    }

    // lint false positive: the InputStream is closed by `.use { }` below, but
    // the Recycle detector does not model Kotlin's use() inlining. See #67.
    @Suppress("Recycle")
    suspend fun importLocalModel(modelId: String, uri: android.net.Uri): ImportLocalModelResult =
        withContext(Dispatchers.IO) {
            val spec = OnDeviceModelRegistry.findById(modelId)
                ?: return@withContext ImportLocalModelResult.Failure("Unknown model id: $modelId")
            installImportedFile(modelId, spec, uri, registerCustomEntity = false)
        }

    /**
     * Imports a LiteRT `.task` / `.litertlm` file as a freestanding custom model (new id),
     * independent of the built-in catalog slots.
     */
    // lint false positive: see importLocalModel / #67.
    @Suppress("Recycle")
    suspend fun importCustomLocalModel(uri: android.net.Uri): ImportLocalModelResult =
        withContext(Dispatchers.IO) {
            val displayName = resolveDisplayName(uri)
            if (ModelStoragePaths.isLikelyUnsupportedGguf(displayName)) {
                return@withContext ImportLocalModelResult.Failure(
                    "GGUF is not supported. OpenDroid on-device models must be LiteRT (.task or .litertlm). " +
                        "Convert the model or pick a LiteRT build from Hugging Face litert-community."
                )
            }
            val safeFilename = ModelStoragePaths.sanitizeImportFilename(displayName)
                ?: return@withContext ImportLocalModelResult.Failure(
                    "Unsupported file type. Import a LiteRT model with a .task or .litertlm extension."
                )

            val modelId = allocateCustomModelId(safeFilename)
            val spec = OnDeviceModelRegistry.customSpec(
                id = modelId,
                displayName = displayNameForImport(displayName, safeFilename),
                modelFilename = safeFilename
            )
            installImportedFile(modelId, spec, uri, registerCustomEntity = true)
        }

    private fun allocateCustomModelId(safeFilename: String): String {
        val base = safeFilename
            .substringBeforeLast('.')
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "model" }
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        return "${OnDeviceModelRegistry.CUSTOM_ID_PREFIX}$base-$suffix"
    }

    private fun displayNameForImport(rawDisplayName: String, safeFilename: String): String {
        val leaf = rawDisplayName.substringAfterLast('/').substringAfterLast('\\').trim()
        return leaf.ifBlank { safeFilename }
    }

    private fun resolveDisplayName(uri: android.net.Uri): String {
        val fromUri = uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index)?.trim().orEmpty() else ""
                    } else {
                        ""
                    }
                }
                ?.takeIf { it.isNotBlank() }
                ?: fromUri.ifBlank { "model.litertlm" }
        } catch (_: Exception) {
            fromUri.ifBlank { "model.litertlm" }
        }
    }

    @Suppress("Recycle")
    private suspend fun installImportedFile(
        modelId: String,
        initialSpec: OnDeviceModelSpec,
        uri: android.net.Uri,
        registerCustomEntity: Boolean
    ): ImportLocalModelResult {
        val dir = getModelDir(modelId)
        if (!dir.exists() && !dir.mkdirs()) {
            return ImportLocalModelResult.Failure(
                "Could not prepare private model storage. Free space and try again."
            )
        }
        var spec = initialSpec
        val targetFile = ModelStoragePaths.targetFile(dir, spec)
        val manifestFile = ModelStoragePaths.manifestFile(dir)
        val temporaryImport = File.createTempFile(".model-import-", ".tmp", context.cacheDir)

        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return ImportLocalModelResult.Failure(
                    "Could not open the selected file. Try another file manager or copy the model to Downloads first."
                )
            input.use { stream ->
                temporaryImport.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }

            val finalSize = temporaryImport.length()
            if (finalSize < ModelStoragePaths.MIN_LOCAL_IMPORT_BYTES) {
                return ImportLocalModelResult.Failure(
                    "Imported file is too small (${finalSize} bytes). Expected a LiteRT model (.litertlm or .task) larger than 10 MB."
                )
            }

            // Size-driven RAM gate once the payload is known (critical for freestanding custom).
            spec = if (registerCustomEntity || OnDeviceModelRegistry.isCustomId(modelId)) {
                initialSpec.copy(expectedSize = finalSize)
            } else {
                initialSpec
            }
            try {
                OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, spec)
            } catch (e: IllegalStateException) {
                val reason = e.localizedMessage ?: "Insufficient device memory."
                Log.e(tag, "RAM check failed for import: ${e.message}")
                if (!registerCustomEntity) {
                    modelDao.markDownloadFailed(modelId, reason)
                }
                return ImportLocalModelResult.Failure(reason)
            }

            val install = artifactInstaller.installLocalImport(
                source = temporaryImport,
                target = targetFile,
                manifestFile = manifestFile,
                spec = spec,
                verifyFormat = { LiteRtCompatibility.verify(it, context.cacheDir) }
            )
            if (install is ArtifactVerificationResult.Invalid) {
                if (install.failure == ArtifactVerificationFailure.LITERT_RUNTIME_INCOMPATIBLE) {
                    Log.w(tag, "LiteRT runtime could not initialize imported model: ${spec.id}")
                    return ImportLocalModelResult.Failure(
                        "This LiteRT model could not be initialized on this device: " +
                            "no supported backend could load it. Try the latest app version " +
                            "or a model built for this device."
                    )
                }
                if (install.failure == ArtifactVerificationFailure.FORMAT_INVALID) {
                    return ImportLocalModelResult.Failure(
                        "LiteRT could not open this file. Ensure it is a valid .litertlm or .task model."
                    )
                }
                return ImportLocalModelResult.Failure(
                    "Could not securely install the selected model. The existing installed model was left unchanged."
                )
            }

            val refFile = File(context.filesDir, "litert_models/${modelId}.litertlm")
            refFile.parentFile?.mkdirs()
            refFile.writeText(dir.absolutePath)

            if (registerCustomEntity) {
                val now = System.currentTimeMillis()
                modelDao.insertModel(
                    ModelEntity(
                        id = modelId,
                        name = spec.displayName,
                        version = spec.version,
                        size = finalSize,
                        downloadUrl = "",
                        localPath = dir.absolutePath,
                        status = ModelStatus.READY,
                        downloadProgress = 100,
                        lastUsed = 0L,
                        installedAt = now,
                        downloadedSize = finalSize
                    )
                )
            } else {
                modelDao.markDownloadReady(modelId, finalSize)
            }

            ImportLocalModelResult.Success
        } catch (e: Exception) {
            Log.e(tag, "Failed to import local model", e)
            if (registerCustomEntity) {
                // New custom slot: roll back partial tree so a failed import leaves no orphan row/dir.
                runCatching {
                    if (dir.exists()) dir.deleteRecursively()
                    File(context.filesDir, "litert_models/${modelId}.litertlm").delete()
                    modelDao.deleteModel(modelId)
                }
            }
            ImportLocalModelResult.Failure("Import failed. The existing installed model was left unchanged.")
        } finally {
            temporaryImport.delete()
        }
    }

    suspend fun pauseDownload(model: OnDeviceModel) {
        if (OnDeviceModelRegistry.findById(model.id) == null) return
        modelDao.updateModelStatus(model.id, ModelStatus.PAUSED)
        workManager.cancelUniqueWork("download_${model.id}")
    }

    suspend fun cancelDownload(model: OnDeviceModel) {
        val isCatalog = OnDeviceModelRegistry.findById(model.id) != null
        val isCustom = OnDeviceModelRegistry.isCustomId(model.id)
        if (!isCatalog && !isCustom) return
        // Mark cancellation before stopping work so a stopping worker cannot restore PAUSED.
        if (isCatalog) {
            modelDao.clearDownloadState(model.id)
            workManager.cancelUniqueWork("download_${model.id}")
        }
        val dir = getModelDir(model.id)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        listOf(
            File(context.cacheDir, "${model.id}.download"),
            File(context.cacheDir, "${model.id}.tmp")
        ).forEach { temporaryFile ->
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }

        val refFile = File(context.filesDir, "litert_models/${model.id}.litertlm")
        if (refFile.exists()) {
            refFile.delete()
        }
        if (isCustom) {
            modelDao.deleteModel(model.id)
        }
    }

    suspend fun resumeDownload(model: OnDeviceModel) {
        if (modelDao.getModelById(model.id) == null) return
        startDownload(model)
    }

    override suspend fun delete(model: OnDeviceModel) {
        cancelDownload(model)
        if (OnDeviceModelRegistry.findById(model.id) != null) {
            modelDao.updateModelStatus(model.id, ModelStatus.NOT_DOWNLOADED)
        }
    }

    override suspend fun load(model: OnDeviceModel) {
        modelDao.updateModelStatus(model.id, ModelStatus.LOADING)

        // Simulate loading process
        kotlinx.coroutines.delay(1000)

        modelDao.updateModelStatus(model.id, ModelStatus.READY)
        modelDao.updateLastUsed(model.id, System.currentTimeMillis())

        settingsRepository.updateConfig { current ->
            current
                .withActiveProvider(ProviderCatalog.ON_DEVICE)
                .withSelectedModel(ProviderCatalog.ON_DEVICE, model.id)
        }
    }

    override suspend fun isDownloaded(model: OnDeviceModel): Boolean {
        val spec = resolveLiteRTSpec(model.id) ?: return false
        val dir = getModelDir(model.id)
        val file = ModelStoragePaths.resolveExistingFile(dir, spec) ?: return false
        return artifactVerifier.verifyForStartup(
            file,
            ModelStoragePaths.manifestFile(dir),
            spec
        ) == ArtifactVerificationResult.Valid
    }

    override suspend fun currentModel(): OnDeviceModel? {
        val config = settingsRepository.llmConfig.first()
        return resolveLiteRTSpec(config.activeModel)
            ?: OnDeviceModelRegistry.findById(config.activeModel)
    }

    // ── Storage Management ──
    
    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val usedByAppBytes: Long
    )

    fun getStorageInfoFlow(): Flow<StorageInfo> = flow {
        while (true) {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = totalBlocks * blockSize
            val free = availableBlocks * blockSize
            val usedByApp = getFolderSize(getModelsDirectory())

            emit(StorageInfo(total, free, usedByApp))
            kotlinx.coroutines.delay(5000)
        }
    }.flowOn(Dispatchers.IO)

    private fun getFolderSize(file: File): Long {
        if (file.isDirectory) {
            var size = 0L
            val children = file.listFiles() ?: return 0L
            for (child in children) {
                size += getFolderSize(child)
            }
            return size
        }
        return file.length()
    }

    suspend fun deleteUnusedModels() {
        val modelsInUse = settingsRepository.llmConfig.first().modelsInUse()

        OnDeviceModelRegistry.liteRTOnly.forEach { spec ->
            if (spec.id !in modelsInUse) {
                val dir = getModelDir(spec.id)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                
                val refFile = File(context.filesDir, "litert_models/${spec.id}.litertlm")
                if (refFile.exists()) {
                    refFile.delete()
                }

                modelDao.clearDownloadState(spec.id)
            }
        }

        // Remove custom imports that no provider is set to run
        modelDao.getAllModels().first().forEach { entity ->
            if (OnDeviceModelRegistry.isCustomId(entity.id) && entity.id !in modelsInUse) {
                cancelDownload(
                    OnDeviceModelRegistry.customSpec(
                        id = entity.id,
                        displayName = entity.name,
                        modelFilename = "model.litertlm"
                    )
                )
            }
        }
    }
}
