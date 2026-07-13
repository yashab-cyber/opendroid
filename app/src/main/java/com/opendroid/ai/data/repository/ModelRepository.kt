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
            val hasFiles = dir.exists() && File(dir, "model.task").exists() && File(dir, "model.task").length() > 0

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
                version = "1.0.0",
                size = getModelSize(spec.id),
                downloadUrl = getModelDownloadUrl(spec),
                localPath = dir.absolutePath,
                status = currentStatus,
                downloadProgress = currentProgress,
                lastUsed = existing?.lastUsed ?: 0L,
                installedAt = existing?.installedAt ?: (if (hasFiles) System.currentTimeMillis() else 0L),
                downloadedSize = existing?.downloadedSize ?: (if (hasFiles) getModelSize(spec.id) else 0L)
            )

            modelDao.insertModel(entity)
        }
    }

    private fun getModelSize(modelId: String): Long {
        return when {
            modelId.contains("2b") -> 2_600_000_000L
            modelId.contains("4b") -> 4_300_000_000L
            else -> 2_000_000_000L
        }
    }

    private fun getModelDownloadUrl(spec: OnDeviceModelSpec): String {
        return "https://huggingface.co/${spec.modelPath}/resolve/main/model.task"
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
