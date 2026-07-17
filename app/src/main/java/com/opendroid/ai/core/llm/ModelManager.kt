package com.opendroid.ai.core.llm

typealias OnDeviceModel = OnDeviceModelSpec

interface ModelManager {
    suspend fun download(model: OnDeviceModel)
    suspend fun delete(model: OnDeviceModel)
    suspend fun load(model: OnDeviceModel)
    suspend fun isDownloaded(model: OnDeviceModel): Boolean
    suspend fun currentModel(): OnDeviceModel?
}
