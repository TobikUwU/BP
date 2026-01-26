package com.example.bp.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Progressive loading using server-side preview files
 * 1. Download small preview file first (fast)
 * 2. Display preview immediately
 * 3. Download full model in background
 * 4. Swap to full model when ready
 */
class ProgressiveModelLoader(
    private val context: Context,
    private val downloader: ParallelModelDownloader
) {

    /**
     * Progressive load states for preview-based loading
     */
    enum class ProgressiveLoadState {
        LOADING_PREVIEW,    // Downloading preview file
        PREVIEW_READY,      // Preview downloaded, can display
        LOADING_FULL,       // Downloading full model in background
        FULL_READY          // Full model ready
    }

    /**
     * Callback data for state changes
     */
    data class PreviewLoadResult(
        val state: ProgressiveLoadState,
        val previewFile: File? = null,
        val fullFile: File? = null,
        val previewProgress: Float = 0f,    // 0-1 for preview download
        val fullProgress: Float = 0f,       // 0-1 for full model download
        val message: String = ""
    )

    companion object {
        private const val TAG = "ProgressiveLoader"
    }

    /**
     * Download with server-side preview
     * 1. Check if preview exists (hasPreview)
     * 2. Download preview file directly (no chunks)
     * 3. Call onStateChange(PREVIEW_READY, previewFile)
     * 4. Start downloading full model in background
     * 5. Call onStateChange(FULL_READY, fullFile) when done
     */
    suspend fun downloadWithPreview(
        modelInfo: ModelInfo,
        onStateChange: (PreviewLoadResult) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        if (!modelInfo.hasPreview || modelInfo.previewName == null) {
            Log.d(TAG, "No preview available for ${modelInfo.name}, falling back to normal download")
            return@withContext null
        }

        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        try {
            // Step 1: Download preview
            Log.d(TAG, "⏳ Downloading preview: ${modelInfo.previewName}")
            onStateChange(PreviewLoadResult(
                state = ProgressiveLoadState.LOADING_PREVIEW,
                message = "Načítání náhledu..."
            ))

            val downloadedPreview = downloader.downloadPreview(modelInfo.previewName)

            if (downloadedPreview == null) {
                Log.e(TAG, "Failed to download preview, falling back")
                return@withContext null
            }

            // Step 2: Preview ready - notify UI immediately
            Log.d(TAG, "✨ Preview ready: ${downloadedPreview.absolutePath}")
            onStateChange(PreviewLoadResult(
                state = ProgressiveLoadState.PREVIEW_READY,
                previewFile = downloadedPreview,
                previewProgress = 1f,
                message = "Náhled připraven"
            ))

            // Step 3: Start downloading full model in background
            Log.d(TAG, "📦 Starting full model download: ${modelInfo.name}")
            onStateChange(PreviewLoadResult(
                state = ProgressiveLoadState.LOADING_FULL,
                previewFile = downloadedPreview,
                previewProgress = 1f,
                message = "Stahování plné kvality..."
            ))

            val downloadedFull = downloader.downloadModel(modelInfo.name) { progress ->
                val fullProgress = if (progress.totalBytes > 0) {
                    progress.downloadedBytes.toFloat() / progress.totalBytes
                } else 0f

                onStateChange(PreviewLoadResult(
                    state = ProgressiveLoadState.LOADING_FULL,
                    previewFile = downloadedPreview,
                    previewProgress = 1f,
                    fullProgress = fullProgress,
                    message = "Plná kvalita: ${(fullProgress * 100).toInt()}%"
                ))
            }

            if (downloadedFull == null) {
                Log.e(TAG, "Failed to download full model, but preview is available")
                // Return preview file as fallback
                return@withContext downloadedPreview
            }

            // Step 4: Full model ready
            Log.d(TAG, "✅ Full model ready: ${downloadedFull.absolutePath}")
            onStateChange(PreviewLoadResult(
                state = ProgressiveLoadState.FULL_READY,
                previewFile = downloadedPreview,
                fullFile = downloadedFull,
                previewProgress = 1f,
                fullProgress = 1f,
                message = "Plná kvalita připravena"
            ))

            downloadedFull

        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Preview download error", e)
            null
        }
    }
}
