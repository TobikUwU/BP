package com.example.bp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelInfo(
    val name: String,
    val sizeInMB: Double,
    val modified: String
)

class ModelDownloader(private val context: Context) {

    private val serverUrl = "http://192.168.50.96:3000" // Pro emulátor (localhost)
    // Pro reálné zařízení použij IP adresu počítače, např: "http://192.168.1.100:3000"

    companion object {
        private const val TAG = "ModelDownloader"
    }

    /**
     * Stáhne seznam dostupných modelů ze serveru
     */
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

            // Parsuj JSON odpověď
            val jsonResponse = JSONObject(response)
            val modelsArray = jsonResponse.getJSONArray("models")

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until modelsArray.length()) {
                val modelObj = modelsArray.getJSONObject(i)
                models.add(
                    ModelInfo(
                        name = modelObj.getString("name"),
                        sizeInMB = modelObj.getDouble("sizeInMB"),
                        modified = modelObj.getString("modified")
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

    /**
     * Stáhne model ze serveru a uloží ho do interního úložiště
     * S progress reportingem pro velké soubory
     */
    suspend fun downloadModel(
        modelName: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download: $modelName")

            val url = URL("$serverUrl/download-model/$modelName")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 60000  // 60 sekund pro velké soubory
            connection.readTimeout = 120000    // 2 minuty pro velké soubory

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned code: ${connection.responseCode}")
                connection.disconnect()
                return@withContext null
            }

            val totalSize = connection.contentLength.toLong()
            Log.d(TAG, "Total size: ${totalSize / (1024 * 1024)} MB")

            // Vytvoř složku pro modely
            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }

            val outputFile = File(modelsDir, modelName)

            // Stáhni soubor s progress tracking
            var downloadedSize = 0L
            val buffer = ByteArray(8192) // 8KB buffer

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        // Report progress
                        onProgress?.invoke(downloadedSize, totalSize)

                        if (downloadedSize % (10 * 1024 * 1024) == 0L) { // Log každých 10 MB
                            Log.d(TAG, "Downloaded: ${downloadedSize / (1024 * 1024)} MB / ${totalSize / (1024 * 1024)} MB")
                        }
                    }
                }
            }

            connection.disconnect()
            Log.d(TAG, "Download completed: ${outputFile.absolutePath}")
            Log.d(TAG, "File size: ${outputFile.length() / (1024 * 1024)} MB")

            outputFile
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
}