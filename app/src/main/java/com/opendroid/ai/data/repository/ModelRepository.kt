package com.opendroid.ai.data.repository

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private fun getModelDir(modelId: String): File {
        val folderName = when (modelId) {
            "gemma-4-e2b-it-litert" -> "Gemma4-E2B"
            "gemma-4-e4b-it-litert" -> "Gemma4-E4B"
            "gemma-3n-e2b-it-litert" -> "Gemma3n-E2B"
            "gemma-3n-e4b-it-litert" -> "Gemma3n-E4B"
            else -> modelId.replace("-", "").replace("litert", "").replace("it", "")
        }
        return File(getModelsDirectory(), folderName)
    }

    private suspend fun initModelsInDatabase() {
        val registeredModels = OnDeviceModelRegistry.liteRTOnly
        registeredModels.forEach { spec ->
            val existing = modelDao.getModelById(spec.id)
            val dir = getModelDir(spec.id)
            
            val modelTaskFile = File(dir, "model.task")
            if (modelTaskFile.exists() && modelTaskFile.length() <= 100 * 1024 * 1024) {
                Log.w(tag, "Deleting invalid/simulated placeholder model file: ${modelTaskFile.absolutePath} (size: ${modelTaskFile.length()} bytes)")
                try {
                    modelTaskFile.delete()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to delete invalid placeholder model file", e)
                }
            }
            
            val hasFiles = dir.exists() && modelTaskFile.exists() && modelTaskFile.length() > 100 * 1024 * 1024
 
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
                downloadedSize = existing?.downloadedSize ?: (if (hasFiles) spec.expectedSize else 0L)
            )
 
            modelDao.insertModel(entity)
        }
    }

    private fun getModelDownloadUrl(spec: OnDeviceModelSpec): String {
        return "https://huggingface.co/${spec.modelPath}/resolve/main/${spec.modelFilename}"
    }

    override suspend fun download(model: OnDeviceModel) {
        startDownload(model, simulate = false)
    }

    suspend fun startDownload(model: OnDeviceModel, simulate: Boolean) {
        val entity = modelDao.getModelById(model.id) ?: return
        
        val inputData = Data.Builder()
            .putString("model_id", model.id)
            .putString("download_url", entity.downloadUrl)
            .putString("target_path", entity.localPath)
            .putLong("size", entity.size)
            .putString("sha256", model.sha256)
            .putBoolean("simulate", simulate)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag("download_${model.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${model.id}",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )
    }

    suspend fun importLocalModel(modelId: String, uri: android.net.Uri): Boolean {
        val spec = OnDeviceModelRegistry.findById(modelId) ?: return false
        try {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, spec)
        } catch (e: IllegalStateException) {
            Log.e(tag, "RAM check failed for import: ${e.message}")
            modelDao.updateDownloadProgressDetails(
                modelId,
                0,
                0L,
                "",
                e.localizedMessage ?: "Insufficient device memory.",
                ModelStatus.FAILED
            )
            return false
        }
        val dir = getModelDir(modelId)
        if (!dir.exists()) dir.mkdirs()
        val targetFile = File(dir, "model.task")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            val finalSize = targetFile.length()
            if (finalSize < 10 * 1024 * 1024) { // Must be > 10MB
                Log.e(tag, "Imported file size is too small: $finalSize bytes")
                targetFile.delete()
                return false
            }

            // Verify LiteRT compatibility
            Log.i(tag, "Verifying LiteRT compatibility of imported model...")
            try {
                val config = com.google.ai.edge.litertlm.EngineConfig(targetFile.absolutePath, com.google.ai.edge.litertlm.Backend.CPU())
                com.google.ai.edge.litertlm.Engine(config).use { engine ->
                    engine.initialize()
                }
                Log.i(tag, "LiteRT compatibility verified successfully.")
            } catch (e: Throwable) {
                Log.e(tag, "Imported file is not LiteRT compatible: ${e.message}", e)
                targetFile.delete()
                return false
            }

            // Write references & manifest
            val manifestFile = File(dir, "manifest.json")
            val manifest = org.json.JSONObject().apply {
                put("model_id", modelId)
                put("status", "ready")
                put("format", "litertlm")
            }
            manifestFile.writeText(manifest.toString())

            val refFile = File(context.filesDir, "litert_models/${modelId}.litertlm")
            refFile.parentFile?.mkdirs()
            refFile.writeText(dir.absolutePath)

            // Update database status
            modelDao.updateDownloadProgressDetails(
                modelId,
                100,
                finalSize,
                "",
                "",
                ModelStatus.READY
            )

            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to import local model", e)
            if (targetFile.exists()) targetFile.delete()
            return false
        }
    }

    suspend fun pauseDownload(model: OnDeviceModel) {
        workManager.cancelUniqueWork("download_${model.id}")
        modelDao.updateModelStatus(model.id, ModelStatus.PAUSED)
    }

    suspend fun cancelDownload(model: OnDeviceModel) {
        workManager.cancelUniqueWork("download_${model.id}")
        val dir = getModelDir(model.id)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val tempFile = File(context.cacheDir, "${model.id}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        
        val refFile = File(context.filesDir, "litert_models/${model.id}.litertlm")
        if (refFile.exists()) {
            refFile.delete()
        }

        modelDao.updateDownloadProgressDetails(
            model.id,
            0,
            0L,
            "",
            "",
            ModelStatus.NOT_DOWNLOADED
        )
    }

    suspend fun resumeDownload(model: OnDeviceModel) {
        val entity = modelDao.getModelById(model.id) ?: return
        startDownload(model, simulate = false)
    }

    override suspend fun delete(model: OnDeviceModel) {
        cancelDownload(model)
        modelDao.updateModelStatus(model.id, ModelStatus.NOT_DOWNLOADED)
    }

    override suspend fun load(model: OnDeviceModel) {
        modelDao.updateModelStatus(model.id, ModelStatus.LOADING)
        
        // Simulate loading process
        kotlinx.coroutines.delay(1000)
        
        modelDao.updateModelStatus(model.id, ModelStatus.READY)
        modelDao.updateLastUsed(model.id, System.currentTimeMillis())
        
        settingsRepository.updateConfig { current ->
            current.copy(activeModel = model.id)
        }
    }

    override suspend fun isDownloaded(model: OnDeviceModel): Boolean {
        val dir = getModelDir(model.id)
        return dir.exists() && File(dir, "model.task").exists() && File(dir, "model.task").length() > 0
    }

    override suspend fun currentModel(): OnDeviceModel? {
        val config = settingsRepository.llmConfig.first()
        return OnDeviceModelRegistry.findById(config.activeModel)
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
        val config = settingsRepository.llmConfig.first()
        val activeModelId = config.activeModel
        
        OnDeviceModelRegistry.liteRTOnly.forEach { spec ->
            if (spec.id != activeModelId) {
                val dir = getModelDir(spec.id)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                
                val refFile = File(context.filesDir, "litert_models/${spec.id}.litertlm")
                if (refFile.exists()) {
                    refFile.delete()
                }

                modelDao.updateDownloadProgressDetails(
                    spec.id,
                    0,
                    0L,
                    "",
                    "",
                    ModelStatus.NOT_DOWNLOADED
                )
            }
        }
    }
}
