package com.example.bp.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Centrální správce stahování - orchestruje všechny download operace
 * Rozhoduje o nejlepší strategii stahování na základě podmínek
 */
class DownloadManager(private val context: Context) {

    private val parallelDownloader = ParallelModelDownloader(context)
    private val bandwidthMonitor = BandwidthMonitor(context)
    private val progressiveLoader = ProgressiveModelLoader(context, parallelDownloader)  // 🆕

    // 🆕 DEBUG FLAGS
    var debugForceParallel = false
    var debugParallelCount = 5

    // Download queue
    private val _downloadQueue = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val downloadQueue: StateFlow<List<QueuedDownload>> = _downloadQueue.asStateFlow()

    // Current download state
    private val _currentDownload = MutableStateFlow<DownloadState?>(null)
    val currentDownload: StateFlow<DownloadState?> = _currentDownload.asStateFlow()

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
        val useParallel: Boolean,
        val maxParallelChunks: Int,
        val compressionEnabled: Boolean,
        val qualityMode: QualityMode,
        val useProgressive: Boolean  // 🆕
    )

    enum class QualityMode {
        AUTO,      // Automaticky podle sítě
        LOW,       // Jen základní chunky
        MEDIUM,    // 50% chunků
        HIGH,      // 80% chunků
        ULTRA      // Všechny chunky
    }

    companion object {
        private const val TAG = "DownloadManager"
    }

    // ========================================================================
    // HLAVNÍ API
    // ========================================================================

    /**
     * Inteligentní download - automaticky vybere nejlepší strategii
     */
    suspend fun downloadModelSmart(
        modelInfo: ModelInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? {
        val strategy = determineOptimalStrategy(modelInfo)

        Log.d(TAG, """
            Download strategy for ${modelInfo.name}:
            - Parallel: ${strategy.useParallel}
            - Max chunks: ${strategy.maxParallelChunks}
            - Quality: ${strategy.qualityMode}
            - Progressive: ${strategy.useProgressive}
            - Network: ${bandwidthMonitor.networkStats.value.connectionType}
        """.trimIndent())

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

    /**
     * 🆕 Stáhne model s preview-based progressive loading
     * 1. Downloads small preview first (fast)
     * 2. Displays preview immediately
     * 3. Downloads full model in background
     * 4. Swaps to full model when ready
     */
    suspend fun downloadModelProgressive(
        modelInfo: ModelInfo,
        onProgressiveState: (ProgressiveModelLoader.PreviewLoadResult) -> Unit
    ): File? {
        Log.d(TAG, "Starting preview-based progressive download for ${modelInfo.name}")

        _currentDownload.value = DownloadState.Preparing(modelInfo.name)

        return try {
            val file = progressiveLoader.downloadWithPreview(
                modelInfo,
                onProgressiveState
            )

            if (file != null) {
                _currentDownload.value = DownloadState.Completed(modelInfo.name, file)
                Log.d(TAG, "Progressive download completed: ${modelInfo.name}")
            } else {
                // Fallback to normal download if preview not available
                Log.d(TAG, "Preview not available, falling back to normal download")
                _currentDownload.value = DownloadState.Failed(
                    modelInfo.name,
                    "Progressive download failed - no preview available"
                )
            }

            file
        } catch (e: Exception) {
            Log.e(TAG, "Progressive download error", e)
            _currentDownload.value = DownloadState.Failed(modelInfo.name, e.message ?: "Unknown error")
            null
        }
    }

    /**
     * 🆕 Vrátí true pokud model podporuje progressive loading (now based on preview availability)
     */
    fun supportsProgressiveLoading(modelInfo: ModelInfo): Boolean {
        return modelInfo.hasPreview  // Now based on preview availability
    }

    /**
     * Přidej do fronty stahování
     */
    fun queueDownload(modelInfo: ModelInfo, priority: Priority = Priority.NORMAL) {
        val queued = QueuedDownload(modelInfo.name, modelInfo, priority)
        _downloadQueue.value = (_downloadQueue.value + queued)
            .sortedByDescending { it.priority.ordinal }

        Log.d(TAG, "Added to queue: ${modelInfo.name} (priority: $priority)")
    }

    /**
     * Odeber z fronty
     */
    fun removeFromQueue(modelName: String) {
        _downloadQueue.value = _downloadQueue.value.filter { it.modelName != modelName }
    }

    /**
     * Vyčisti frontu
     */
    fun clearQueue() {
        _downloadQueue.value = emptyList()
    }

    // ========================================================================
    // STRATEGIE STAHOVÁNÍ
    // ========================================================================

    /**
     * Určí optimální strategii stahování na základě:
     * - Typu připojení
     * - Rychlosti sítě
     * - Velikosti souboru
     * - Metered connection
     */
    private fun determineOptimalStrategy(modelInfo: ModelInfo): DownloadStrategy {
        val networkStats = bandwidthMonitor.networkStats.value
        val modelSizeBytes = (modelInfo.sizeInMB * 1024 * 1024).toLong()

        // 🆕 TEMPORARY FIX: Force parallel if we can download at all
        val useParallel = when (networkStats.connectionType) {
            BandwidthMonitor.ConnectionType.OFFLINE -> {
                // Pokud download vůbec funguje, pravděpodobně nejsme OFFLINE
                Log.w(TAG, "Network detected as OFFLINE but attempting parallel download anyway")
                modelSizeBytes > 10 * 1024 * 1024 // >10MB
            }
            BandwidthMonitor.ConnectionType.WIFI,
            BandwidthMonitor.ConnectionType.ETHERNET -> true
            BandwidthMonitor.ConnectionType.CELLULAR_5G -> modelSizeBytes > 10 * 1024 * 1024
            BandwidthMonitor.ConnectionType.CELLULAR_4G -> modelSizeBytes > 50 * 1024 * 1024
            else -> false
        }

        // Počet paralelních chunků
        val maxParallel = if (useParallel) {
            if (networkStats.recommendedParallelism > 1) {
                networkStats.recommendedParallelism
            } else {
                // 🆕 Default fallback pokud není známa rychlost
                3 // Rozumný default
            }
        } else {
            1
        }

        // Kvalita podle typu připojení
        val qualityMode = when {
            networkStats.isMetered && networkStats.connectionType != BandwidthMonitor.ConnectionType.WIFI -> {
                when (networkStats.connectionType) {
                    BandwidthMonitor.ConnectionType.CELLULAR_5G -> QualityMode.HIGH
                    BandwidthMonitor.ConnectionType.CELLULAR_4G -> QualityMode.MEDIUM
                    else -> QualityMode.LOW
                }
            }
            else -> QualityMode.ULTRA
        }

        // 🆕 Progressive loading decision
        val useProgressive = modelInfo.chunked &&
                modelInfo.totalChunks >= 10 &&
                modelSizeBytes > 10 * 1024 * 1024  // >10MB

        Log.d(TAG, "Strategy: parallel=$useParallel, maxParallel=$maxParallel, quality=$qualityMode, progressive=$useProgressive")

        return DownloadStrategy(
            useParallel = useParallel,
            maxParallelChunks = maxParallel,
            compressionEnabled = networkStats.isMetered,
            qualityMode = qualityMode,
            useProgressive = useProgressive
        )
    }

    /**
     * Měl by uživatel být varován?
     */
    fun shouldShowMeteredWarning(modelInfo: ModelInfo): Boolean {
        val modelSizeBytes = (modelInfo.sizeInMB * 1024 * 1024).toLong()
        return bandwidthMonitor.shouldWarnAboutMeteredConnection(modelSizeBytes)
    }

    /**
     * Získej doporučení pro uživatele
     */
    fun getDownloadRecommendation(modelInfo: ModelInfo): String {
        val strategy = determineOptimalStrategy(modelInfo)
        val networkStats = bandwidthMonitor.networkStats.value

        return buildString {
            append("📡 Připojení: ${networkStats.connectionType.name}\n")

            if (networkStats.isMetered) {
                append("⚠️ Mobilní data aktivní\n")
            }

            append("📦 Strategie: ")
            append(if (strategy.useParallel) "Paralelní (${strategy.maxParallelChunks}×)" else "Sekvenční")
            append("\n")

            // 🆕 Progressive loading info
            if (strategy.useProgressive) {
                append("🎨 Progressive: Zobrazí se při 10%\n")
            }

            append("🎨 Kvalita: ${strategy.qualityMode.name}\n")

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

    // ========================================================================
    // DELEGOVANÉ METODY
    // ========================================================================

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
        return bandwidthMonitor.networkStats.value
    }
}