package com.example.bp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val context: Context) {

    private val serverUrl = "http://10.0.2.2:3000" // Pro emulátor (localhost)
    // Pro reálné zařízení použij IP adresu počítače, např: "http://192.168.1.100:3000"

    /**
     * Stáhne seznam dostupných modelů ze serveru
     */
    suspend fun getAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$serverUrl/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Parsování JSON pole (jednoduché, předpokládáme pole stringů)
            response.trim()
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Stáhne model ze serveru a uloží ho do interního úložiště
     * @param modelName název souboru na serveru (např. "DamagedHelmet.glb")
     * @return File objekt staženého modelu nebo null při chybě
     */
    suspend fun downloadModel(modelName: String): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$serverUrl/download-model/$modelName")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext null
            }

            // Vytvoř složku pro modely v interním úložiště
            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }

            val outputFile = File(modelsDir, modelName)

            // Stáhni soubor
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            connection.disconnect()
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
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
}