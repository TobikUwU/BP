package com.example.bp

import android.content.Context
import android.util.Log
import com.example.bp.download.ModelInfo
import com.example.bp.download.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Původní ModelDownloader - zachován pro kompatibilitu
 * Pro nové projekty použijte ParallelModelDownloader nebo DownloadManager
 * Aktualizováno pro HTTP/2 support
 */
class ModelDownloader(private val context: Context) {

    // HTTP/2 server URL (HTTPS na portu 3443)
    private val serverUrl = "https://192.168.50.96:3443"

    // HTTP/2 klient
    private val httpClient = com.example.bp.download.Http2ClientManager.client

    companion object {
        private const val TAG = "ModelDownloader"
    }

    /**
     * Stáhne seznam dostupných modelů ze serveru (HTTP/2)
     */
    suspend fun getAvailableModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url("$serverUrl/models")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get models: ${response.code}")
                    return@withContext emptyList()
                }

                val responseBody = response.body?.string() ?: return@withContext emptyList()
                Log.d(TAG, "Models retrieved via ${response.protocol}")

                val jsonResponse = JSONObject(responseBody)
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
                            totalChunks = modelObj.optInt("totalChunks", 0),
                            hasPreview = modelObj.optBoolean("hasPreview", false),
                            previewName = modelObj.optString("previewName", null),
                            previewSizeInMB = modelObj.optDouble("previewSizeInMB", 0.0)
                        )
                    )
                }

                Log.d(TAG, "Parsed ${models.size} models")
                models
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting models list", e)
            emptyList()
        }
    }

    /**
     * Stáhne model ze serveru a uloží ho do interního úložiště
     * S progress reportingem pro velké soubory (HTTP/2)
     */
    suspend fun downloadModel(
        modelName: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download: $modelName")

            val request = okhttp3.Request.Builder()
                .url("$serverUrl/download-model/$modelName")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Server returned code: ${response.code}")
                    return@withContext null
                }

                val totalSize = response.body?.contentLength() ?: 0L
                Log.d(TAG, "Total size: ${totalSize / (1024 * 1024)} MB via ${response.protocol}")

                val modelsDir = File(context.filesDir, "models")
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                val outputFile = File(modelsDir, modelName)

                var downloadedSize = 0L
                var lastProgressTime = System.currentTimeMillis()
                var lastDownloadedSize = 0L
                val buffer = ByteArray(8192)

                response.body?.byteStream()?.use { input ->
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

                Log.d(TAG, "Download completed: ${outputFile.absolutePath}")
                outputFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            null
        }
    }

    /**
     * Zkontroluje, zda model již existuje v interním úložišti
     */
    fun isModelDownloaded(modelName: String): Boolean {
        val modelsDir = File(context.filesDir, "models")
        val modelFile = File(modelsDir, modelName)
        return modelFile.exists()
    }

    /**
     * Získá cestu k modelu v interním úložišti
     */
    fun getModelPath(modelName: String): String? {
        val modelsDir = File(context.filesDir, "models")
        val modelFile = File(modelsDir, modelName)
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    /**
     * Získá velikost modelu v MB
     */
    fun getModelSize(modelName: String): Long {
        val modelsDir = File(context.filesDir, "models")
        val modelFile = File(modelsDir, modelName)
        return if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0
    }

    /**
     * Smaže model z úložiště
     */
    fun deleteModel(modelName: String): Boolean {
        return try {
            val modelsDir = File(context.filesDir, "models")
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

    /**
     * Vyčistí cache
     */
    fun clearCache() {
        try {
            val cacheDir = File(context.cacheDir, "model_chunks")
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    /**
     * Získá velikost cache
     */
    fun getCacheSize(): Long {
        val cacheDir = File(context.cacheDir, "model_chunks")
        return if (cacheDir.exists()) {
            cacheDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } else {
            0
        }
    }
}