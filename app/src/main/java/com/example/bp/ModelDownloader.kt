package com.example.bp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelInfo(
    val name: String,
    val sizeInMB: Double,
    val modified: String,
    val chunked: Boolean = false,
    val totalChunks: Int = 0
)

data class ModelMetadata(
    val modelName: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val totalSize: Long,
    val fileHash: String,
    val chunkHashes: List<String>
)

data class DownloadProgress(
    val downloadedChunks: Int,
    val totalChunks: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentSpeed: Long, // bytes per second
    val eta: Long // seconds
)

class ModelDownloader(private val context: Context) {

    private val serverUrl = "http://192.168.50.96:3000"
    private val cacheDir = File(context.cacheDir, "model_chunks")
    private val modelsDir = File(context.filesDir, "models")

    companion object {
        private const val TAG = "ModelDownloader"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    init {
        // Vytvoř potřebné složky
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    // ========================================================================
    // SEZNAM MODELŮ
    // ========================================================================

    suspend fun getAvailableModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$serverUrl/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            Log.d(TAG, "Server response: $response")

            val jsonResponse = JSONObject(response)
            val modelsArray = jsonResponse.getJSONArray("models")

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until modelsArray.length()) {
                val modelObj = modelsArray.getJSONObject(i)
                models.add(
                    ModelInfo(
                        name = modelObj.getString("name"),
                        sizeInMB = modelObj.getDouble("sizeInMB"),
                        modified = modelObj.getString("modified"),
                        chunked = modelObj.optBoolean("chunked", false),
                        totalChunks = modelObj.optInt("totalChunks", 0)
                    )
                )
            }

            Log.d(TAG, "Parsed ${models.size} models")
            models
        } catch (e: Exception) {
            Log.e(TAG, "Error getting models list", e)
            emptyList()
        }
    }

    // ========================================================================
    // ZÍSKÁNÍ METADATA
    // ========================================================================

    private suspend fun getModelMetadata(modelName: String): ModelMetadata? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/model-metadata/$modelName")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Metadata not found (HTTP ${connection.responseCode}) - model doesn't support chunking")
                    connection.disconnect()
                    return@withContext null
                }

                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val jsonResponse = JSONObject(response)
                val metadata = jsonResponse.getJSONObject("metadata")

                val chunkHashesArray = metadata.getJSONArray("chunkHashes")
                val chunkHashes = mutableListOf<String>()
                for (i in 0 until chunkHashesArray.length()) {
                    chunkHashes.add(chunkHashesArray.getString(i))
                }

                ModelMetadata(
                    modelName = metadata.getString("modelName"),
                    totalChunks = metadata.getInt("totalChunks"),
                    chunkSize = metadata.getInt("chunkSize"),
                    totalSize = metadata.getLong("totalSize"),
                    fileHash = metadata.getString("fileHash"),
                    chunkHashes = chunkHashes
                )
            } catch (e: Exception) {
                Log.d(TAG, "Metadata not available for $modelName - using fallback download")
                null
            }
        }

    // ========================================================================
    // CHUNKED DOWNLOAD S RESUMABLE SUPPORT
    // ========================================================================

    suspend fun downloadModelChunked(
        modelName: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting chunked download: $modelName")

            // Získej metadata
            val metadata = getModelMetadata(modelName)
            if (metadata == null) {
                Log.e(TAG, "Failed to get metadata, falling back to regular download")
                return@withContext downloadModelFallback(modelName, onProgress)
            }

            Log.d(TAG, "Metadata: ${metadata.totalChunks} chunks, ${metadata.totalSize / (1024 * 1024)} MB")

            val outputFile = File(modelsDir, modelName)
            val tempFile = File(modelsDir, "$modelName.tmp")

            // Vytvoř dočasný soubor s plnou velikostí
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(metadata.totalSize)
            }

            // Sledování progressu
            var downloadedChunks = 0
            var downloadedBytes = 0L
            var lastProgressTime = System.currentTimeMillis()
            var lastDownloadedBytes = 0L

            // Stahuj chunky
            for (chunkIndex in 0 until metadata.totalChunks) {
                val startTime = System.currentTimeMillis()

                // Zkontroluj cache
                val cachedChunk = getCachedChunk(modelName, chunkIndex)
                val chunkData = if (cachedChunk != null &&
                    verifyChunkHash(cachedChunk, metadata.chunkHashes[chunkIndex])) {
                    Log.d(TAG, "Using cached chunk $chunkIndex")
                    cachedChunk
                } else {
                    // Stáhni chunk s retry logikou
                    downloadChunkWithRetry(modelName, chunkIndex)
                }

                if (chunkData == null) {
                    Log.e(TAG, "Failed to download chunk $chunkIndex")
                    tempFile.delete()
                    return@withContext null
                }

                // Ulož chunk do cache
                saveCachedChunk(modelName, chunkIndex, chunkData)

                // Zapiš chunk do výsledného souboru
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(chunkIndex.toLong() * metadata.chunkSize)
                    raf.write(chunkData)
                }

                downloadedChunks++
                downloadedBytes += chunkData.size

                // Vypočti rychlost a ETA
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastProgressTime

                if (timeDiff > 500) { // Update každých 500ms
                    val bytesDiff = downloadedBytes - lastDownloadedBytes
                    val speed = (bytesDiff * 1000) / timeDiff // bytes per second
                    val remainingBytes = metadata.totalSize - downloadedBytes
                    val eta = if (speed > 0) remainingBytes / speed else 0

                    onProgress?.invoke(
                        DownloadProgress(
                            downloadedChunks = downloadedChunks,
                            totalChunks = metadata.totalChunks,
                            downloadedBytes = downloadedBytes,
                            totalBytes = metadata.totalSize,
                            currentSpeed = speed,
                            eta = eta
                        )
                    )

                    lastProgressTime = currentTime
                    lastDownloadedBytes = downloadedBytes
                }

                val chunkTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Chunk $chunkIndex/${ metadata.totalChunks} done in ${chunkTime}ms")
            }

            // Ověř celý soubor
            Log.d(TAG, "Verifying complete file...")
            val fileHash = calculateFileHash(tempFile)

            if (fileHash != metadata.fileHash) {
                Log.e(TAG, "File hash mismatch! Expected: ${metadata.fileHash}, Got: $fileHash")
                tempFile.delete()
                return@withContext null
            }

            // Přejmenuj na finální soubor
            if (outputFile.exists()) {
                outputFile.delete()
            }
            tempFile.renameTo(outputFile)

            Log.d(TAG, "Download completed successfully: ${outputFile.absolutePath}")
            Log.d(TAG, "Final size: ${outputFile.length() / (1024 * 1024)} MB")

            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error in chunked download", e)
            null
        }
    }

    // ========================================================================
    // DOWNLOAD JEDNOTLIVÉHO CHUNKU S RETRY
    // ========================================================================

    private suspend fun downloadChunkWithRetry(
        modelName: String,
        chunkIndex: Int,
        retryCount: Int = 0
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$serverUrl/download-chunk/$modelName/$chunkIndex")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            // Support pro ETag caching
            val cachedChunk = getCachedChunk(modelName, chunkIndex)
            if (cachedChunk != null) {
                connection.setRequestProperty("If-None-Match", "cached")
            }

            val responseCode = connection.responseCode

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val data = connection.inputStream.readBytes()
                    connection.disconnect()
                    data
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    connection.disconnect()
                    cachedChunk
                }
                else -> {
                    connection.disconnect()
                    throw Exception("HTTP $responseCode")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chunk $chunkIndex download failed (attempt ${retryCount + 1})", e)

            if (retryCount < MAX_RETRIES) {
                Thread.sleep(RETRY_DELAY_MS * (retryCount + 1))
                downloadChunkWithRetry(modelName, chunkIndex, retryCount + 1)
            } else {
                Log.e(TAG, "Chunk $chunkIndex failed after $MAX_RETRIES retries")
                null
            }
        }
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    private fun getCachedChunk(modelName: String, chunkIndex: Int): ByteArray? {
        val cacheFile = File(cacheDir, "${modelName}_chunk_$chunkIndex")
        return if (cacheFile.exists()) {
            try {
                cacheFile.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cached chunk", e)
                null
            }
        } else {
            null
        }
    }

    private fun saveCachedChunk(modelName: String, chunkIndex: Int, data: ByteArray) {
        try {
            val cacheFile = File(cacheDir, "${modelName}_chunk_$chunkIndex")
            cacheFile.writeBytes(data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache chunk", e)
        }
    }

    private fun verifyChunkHash(data: ByteArray, expectedHash: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
        return hash == expectedHash
    }

    private fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    fun getCacheSize(): Long {
        return cacheDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    // ========================================================================
    // FALLBACK: KLASICKÝ DOWNLOAD (pro modely bez chunků)
    // ========================================================================

    private suspend fun downloadModelFallback(
        modelName: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Using fallback download for: $modelName")

            val url = URL("$serverUrl/download-model/$modelName")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 60000
            connection.readTimeout = 120000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned code: ${connection.responseCode}")
                connection.disconnect()
                return@withContext null
            }

            val totalSize = connection.contentLength.toLong()
            Log.d(TAG, "Total size: ${totalSize / (1024 * 1024)} MB")

            val outputFile = File(modelsDir, modelName)
            var downloadedSize = 0L
            var lastProgressTime = System.currentTimeMillis()
            var lastDownloadedSize = 0L
            val buffer = ByteArray(8192)

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastProgressTime

                        if (timeDiff > 500) {
                            val bytesDiff = downloadedSize - lastDownloadedSize
                            val speed = (bytesDiff * 1000) / timeDiff
                            val remainingBytes = totalSize - downloadedSize
                            val eta = if (speed > 0) remainingBytes / speed else 0

                            onProgress?.invoke(
                                DownloadProgress(
                                    downloadedChunks = 0,
                                    totalChunks = 0,
                                    downloadedBytes = downloadedSize,
                                    totalBytes = totalSize,
                                    currentSpeed = speed,
                                    eta = eta
                                )
                            )

                            lastProgressTime = currentTime
                            lastDownloadedSize = downloadedSize
                        }
                    }
                }
            }

            connection.disconnect()
            Log.d(TAG, "Fallback download completed: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback download", e)
            null
        }
    }

    // ========================================================================
    // POMOCNÉ FUNKCE
    // ========================================================================

    fun isModelDownloaded(modelName: String): Boolean {
        val modelFile = File(modelsDir, modelName)
        return modelFile.exists()
    }

    fun getModelPath(modelName: String): String? {
        val modelFile = File(modelsDir, modelName)
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    fun getModelSize(modelName: String): Long {
        val modelFile = File(modelsDir, modelName)
        return if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0
    }

    fun deleteModel(modelName: String): Boolean {
        return try {
            val modelFile = File(modelsDir, modelName)
            if (modelFile.exists()) {
                modelFile.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }
}