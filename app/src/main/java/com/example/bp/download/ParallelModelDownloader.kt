package com.example.bp.download

import android.content.Context
import android.util.Log
import com.example.bp.ModelDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Vylepšený downloader s paralelním stahováním chunků
 * Fallback na původní ModelDownloader pro kompatibilitu
 */
class ParallelModelDownloader(private val context: Context) {

    private val serverUrl = "http://192.168.50.96:3000"
    private val cacheDir = File(context.cacheDir, "model_chunks")
    private val modelsDir = File(context.filesDir, "models")

    // Fallback na původní downloader
    private val fallbackDownloader = ModelDownloader(context)

    // Bandwidth monitor
    private val bandwidthMonitor = BandwidthMonitor(context)

    // Konfigurace
    private var maxParallelDownloads = 3  // Default, bude se měnit podle rychlosti
    private val maxRetries = 3
    private val retryDelayMs = 1000L

    companion object {
        private const val TAG = "ParallelDownloader"
    }

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        if (!modelsDir.exists()) modelsDir.mkdirs()
    }

    // ========================================================================
    // PUBLIC API - kompatibilní s původním ModelDownloaderem
    // ========================================================================

    suspend fun getAvailableModels(): List<ModelInfo> {
        return fallbackDownloader.getAvailableModels()
    }

    /**
     * HLAVNÍ METODA - automaticky vybere nejlepší způsob stahování
     */
    suspend fun downloadModel(
        modelName: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Zkontroluj síťové podmínky
            val networkStats = bandwidthMonitor.networkStats.value
            Log.d(TAG, "Network: ${networkStats.connectionType}, Speed: ${networkStats.averageSpeed / 1024}KB/s")

            // Získej metadata
            val metadata = getModelMetadata(modelName)

            if (metadata == null) {
                // Fallback na původní metodu
                Log.d(TAG, "No metadata, using original downloader")
                return@withContext fallbackDownloader.downloadModel(modelName, onProgress)
            }

            // Varování pro mobilní data
            if (bandwidthMonitor.shouldWarnAboutMeteredConnection(metadata.totalSize)) {
                Log.w(TAG, "Large download on metered connection!")
                // UI by mělo zobrazit varování
            }

            // Adaptuj paralelismus podle rychlosti
            maxParallelDownloads = networkStats.recommendedParallelism
            Log.d(TAG, "Using $maxParallelDownloads parallel downloads")

            // Paralelní stahování
            downloadModelParallel(modelName, metadata, onProgress)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed, trying fallback", e)
            fallbackDownloader.downloadModel(modelName, onProgress)
        }
    }

    // ========================================================================
    // PARALELNÍ STAHOVÁNÍ
    // ========================================================================

    private suspend fun downloadModelParallel(
        modelName: String,
        metadata: ModelMetadata,
        onProgress: ((DownloadProgress) -> Unit)?
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(modelsDir, modelName)
        val tempFile = File(modelsDir, "$modelName.tmp")

        // Vytvoř prázdný soubor
        RandomAccessFile(tempFile, "rw").use { it.setLength(metadata.totalSize) }

        // Progress tracking
        val downloadedChunks = AtomicInteger(0)
        val downloadedBytes = AtomicLong(0)
        val failedChunks = ConcurrentHashMap<Int, Int>()
        var lastProgressTime = System.currentTimeMillis()
        var lastDownloadedBytes = 0L

        // Semafora pro omezení paralelních stahování
        val semaphore = Semaphore(maxParallelDownloads)

        // Stáhni všechny chunky paralelně
        val jobs = (0 until metadata.totalChunks).map { chunkIndex ->
            async {
                semaphore.withPermit {
                    val startTime = System.currentTimeMillis()

                    downloadChunkWithRetry(
                        modelName,
                        chunkIndex,
                        metadata,
                        tempFile
                    )?.also { chunkSize ->
                        downloadedChunks.incrementAndGet()
                        downloadedBytes.addAndGet(chunkSize.toLong())

                        // Vypočti rychlost pro bandwidth monitor
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > 0) {
                            val speed = (chunkSize * 1000L) / elapsed
                            bandwidthMonitor.updateSpeed(speed)
                        }

                        // Update progress
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastProgressTime

                        if (timeDiff > 300) {
                            val currentBytes = downloadedBytes.get()
                            val bytesDiff = currentBytes - lastDownloadedBytes
                            val speed = if (timeDiff > 0) (bytesDiff * 1000) / timeDiff else 0
                            val remainingBytes = metadata.totalSize - currentBytes
                            val eta = if (speed > 0) remainingBytes / speed else 0

                            onProgress?.invoke(
                                DownloadProgress(
                                    downloadedChunks = downloadedChunks.get(),
                                    totalChunks = metadata.totalChunks,
                                    downloadedBytes = currentBytes,
                                    totalBytes = metadata.totalSize,
                                    currentSpeed = speed,
                                    eta = eta
                                )
                            )

                            lastProgressTime = currentTime
                            lastDownloadedBytes = currentBytes
                        }
                    } ?: run {
                        failedChunks[chunkIndex] = (failedChunks[chunkIndex] ?: 0) + 1
                        null
                    }
                }
            }
        }

        // Počkej na všechny
        jobs.awaitAll()

        // Zkontroluj chyby
        if (failedChunks.isNotEmpty()) {
            Log.e(TAG, "Failed chunks: ${failedChunks.keys}")
            tempFile.delete()
            return@withContext null
        }

        // Ověř hash
        Log.d(TAG, "Verifying integrity...")
        val fileHash = calculateFileHash(tempFile)

        if (fileHash != metadata.fileHash) {
            Log.e(TAG, "Hash mismatch!")
            tempFile.delete()
            return@withContext null
        }

        // Přejmenuj
        if (outputFile.exists()) outputFile.delete()
        tempFile.renameTo(outputFile)

        Log.d(TAG, "Download completed: ${outputFile.absolutePath}")
        outputFile
    }

    private suspend fun downloadChunkWithRetry(
        modelName: String,
        chunkIndex: Int,
        metadata: ModelMetadata,
        outputFile: File,
        retryCount: Int = 0
    ): Int? = withContext(Dispatchers.IO) {
        try {
            // Zkontroluj cache
            val cachedChunk = getCachedChunk(modelName, chunkIndex)
            val chunkData = if (cachedChunk != null &&
                verifyChunkHash(cachedChunk, metadata.chunkHashes[chunkIndex])) {
                Log.d(TAG, "Using cached chunk $chunkIndex")
                cachedChunk
            } else {
                // Stáhni chunk
                downloadChunk(modelName, chunkIndex)
            }

            if (chunkData == null) {
                throw Exception("Failed to download chunk")
            }

            // Ověř hash
            if (!verifyChunkHash(chunkData, metadata.chunkHashes[chunkIndex])) {
                Log.e(TAG, "Chunk $chunkIndex hash mismatch!")
                Log.e(TAG, "  Expected: ${metadata.chunkHashes[chunkIndex]}")
                Log.e(TAG, "  Got: ${MessageDigest.getInstance("SHA-256").digest(chunkData).joinToString("") { "%02x".format(it) }}")
                Log.e(TAG, "  Size: ${chunkData.size} bytes")

                // 🆕 Smaž vadnou cache
                val cacheFile = File(cacheDir, "${modelName}_chunk_$chunkIndex")
                if (cacheFile.exists()) {
                    cacheFile.delete()
                    Log.d(TAG, "Deleted corrupted cache for chunk $chunkIndex")
                }
                throw Exception("Chunk hash mismatch")
            }

            // Ulož do cache
            saveCachedChunk(modelName, chunkIndex, chunkData)

            // Zapiš do souboru
            synchronized(outputFile) {  // 🆕 Thread-safe zápis
                RandomAccessFile(outputFile, "rw").use { raf ->
                    raf.seek(chunkIndex.toLong() * metadata.chunkSize)
                    raf.write(chunkData)
                }
            }

            chunkData.size

        } catch (e: Exception) {
            Log.w(TAG, "Chunk $chunkIndex retry $retryCount failed: ${e.message}")

            if (retryCount < maxRetries) {
                // 🆕 Exponential backoff s jitter
                val baseDelay = retryDelayMs * (retryCount + 1)
                val jitter = (Math.random() * 500).toLong()
                delay(baseDelay + jitter)

                downloadChunkWithRetry(modelName, chunkIndex, metadata, outputFile, retryCount + 1)
            } else {
                Log.e(TAG, "Chunk $chunkIndex failed after $maxRetries retries")
                null
            }
        }
    }

    // ========================================================================
    // HELPER METODY - INTERNAL pro ProgressiveModelLoader
    // ========================================================================

    internal suspend fun getModelMetadata(modelName: String): ModelMetadata? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/model-metadata/$modelName")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
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
                Log.d(TAG, "Metadata not available for $modelName")
                null
            }
        }

    internal suspend fun downloadChunk(modelName: String, chunkIndex: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$serverUrl/download-chunk/$modelName/$chunkIndex")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 60000

                // Request GZIP compression
                connection.setRequestProperty("Accept-Encoding", "gzip")

                // 🆕 Přidej více headerů pro stability
                connection.setRequestProperty("Connection", "close")  // Avoid keep-alive issues
                connection.useCaches = false

                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Check for GZIP
                    val contentEncoding = connection.getHeaderField("Content-Encoding")
                    val isGzipped = contentEncoding?.contains("gzip") == true

                    // 🆕 Manuální GZIP dekomprese!
                    val data = if (isGzipped) {
                        // Server poslal GZIP komprimovaná data → musíme je dekomprimovat
                        val compressedData = connection.inputStream.use { stream ->
                            stream.readBytes()
                        }

                        // Dekomprimuj pomocí GZIPInputStream
                        val decompressed = java.util.zip.GZIPInputStream(
                            java.io.ByteArrayInputStream(compressedData)
                        ).use { gzipStream ->
                            gzipStream.readBytes()
                        }

                        val originalSize = connection.getHeaderField("X-Original-Size")?.toLongOrNull()
                        val compressedSize = compressedData.size.toLong()
                        if (originalSize != null && originalSize > 0) {
                            val savings = ((1 - compressedSize.toFloat() / originalSize) * 100).toInt()
                            Log.d(TAG, "Chunk $chunkIndex: saved ${savings}% bandwidth (${compressedSize / 1024}KB → ${decompressed.size / 1024}KB)")
                        }

                        decompressed
                    } else {
                        // Server poslal nekomprimovaná data
                        connection.inputStream.use { stream ->
                            stream.readBytes()
                        }
                    }

                    data
                } else {
                    Log.e(TAG, "Chunk $chunkIndex HTTP error: $responseCode")
                    null
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Chunk $chunkIndex timeout: ${e.message}")
                null
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Chunk $chunkIndex IO error: ${e.message}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Chunk $chunkIndex error: ${e.message}")
                null
            } finally {
                connection?.disconnect()
            }
        }

    internal fun getCachedChunk(modelName: String, chunkIndex: Int): ByteArray? {
        val cacheFile = File(cacheDir, "${modelName}_chunk_$chunkIndex")
        return if (cacheFile.exists()) {
            try {
                cacheFile.readBytes()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    internal fun saveCachedChunk(modelName: String, chunkIndex: Int, data: ByteArray) {
        try {
            val cacheFile = File(cacheDir, "${modelName}_chunk_$chunkIndex")
            cacheFile.writeBytes(data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache chunk", e)
        }
    }

    internal fun verifyChunkHash(data: ByteArray, expectedHash: String): Boolean {
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

    // ========================================================================
    // KOMPATIBILNÍ API s původním ModelDownloaderem
    // ========================================================================

    fun isModelDownloaded(modelName: String): Boolean {
        return fallbackDownloader.isModelDownloaded(modelName)
    }

    fun getModelPath(modelName: String): String? {
        return fallbackDownloader.getModelPath(modelName)
    }

    fun getModelSize(modelName: String): Long {
        return fallbackDownloader.getModelSize(modelName)
    }

    fun deleteModel(modelName: String): Boolean {
        return fallbackDownloader.deleteModel(modelName)
    }

    fun clearCache() {
        fallbackDownloader.clearCache()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun getCacheSize(): Long {
        return fallbackDownloader.getCacheSize() +
                cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}