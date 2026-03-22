package com.example.bp.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.*
import java.io.File


class DownloadManager(private val context: Context) {

    private val bandwidthMonitor = BandwidthMonitor(context)
    private val parallelDownloader = ParallelModelDownloader(context, bandwidthMonitor)
    private val downloadStateManager = DownloadStateManager(context)

    var debugForceParallel = false

    // Download queue
    private val _downloadQueue = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val downloadQueue: StateFlow<List<QueuedDownload>> = _downloadQueue.asStateFlow()

    // Current download state
    private val _currentDownload = MutableStateFlow<DownloadState?>(null)
    val currentDownload: StateFlow<DownloadState?> = _currentDownload.asStateFlow()

    // Paused downloads
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
        data class Downloading(
            val modelName: String,
            val progress: DownloadProgress
        ) : DownloadState()

        data class Completed(val modelName: String, val file: File) : DownloadState()
        data class Failed(val modelName: String, val error: String) : DownloadState()
        data class Paused(val modelName: String, val progress: DownloadProgress) : DownloadState()
    }

    data class DownloadStrategy(
        val useParallel: Boolean
    )

    companion object {
        private const val TAG = "DownloadManager"
    }


    // HLAVNÍ API


    suspend fun downloadModelSmart(
        modelInfo: ModelInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? {
        val strategy = determineOptimalStrategy(modelInfo)

        Log.d(
            TAG, """
            Download strategy for ${modelInfo.name}:
            - Parallel: ${strategy.useParallel}
            - Network: ${bandwidthMonitor.networkStats.value.connectionType}
        """.trimIndent()
        )

        _currentDownload.value = DownloadState.Preparing(modelInfo.name)

        return try {
            val file = when {
                strategy.useParallel -> {
                    parallelDownloader.downloadModel(modelInfo.name) { progress ->
                        _currentDownload.value = DownloadState.Downloading(
                            modelInfo.name,
                            progress
                        )
                        onProgress?.invoke(progress)
                    }
                }

                else -> {
                    // Fallback na sekvenční stahování
                    parallelDownloader.downloadModel(modelInfo.name, onProgress)
                }
            }

            if (file != null) {
                _currentDownload.value = DownloadState.Completed(modelInfo.name, file)
                Log.d(TAG, "Download completed: ${modelInfo.name}")
            } else {
                _currentDownload.value = DownloadState.Failed(
                    modelInfo.name,
                    "Download failed"
                )
            }

            file
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            _currentDownload.value = DownloadState.Failed(modelInfo.name, e.message ?: "Unknown error")
            null
        }
    }


    fun queueDownload(modelInfo: ModelInfo, priority: Priority = Priority.NORMAL) {
        val queued = QueuedDownload(modelInfo.name, modelInfo, priority)
        _downloadQueue.value = (_downloadQueue.value + queued)
            .sortedByDescending { it.priority.ordinal }

        Log.d(TAG, "Added to queue: ${modelInfo.name} (priority: $priority)")
    }


    fun removeFromQueue(modelName: String) {
        _downloadQueue.value = _downloadQueue.value.filter { it.modelName != modelName }
    }


    fun clearQueue() {
        _downloadQueue.value = emptyList()
    }


    // STRATEGIE STAHOVÁNÍ


    /*
      Určí optimální strategii stahování na základě:
      - Typu připojení
      - Rychlosti sítě
      - Velikosti souboru
      - Metered connection
     */
    private fun determineOptimalStrategy(modelInfo: ModelInfo): DownloadStrategy {
        bandwidthMonitor.refreshNetworkStats()
        val networkStats = bandwidthMonitor.networkStats.value
        val modelSizeBytes = (modelInfo.sizeInMB * 1024 * 1024).toLong()

        // Debug mode má přednost
        val useParallel = if (debugForceParallel) {
            Log.d(TAG, "🔧 DEBUG MODE: Force parallel enabled!")
            true
        } else {
            // Automatická detekce
            when (networkStats.connectionType) {
                BandwidthMonitor.ConnectionType.OFFLINE -> false  // Sekvenční při offline
                BandwidthMonitor.ConnectionType.WIFI,
                BandwidthMonitor.ConnectionType.ETHERNET -> true

                BandwidthMonitor.ConnectionType.CELLULAR_5G -> modelSizeBytes > 10 * 1024 * 1024
                BandwidthMonitor.ConnectionType.CELLULAR_4G -> modelSizeBytes > 50 * 1024 * 1024
                else -> false
            }
        }

        Log.d(TAG, "Strategy: parallel=$useParallel (debugForceParallel=$debugForceParallel)")

        return DownloadStrategy(useParallel = useParallel)
    }


    fun shouldShowMeteredWarning(modelInfo: ModelInfo): Boolean {
        bandwidthMonitor.refreshNetworkStats()
        val modelSizeBytes = (modelInfo.sizeInMB * 1024 * 1024).toLong()
        return bandwidthMonitor.shouldWarnAboutMeteredConnection(modelSizeBytes)
    }


    fun getDownloadRecommendation(modelInfo: ModelInfo): String {
        bandwidthMonitor.refreshNetworkStats()
        val strategy = determineOptimalStrategy(modelInfo)
        val networkStats = bandwidthMonitor.networkStats.value

        return buildString {
            append("📡 Připojení: ${networkStats.connectionType.name}\n")

            if (networkStats.isMetered) {
                append("⚠️ Mobilní data aktivní\n")
            }

            append("📦 Strategie: ")
            append(if (strategy.useParallel) "Paralelní" else "Sekvenční")
            append("\n")

            val estimatedTime = estimateDownloadTime(modelInfo, networkStats)
            if (estimatedTime > 0) {
                append("⏱️ Odhad: ${formatTime(estimatedTime)}\n")
            }
        }
    }

    private fun estimateDownloadTime(
        modelInfo: ModelInfo,
        networkStats: BandwidthMonitor.NetworkStats
    ): Long {
        if (networkStats.averageSpeed == 0L) return 0

        val sizeBytes = (modelInfo.sizeInMB * 1024 * 1024).toLong()
        return sizeBytes / networkStats.averageSpeed // seconds
    }

    private fun formatTime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }


    // DELEGOVANÉ METODY


    suspend fun getAvailableModels(): List<ModelInfo> {
        return parallelDownloader.getAvailableModels()
    }

    fun isModelDownloaded(modelName: String): Boolean {
        return parallelDownloader.isModelDownloaded(modelName)
    }

    fun getModelPath(modelName: String): String? {
        return parallelDownloader.getModelPath(modelName)
    }

    fun getModelSize(modelName: String): Long {
        return parallelDownloader.getModelSize(modelName)
    }

    fun deleteModel(modelName: String): Boolean {
        return parallelDownloader.deleteModel(modelName)
    }

    fun clearCache() {
        parallelDownloader.clearCache()
    }

    fun getCacheSize(): Long {
        return parallelDownloader.getCacheSize()
    }

    fun getNetworkStats(): BandwidthMonitor.NetworkStats {
        bandwidthMonitor.refreshNetworkStats()
        return bandwidthMonitor.networkStats.value
    }

    // PAUSE/RESUME/CANCEL


    suspend fun pauseDownload(modelName: String): Boolean {
        return try {
            val success = parallelDownloader.pauseDownload(modelName)
            if (success) {
                _currentDownload.value = null
                refreshPausedDownloads()
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause download", e)
            false
        }
    }


    suspend fun resumeDownload(
        modelName: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? {
        return try {
            _currentDownload.value = DownloadState.Preparing(modelName)

            val file = parallelDownloader.resumeDownload(modelName) { progress ->
                _currentDownload.value = DownloadState.Downloading(modelName, progress)
                onProgress?.invoke(progress)
            }

            if (file != null) {
                _currentDownload.value = DownloadState.Completed(modelName, file)
                refreshPausedDownloads()
            } else {
                _currentDownload.value = DownloadState.Failed(modelName, "Resume failed")
            }

            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume download", e)
            _currentDownload.value = DownloadState.Failed(modelName, e.message ?: "Unknown error")
            null
        }
    }


    suspend fun cancelDownload(modelName: String): Boolean {
        return try {
            val success = parallelDownloader.cancelDownload(modelName)
            if (success) {
                _currentDownload.value = null
                refreshPausedDownloads()
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel download", e)
            false
        }
    }


    fun getPausedDownloads(): List<DownloadStateManager.DownloadStateData> {
        return downloadStateManager.getAllPendingDownloads().filter { it.isPaused }
    }

    suspend fun autoResumeDownloads(): Int {
        return try {
            val pausedDownloads = getPausedDownloads()
            var resumedCount = 0

            pausedDownloads.forEach { download ->
                if (parallelDownloader.canResumeDownload(download.modelName)) {
                    // Spustí download service pro automatické resume
                    DownloadService.resumeDownload(context, download.modelName)
                    resumedCount++
                }
            }

            refreshPausedDownloads()
            Log.d(TAG, "Auto-resumed $resumedCount downloads")
            resumedCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-resume downloads", e)
            0
        }
    }

    fun canResumeDownload(modelName: String): Boolean {
        return parallelDownloader.canResumeDownload(modelName)
    }


    private fun refreshPausedDownloads() {
        _pausedDownloads.value = getPausedDownloads()
    }


    fun initialize() {
        refreshPausedDownloads()
        downloadStateManager.cleanupOldDownloads()
    }
}
