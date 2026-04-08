package com.example.bp.download

import android.content.Context
import android.util.Log
import com.example.bp.ModelDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DownloadManager(private val context: Context) {

    private val bandwidthMonitor = BandwidthMonitor(context)
    private val modelDownloader = ModelDownloader(context)

    var debugForceParallel = false

    private val _downloadQueue = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val downloadQueue: StateFlow<List<QueuedDownload>> = _downloadQueue.asStateFlow()

    private val _currentDownload = MutableStateFlow<DownloadState?>(null)
    val currentDownload: StateFlow<DownloadState?> = _currentDownload.asStateFlow()

    private val _pausedDownloads = MutableStateFlow<List<DownloadStateManager.DownloadStateData>>(emptyList())
    val pausedDownloads: StateFlow<List<DownloadStateManager.DownloadStateData>> = _pausedDownloads.asStateFlow()

    data class QueuedDownload(
        val modelName: String,
        val modelInfo: ModelInfo,
        val priority: Priority = Priority.NORMAL
    )

    enum class Priority {
        LOW, NORMAL, HIGH
    }

    sealed class DownloadState {
        data class Preparing(val modelName: String) : DownloadState()
        data class Downloading(val modelName: String, val progress: DownloadProgress) : DownloadState()
        data class Completed(val modelName: String, val session: StreamSession) : DownloadState()
        data class Failed(val modelName: String, val error: String) : DownloadState()
        data class Paused(val modelName: String, val progress: DownloadProgress) : DownloadState()
    }

    companion object {
        private const val TAG = "DownloadManager"
    }

    suspend fun downloadModelSmart(
        modelInfo: ModelInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): StreamSession? {
        _currentDownload.value = DownloadState.Preparing(modelInfo.name)
        return try {
            val session = modelDownloader.prepareStreamSession(modelInfo) { progress ->
                _currentDownload.value = DownloadState.Downloading(modelInfo.name, progress)
                onProgress?.invoke(progress)
            }

            if (session != null) {
                _currentDownload.value = DownloadState.Completed(modelInfo.name, session)
            } else {
                _currentDownload.value = DownloadState.Failed(modelInfo.name, "Download failed")
            }
            session
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            _currentDownload.value = DownloadState.Failed(modelInfo.name, e.message ?: "Unknown error")
            null
        }
    }

    fun getCachedSession(modelInfo: ModelInfo): StreamSession? = modelDownloader.getCachedSession(modelInfo)

    fun queueDownload(modelInfo: ModelInfo, priority: Priority = Priority.NORMAL) {
        _downloadQueue.value = (_downloadQueue.value + QueuedDownload(modelInfo.name, modelInfo, priority))
            .sortedByDescending { it.priority.ordinal }
    }

    fun removeFromQueue(modelName: String) {
        _downloadQueue.value = _downloadQueue.value.filter { it.modelName != modelName }
    }

    fun clearQueue() {
        _downloadQueue.value = emptyList()
    }

    fun shouldShowMeteredWarning(modelInfo: ModelInfo): Boolean {
        bandwidthMonitor.refreshNetworkStats()
        val modelSizeBytes = (modelInfo.totalSizeInMB * 1024 * 1024).toLong()
        return bandwidthMonitor.shouldWarnAboutMeteredConnection(modelSizeBytes)
    }

    fun getDownloadRecommendation(modelInfo: ModelInfo): String {
        bandwidthMonitor.refreshNetworkStats()
        val networkStats = bandwidthMonitor.networkStats.value
        val estimatedTime = estimateDownloadTime(modelInfo, networkStats)

        return buildString {
            append("Připojení: ${networkStats.connectionType.name}\n")
            if (networkStats.isMetered) append("Mobilní data aktivní\n")
            append("Backend: ${modelInfo.streamingStrategy.ifBlank { "hybrid_overview_tiles" }}\n")
            append("Overview stages: ${modelInfo.overviewStageCount}, detail tiles: ${modelInfo.tileCount}\n")
            if (estimatedTime > 0) append("Odhad prvního stage: ${formatTime(estimatedTime)}\n")
        }
    }

    private fun estimateDownloadTime(modelInfo: ModelInfo, networkStats: BandwidthMonitor.NetworkStats): Long {
        if (networkStats.averageSpeed == 0L) return 0
        val sizeBytes = (modelInfo.totalSizeInMB * 1024 * 1024).toLong()
        return sizeBytes / networkStats.averageSpeed
    }

    private fun formatTime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    suspend fun getAvailableModels(): List<ModelInfo> = modelDownloader.getAvailableModels()
    fun isModelDownloaded(modelName: String): Boolean = modelDownloader.isModelDownloaded(modelName)
    fun getModelPath(modelName: String): String? = modelDownloader.getModelPath(modelName)
    fun getModelSize(modelName: String): Long = modelDownloader.getModelSize(modelName)
    fun deleteModel(modelName: String): Boolean = modelDownloader.deleteModel(modelName)
    fun clearCache() = modelDownloader.clearCache()
    fun getCacheSize(): Long = modelDownloader.getCacheSize()

    fun getNetworkStats(): BandwidthMonitor.NetworkStats {
        bandwidthMonitor.refreshNetworkStats()
        return bandwidthMonitor.networkStats.value
    }

    suspend fun pauseDownload(modelName: String): Boolean = false
    suspend fun resumeDownload(modelName: String, onProgress: ((DownloadProgress) -> Unit)? = null): StreamSession? = null
    suspend fun cancelDownload(modelName: String): Boolean = false
    fun getPausedDownloads(): List<DownloadStateManager.DownloadStateData> = emptyList()
    suspend fun autoResumeDownloads(): Int = 0
    fun canResumeDownload(modelName: String): Boolean = false
    fun initialize() = Unit
}
