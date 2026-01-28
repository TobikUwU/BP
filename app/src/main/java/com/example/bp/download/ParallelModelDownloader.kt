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

    // HTTP/2 server URL (HTTPS na portu 3443)
    private val serverUrl = "https://192.168.50.96:3443"
    private val cacheDir = File(context.cacheDir, "model_chunks")
    private val modelsDir = File(context.filesDir, "models")

    // HTTP/2 klient
    private val httpClient = Http2ClientManager.chunkClient

    // Fallback na původní downloader
    private val fallbackDownloader = ModelDownloader(context)

    // Bandwidth monitor
    private val bandwidthMonitor = BandwidthMonitor(context)

    // Download state manager pro persistence
    private val downloadStateManager = DownloadStateManager(context)

    // Active jobs tracking pro pause/resume
    private val activeJobs = ConcurrentHashMap<String, Job>()

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
        // Zkontroluj, zda existuje uložený stav
        val existingState = downloadStateManager.getDownloadState(modelName)

        // Pokud existuje uložený stav a je platný, pokračuj
        if (existingState != null && downloadStateManager.verifyTmpFile(existingState)) {
            Log.d(TAG, "Found existing download state, resuming...")
            return@withContext downloadModelWithCheckpoint(modelName, metadata, onProgress, existingState)
        }

        // Jinak začni nový download
        val tempFile = File(modelsDir, "$modelName.tmp")

        // Uložit počáteční checkpoint
        downloadStateManager.saveDownloadState(
            modelName = modelName,
            completedChunks = emptySet(),
            totalChunks = metadata.totalChunks,
            downloadedBytes = 0,
            totalBytes = metadata.totalSize,
            isPaused = false,
            metadata = metadata,
            tmpFilePath = tempFile.absolutePath
        )

        // Použij novou metodu s checkpoint supportem
        downloadModelWithCheckpoint(modelName, metadata, onProgress, null)
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
                val request = okhttp3.Request.Builder()
                    .url("$serverUrl/model-metadata/$modelName")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Metadata request failed: ${response.code}")
                        return@withContext null
                    }

                    val responseBody = response.body?.string() ?: return@withContext null
                    val jsonResponse = JSONObject(responseBody)
                    val metadata = jsonResponse.getJSONObject("metadata")

                    val chunkHashesArray = metadata.getJSONArray("chunkHashes")
                    val chunkHashes = mutableListOf<String>()
                    for (i in 0 until chunkHashesArray.length()) {
                        chunkHashes.add(chunkHashesArray.getString(i))
                    }

                    // Log protokol pro debugging
                    Log.d(TAG, "Metadata retrieved via ${response.protocol}")

                    ModelMetadata(
                        modelName = metadata.getString("modelName"),
                        totalChunks = metadata.getInt("totalChunks"),
                        chunkSize = metadata.getInt("chunkSize"),
                        totalSize = metadata.getLong("totalSize"),
                        fileHash = metadata.getString("fileHash"),
                        chunkHashes = chunkHashes
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Metadata not available for $modelName: ${e.message}")
                null
            }
        }

    internal suspend fun downloadChunk(modelName: String, chunkIndex: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$serverUrl/download-chunk/$modelName/$chunkIndex")
                    // Požádáme o komprimovanou verzi
                    .header("Accept-Encoding", "gzip")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Chunk $chunkIndex HTTP error: ${response.code}")
                        return@withContext null
                    }

                    // Zkontroluj, jestli server poslal komprimovaná data
                    val isCompressed = response.header("X-Chunk-Compressed")?.toBoolean() ?: false
                    val originalSize = response.header("X-Original-Size")?.toLongOrNull()
                    val compressedSize = response.header("X-Compressed-Size")?.toLongOrNull()

                    val data = if (isCompressed) {
                        // Server poslal GZIP komprimovaný chunk (raw .gz soubor)
                        // Musíme ho manuálně dekomprimovat
                        val compressedBytes = response.body?.bytes() ?: return@withContext null

                        val decompressed = try {
                            java.util.zip.GZIPInputStream(
                                java.io.ByteArrayInputStream(compressedBytes)
                            ).use { gzipStream ->
                                gzipStream.readBytes()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Chunk $chunkIndex GZIP decompression failed: ${e.message}")
                            return@withContext null
                        }

                        if (originalSize != null && compressedSize != null && originalSize > 0) {
                            val savings = ((1 - compressedSize.toFloat() / originalSize) * 100).toInt()
                            Log.d(TAG, "Chunk $chunkIndex via ${response.protocol}: saved ${savings}% bandwidth (${compressedSize / 1024}KB → ${decompressed.size / 1024}KB)")
                        }

                        decompressed
                    } else {
                        // Server poslal nekomprimovaná data
                        val responseBytes = response.body?.bytes() ?: return@withContext null
                        Log.d(TAG, "Chunk $chunkIndex via ${response.protocol}: ${responseBytes.size / 1024}KB (uncompressed)")
                        responseBytes
                    }

                    data
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

    // ========================================================================
    // PAUSE/RESUME SUPPORT
    // ========================================================================

    /**
     * Pozastaví aktivní download
     */
    suspend fun pauseDownload(modelName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val job = activeJobs[modelName]
            if (job == null) {
                Log.w(TAG, "No active download for $modelName")
                return@withContext false
            }

            // Cancel job
            job.cancel()
            activeJobs.remove(modelName)

            Log.d(TAG, "Download paused: $modelName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause download", e)
            false
        }
    }

    /**
     * Pokračuje v pozastaveném downloadu
     */
    suspend fun resumeDownload(
        modelName: String,
        onProgress: ((DownloadProgress) -> Unit)?
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Načti uložený stav
            val state = downloadStateManager.getDownloadState(modelName)
            if (state == null) {
                Log.w(TAG, "No saved state for $modelName")
                return@withContext null
            }

            // Verifikuj tmp soubor
            if (!downloadStateManager.verifyTmpFile(state)) {
                Log.w(TAG, "Tmp file verification failed, starting from scratch")
                downloadStateManager.deleteDownloadState(modelName)
                return@withContext downloadModel(modelName, onProgress)
            }

            Log.d(TAG, "Resuming download: $modelName (${state.completedChunks.size}/${state.totalChunks} chunks)")

            // Pokračuj s downloadem chunků
            downloadModelWithCheckpoint(modelName, state.metadata, onProgress, state)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume download", e)
            null
        }
    }

    /**
     * Kontroluje, zda může download pokračovat
     */
    fun canResumeDownload(modelName: String): Boolean {
        val state = downloadStateManager.getDownloadState(modelName) ?: return false
        return downloadStateManager.verifyTmpFile(state)
    }

    /**
     * Zruší download a smaže všechny dočasné soubory
     */
    suspend fun cancelDownload(modelName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Cancel aktivní job
            activeJobs[modelName]?.cancel()
            activeJobs.remove(modelName)

            // Smaž tmp soubor
            val state = downloadStateManager.getDownloadState(modelName)
            state?.tmpFilePath?.let { path ->
                File(path).delete()
            }

            // Smaž cached chunky
            cacheDir.listFiles()?.filter { it.name.startsWith("${modelName}_chunk_") }?.forEach {
                it.delete()
            }

            // Smaž checkpoint
            downloadStateManager.deleteDownloadState(modelName)

            Log.d(TAG, "Download cancelled: $modelName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel download", e)
            false
        }
    }

    /**
     * Download s checkpoint supportem
     */
    private suspend fun downloadModelWithCheckpoint(
        modelName: String,
        metadata: ModelMetadata,
        onProgress: ((DownloadProgress) -> Unit)?,
        existingState: DownloadStateManager.DownloadStateData? = null
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(modelsDir, modelName)
        val tempFile = existingState?.tmpFilePath?.let { File(it) }
            ?: File(modelsDir, "$modelName.tmp")

        // Vytvoř prázdný soubor pokud neexistuje
        if (!tempFile.exists()) {
            RandomAccessFile(tempFile, "rw").use { it.setLength(metadata.totalSize) }
        }

        // Progress tracking
        val completedChunks = existingState?.completedChunks?.toMutableSet() ?: mutableSetOf()
        val downloadedChunks = AtomicInteger(completedChunks.size)
        val downloadedBytes = AtomicLong(existingState?.downloadedBytes ?: 0)
        val failedChunks = ConcurrentHashMap<Int, Int>()
        var lastProgressTime = System.currentTimeMillis()
        var lastDownloadedBytes = downloadedBytes.get()

        // Určit které chunky je potřeba stáhnout
        val chunksToDownload = (0 until metadata.totalChunks).filter { it !in completedChunks }

        Log.d(TAG, "Downloading ${chunksToDownload.size} remaining chunks (${completedChunks.size} already done)")

        // Semafora pro omezení paralelních stahování
        val semaphore = Semaphore(maxParallelDownloads)

        // Vytvoř job pro tento download
        val downloadJob = coroutineContext[Job]!!
        activeJobs[modelName] = downloadJob

        // Stáhni chybějící chunky paralelně
        val jobs = chunksToDownload.map { chunkIndex ->
            async {
                semaphore.withPermit {
                    val startTime = System.currentTimeMillis()

                    downloadChunkWithRetry(
                        modelName,
                        chunkIndex,
                        metadata,
                        tempFile
                    )?.also { chunkSize ->
                        // Označ chunk jako hotový
                        completedChunks.add(chunkIndex)
                        downloadedChunks.incrementAndGet()
                        downloadedBytes.addAndGet(chunkSize.toLong())

                        // Uložit checkpoint
                        downloadStateManager.updateCompletedChunks(modelName, chunkIndex)

                        // Vypočti rychlost
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
        try {
            jobs.awaitAll()
        } catch (e: CancellationException) {
            // Download byl pozastaven
            Log.d(TAG, "Download paused by user")
            downloadStateManager.saveDownloadState(
                modelName = modelName,
                completedChunks = completedChunks,
                totalChunks = metadata.totalChunks,
                downloadedBytes = downloadedBytes.get(),
                totalBytes = metadata.totalSize,
                isPaused = true,
                metadata = metadata,
                tmpFilePath = tempFile.absolutePath
            )
            activeJobs.remove(modelName)
            return@withContext null
        }

        // Odstraň z active jobs
        activeJobs.remove(modelName)

        // Zkontroluj chyby
        if (failedChunks.isNotEmpty()) {
            Log.e(TAG, "Failed chunks: ${failedChunks.keys}")
            downloadStateManager.saveDownloadState(
                modelName = modelName,
                completedChunks = completedChunks,
                totalChunks = metadata.totalChunks,
                downloadedBytes = downloadedBytes.get(),
                totalBytes = metadata.totalSize,
                isPaused = true,
                metadata = metadata,
                tmpFilePath = tempFile.absolutePath
            )
            return@withContext null
        }

        // Ověř hash
        Log.d(TAG, "Verifying integrity...")
        val fileHash = calculateFileHash(tempFile)

        if (fileHash != metadata.fileHash) {
            Log.e(TAG, "Hash mismatch!")
            tempFile.delete()
            downloadStateManager.deleteDownloadState(modelName)
            return@withContext null
        }

        // Přejmenuj
        if (outputFile.exists()) outputFile.delete()
        tempFile.renameTo(outputFile)

        // Smaž checkpoint
        downloadStateManager.deleteDownloadState(modelName)

        Log.d(TAG, "Download completed: ${outputFile.absolutePath}")
        outputFile
    }
}