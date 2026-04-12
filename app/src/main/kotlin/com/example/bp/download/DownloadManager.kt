package com.example.bp.download

import android.content.Context
import com.example.bp.ModelDownloader

class DownloadManager(private val context: Context) {

    private val modelDownloader = ModelDownloader(context)

    suspend fun downloadModelSmart(
        modelInfo: ModelInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): StreamSession? {
        return modelDownloader.prepareStreamSession(modelInfo, onProgress)
    }

    fun getCachedSession(modelInfo: ModelInfo): StreamSession? = modelDownloader.getCachedSession(modelInfo)

    suspend fun getAvailableModels(): List<ModelInfo> = modelDownloader.getAvailableModels()
    fun isModelDownloaded(modelName: String): Boolean = modelDownloader.isModelDownloaded(modelName)
    fun clearCache(): Boolean = modelDownloader.clearCache()
    fun getCacheSize(): Long = modelDownloader.getCacheSize()
}
