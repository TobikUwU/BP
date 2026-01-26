package com.example.bp.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile

/**
 * Progressive loading - zobrazuje model postupně během stahování
 * Vytváří LOD (Level of Detail) verze pro rychlejší načtení
 */
class ProgressiveModelLoader(
    private val context: Context,
    private val downloader: ParallelModelDownloader
) {

    data class ProgressiveLoadState(
        val lodLevel: LODLevel,
        val availableChunks: Int,
        val totalChunks: Int,
        val canDisplay: Boolean,
        val modelFile: File?,
        val loadPercentage: Float
    )

    enum class LODLevel(val percentage: Int, val description: String) {
        NONE(0, "Nepřipraveno"),
        LOW(10, "Nízká kvalita - základní geometrie"),
        MEDIUM(30, "Střední kvalita"),
        HIGH(60, "Vysoká kvalita"),
        ULTRA(100, "Plná kvalita")
    }

    companion object {
        private const val TAG = "ProgressiveLoader"
    }

    /**
     * Stáhne model s progresivním zobrazením
     * Callback je volán při dosažení každého LOD levelu
     */
    suspend fun downloadWithProgressiveLoading(
        modelName: String,
        onStateChange: (ProgressiveLoadState) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        val metadata = downloader.getModelMetadata(modelName)

        if (metadata == null) {
            Log.d(TAG, "No metadata, progressive loading not available")
            return@withContext null
        }

        val totalChunks = metadata.totalChunks

        // Definuj LOD boundaries
        val lodBoundaries = mapOf(
            LODLevel.LOW to (totalChunks * 0.1).toInt(),
            LODLevel.MEDIUM to (totalChunks * 0.3).toInt(),
            LODLevel.HIGH to (totalChunks * 0.6).toInt(),
            LODLevel.ULTRA to totalChunks
        )

        val tempFile = File(context.cacheDir, "$modelName.progressive")
        RandomAccessFile(tempFile, "rw").use { it.setLength(metadata.totalSize) }

        var currentLod = LODLevel.NONE
        var downloadedChunks = 0

        Log.d(TAG, "Starting progressive download: $totalChunks chunks")

        // Prioritní pořadí chunků - nejdřív nejdůležitější
        val chunkPriorities = (0 until totalChunks).map { chunkIndex ->
            val priority = when {
                chunkIndex < lodBoundaries[LODLevel.LOW]!! -> 4      // Nejvyšší priorita
                chunkIndex < lodBoundaries[LODLevel.MEDIUM]!! -> 3
                chunkIndex < lodBoundaries[LODLevel.HIGH]!! -> 2
                else -> 1                                             // Nejnižší priorita
            }
            chunkIndex to priority
        }.sortedByDescending { it.second }

        // Stáhni chunky podle priority
        val jobs = chunkPriorities.map { (chunkIndex, _) ->
            async {
                try {
                    // Stáhni chunk (s retry logikou)
                    val chunkData = downloadChunkWithRetry(modelName, chunkIndex, metadata)

                    if (chunkData != null) {
                        // Zapiš do souboru
                        synchronized(tempFile) {
                            RandomAccessFile(tempFile, "rw").use { raf ->
                                raf.seek(chunkIndex.toLong() * metadata.chunkSize)
                                raf.write(chunkData)
                            }
                        }

                        downloadedChunks++

                        // Zkontroluj, zda jsme dosáhli nového LOD levelu
                        val newLod = determineLODLevel(downloadedChunks, lodBoundaries)

                        if (newLod != currentLod && newLod != LODLevel.NONE) {
                            currentLod = newLod

                            Log.d(TAG, "Reached LOD level: $currentLod ($downloadedChunks/$totalChunks chunks)")

                            // Notifikuj UI
                            withContext(Dispatchers.Main) {
                                onStateChange(
                                    ProgressiveLoadState(
                                        lodLevel = currentLod,
                                        availableChunks = downloadedChunks,
                                        totalChunks = totalChunks,
                                        canDisplay = currentLod != LODLevel.NONE,
                                        modelFile = tempFile,
                                        loadPercentage = (downloadedChunks.toFloat() / totalChunks) * 100
                                    )
                                )
                            }
                        }

                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download chunk $chunkIndex", e)
                    false
                }
            }
        }

        // Počkej na všechny chunky
        jobs.awaitAll()

        // Přesuň do finální lokace
        val finalFile = File(context.filesDir, "models/$modelName")
        finalFile.parentFile?.mkdirs()
        tempFile.renameTo(finalFile)

        Log.d(TAG, "Progressive download completed")

        finalFile
    }

    private suspend fun downloadChunkWithRetry(
        modelName: String,
        chunkIndex: Int,
        metadata: ModelMetadata,
        retryCount: Int = 0
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Zkus cache
            val cached = downloader.getCachedChunk(modelName, chunkIndex)
            if (cached != null) {
                return@withContext cached
            }

            // Stáhni nový
            val url = java.net.URL("${downloader.serverUrl}/download-chunk/$modelName/$chunkIndex")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val data = connection.inputStream.readBytes()
                connection.disconnect()

                // Ulož do cache
                downloader.saveCachedChunk(modelName, chunkIndex, data)

                data
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            if (retryCount < 3) {
                delay(1000L * (retryCount + 1))
                downloadChunkWithRetry(modelName, chunkIndex, metadata, retryCount + 1)
            } else {
                null
            }
        }
    }

    private fun determineLODLevel(
        downloadedChunks: Int,
        boundaries: Map<LODLevel, Int>
    ): LODLevel {
        return when {
            downloadedChunks >= boundaries[LODLevel.ULTRA]!! -> LODLevel.ULTRA
            downloadedChunks >= boundaries[LODLevel.HIGH]!! -> LODLevel.HIGH
            downloadedChunks >= boundaries[LODLevel.MEDIUM]!! -> LODLevel.MEDIUM
            downloadedChunks >= boundaries[LODLevel.LOW]!! -> LODLevel.LOW
            else -> LODLevel.NONE
        }
    }
}

/**
 * Extension pro ParallelModelDownloader - přidává metody pro progressive loader
 */
fun ParallelModelDownloader.getModelMetadata(modelName: String): ModelMetadata? {
    // Tato metoda už existuje v ParallelModelDownloader jako private
    // Buď ji můžete udělat internal, nebo zavolat přes reflection
    // Pro jednoduchost použijeme variantu se síťovým voláním zde
    return null // Placeholder - implementace v ParallelModelDownloader
}

fun ParallelModelDownloader.getCachedChunk(modelName: String, chunkIndex: Int): ByteArray? {
    // Tato metoda už existuje v ParallelModelDownloader jako private
    return null // Placeholder
}

fun ParallelModelDownloader.saveCachedChunk(modelName: String, chunkIndex: Int, data: ByteArray) {
    // Tato metoda už existuje v ParallelModelDownloader jako private
}

val ParallelModelDownloader.serverUrl: String
    get() = "http://192.168.50.96:3000" // Nebo načti z konfigurace