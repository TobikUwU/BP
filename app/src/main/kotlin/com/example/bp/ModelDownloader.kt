package com.example.bp

import android.content.Context
import android.util.Log
import com.example.bp.download.DownloadProgress
import com.example.bp.download.Http2ClientManager
import com.example.bp.download.LodInfo
import com.example.bp.download.ModelInfo
import com.example.bp.download.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL

class ModelDownloader(private val context: Context) {

    private val serverUrl = "https://192.168.50.96:3443"
    private val httpClient = Http2ClientManager.client
    private val modelsDir = File(context.filesDir, "models")

    companion object {
        private const val TAG = "ModelDownloader"
    }

    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    suspend fun getAvailableModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/api/models")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get models: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val root = JSONObject(body)
                val modelsArray = root.optJSONArray("models") ?: return@withContext emptyList()

                buildList {
                    for (index in 0 until modelsArray.length()) {
                        val item = modelsArray.getJSONObject(index)
                        add(
                            ModelInfo(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                createdAt = item.optString("createdAt"),
                                lodCount = item.optInt("lodCount", 0),
                                totalSizeInMB = item.optDouble("totalSizeInMB", 0.0),
                                previewUrl = item.optString("previewUrl").ifBlank { null }
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting models list", e)
            emptyList()
        }
    }

    suspend fun getManifest(modelId: String): ModelManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/api/models/$modelId/manifest")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get manifest for $modelId: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                parseManifest(JSONObject(body).getJSONObject("model"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading manifest for $modelId", e)
            null
        }
    }

    suspend fun downloadModel(
        modelInfo: ModelInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? = downloadModel(modelInfo.id, onProgress)

    suspend fun downloadModel(
        modelKey: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            val modelInfo = resolveModel(modelKey) ?: return@withContext null
            val manifest = getManifest(modelInfo.id) ?: return@withContext null
            val packageDir = File(modelsDir, manifest.id)
            if (!packageDir.exists()) {
                packageDir.mkdirs()
            }

            val totalBytes = manifest.lods.sumOf { it.byteSize }.coerceAtLeast(1L)
            var downloadedBytes = 0L
            var lastProgressTime = System.currentTimeMillis()
            var lastDownloadedBytes = 0L

            writeText(File(packageDir, "manifest.json"), manifestToJson(manifest).toString(2))

            for ((lodIndex, lod) in manifest.lods.withIndex()) {
                val baseUrl = absoluteUrl(lod.gltf)
                val lodDir = File(packageDir, lod.id)
                lodDir.mkdirs()

                val sceneBytes = downloadBytes(baseUrl) { delta ->
                    val now = System.currentTimeMillis()
                    downloadedBytes += delta
                    val timeDiff = now - lastProgressTime
                    if (timeDiff > 300) {
                        val byteDiff = downloadedBytes - lastDownloadedBytes
                        val speed = if (timeDiff > 0) (byteDiff * 1000) / timeDiff else 0
                        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0)
                        val eta = if (speed > 0) remaining / speed else 0
                        onProgress?.invoke(
                            DownloadProgress(
                                downloadedChunks = lodIndex,
                                totalChunks = manifest.lods.size,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                currentSpeed = speed,
                                eta = eta
                            )
                        )
                        lastProgressTime = now
                        lastDownloadedBytes = downloadedBytes
                    }
                } ?: return@withContext null

                val sceneFile = File(lodDir, "scene.gltf")
                sceneFile.writeBytes(sceneBytes)
                val sceneJson = JSONObject(sceneBytes.toString(Charsets.UTF_8))

                val resourceUris = linkedSetOf<String>()
                val buffers = sceneJson.optJSONArray("buffers")
                if (buffers != null) {
                    for (i in 0 until buffers.length()) {
                        buffers.optJSONObject(i)?.optString("uri")?.takeIf { it.isNotBlank() }?.let(resourceUris::add)
                    }
                }
                val images = sceneJson.optJSONArray("images")
                if (images != null) {
                    for (i in 0 until images.length()) {
                        images.optJSONObject(i)?.optString("uri")?.takeIf { it.isNotBlank() }?.let(resourceUris::add)
                    }
                }

                for (resourceUri in resourceUris) {
                    val resourceUrl = URL(URL(baseUrl), resourceUri).toString()
                    val resourceBytes = downloadBytes(resourceUrl) { delta ->
                        val now = System.currentTimeMillis()
                        downloadedBytes += delta
                        val timeDiff = now - lastProgressTime
                        if (timeDiff > 300) {
                            val byteDiff = downloadedBytes - lastDownloadedBytes
                            val speed = if (timeDiff > 0) (byteDiff * 1000) / timeDiff else 0
                            val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0)
                            val eta = if (speed > 0) remaining / speed else 0
                            onProgress?.invoke(
                                DownloadProgress(
                                    downloadedChunks = lodIndex,
                                    totalChunks = manifest.lods.size,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    currentSpeed = speed,
                                    eta = eta
                                )
                            )
                            lastProgressTime = now
                            lastDownloadedBytes = downloadedBytes
                        }
                    } ?: return@withContext null

                    val resourceFile = File(lodDir, resourceUri)
                    resourceFile.parentFile?.mkdirs()
                    resourceFile.writeBytes(resourceBytes)
                }
            }

            onProgress?.invoke(
                DownloadProgress(
                    downloadedChunks = manifest.lods.size,
                    totalChunks = manifest.lods.size,
                    downloadedBytes = totalBytes,
                    totalBytes = totalBytes,
                    currentSpeed = 0,
                    eta = 0
                )
            )

            getModelPath(manifest.id)?.let(::File)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model package", e)
            null
        }
    }

    fun isModelDownloaded(modelKey: String): Boolean {
        return getModelPath(modelKey) != null
    }

    fun getModelPath(modelKey: String): String? {
        val packageDir = resolveLocalModelDir(modelKey) ?: return null
        val lodOrder = listOf("full", "standard", "preview")
        for (lodId in lodOrder) {
            val sceneFile = File(packageDir, "$lodId/scene.gltf")
            if (sceneFile.exists()) {
                return sceneFile.absolutePath
            }
        }
        return null
    }

    fun getModelSize(modelKey: String): Long {
        val packageDir = resolveLocalModelDir(modelKey) ?: return 0
        return packageDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024 * 1024)
    }

    fun deleteModel(modelKey: String): Boolean {
        return try {
            val packageDir = resolveLocalModelDir(modelKey) ?: return false
            packageDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }

    fun clearCache() {
        Log.d(TAG, "No separate cache for manifest packages")
    }

    fun getCacheSize(): Long = 0L

    private suspend fun resolveModel(modelKey: String): ModelInfo? {
        getLocalManifest(modelKey)?.let {
            return ModelInfo(
                id = it.id,
                name = it.name,
                createdAt = it.createdAt,
                lodCount = it.lods.size,
                totalSizeInMB = it.lods.sumOf { lod -> lod.byteSize }.toDouble() / (1024.0 * 1024.0),
                previewUrl = it.lods.firstOrNull()?.gltf
            )
        }

        return getAvailableModels().firstOrNull { it.id == modelKey || it.name == modelKey }
    }

    private fun resolveLocalModelDir(modelKey: String): File? {
        val direct = File(modelsDir, modelKey)
        if (direct.exists()) {
            return direct
        }

        return modelsDir.listFiles()?.firstOrNull { dir ->
            val manifest = runCatching { getLocalManifest(dir.name) }.getOrNull()
            manifest?.name == modelKey
        }
    }

    private fun getLocalManifest(modelKey: String): ModelManifest? {
        val packageDir = File(modelsDir, modelKey)
        val manifestFile = File(packageDir, "manifest.json")
        if (!manifestFile.exists()) {
            return null
        }
        return parseManifest(JSONObject(manifestFile.readText()))
    }

    private fun parseManifest(json: JSONObject): ModelManifest {
        val lodsJson = json.getJSONArray("lods")
        val lods = buildList {
            for (index in 0 until lodsJson.length()) {
                val item = lodsJson.getJSONObject(index)
                add(
                    LodInfo(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        gltf = item.getString("gltf"),
                        textureMaxSize = item.optInt("textureMaxSize", 0),
                        byteSize = item.optLong("byteSize", 0L)
                    )
                )
            }
        }

        return ModelManifest(
            id = json.getString("id"),
            name = json.getString("name"),
            sourceFile = json.optString("sourceFile"),
            createdAt = json.optString("createdAt"),
            mode = json.optString("mode"),
            notes = json.optString("notes"),
            lods = lods
        )
    }

    private fun manifestToJson(manifest: ModelManifest): JSONObject {
        val lodsJson = org.json.JSONArray()
        manifest.lods.forEach { lod ->
            lodsJson.put(
                JSONObject()
                    .put("id", lod.id)
                    .put("label", lod.label)
                    .put("gltf", lod.gltf)
                    .put("textureMaxSize", lod.textureMaxSize)
                    .put("byteSize", lod.byteSize)
            )
        }

        return JSONObject()
            .put("id", manifest.id)
            .put("name", manifest.name)
            .put("sourceFile", manifest.sourceFile)
            .put("createdAt", manifest.createdAt)
            .put("mode", manifest.mode)
            .put("notes", manifest.notes)
            .put("lods", lodsJson)
    }

    private fun absoluteUrl(path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) path else "$serverUrl$path"
    }

    private suspend fun downloadBytes(url: String, onDelta: (Long) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download $url: ${response.code}")
                return@withContext null
            }

            val stream = response.body?.byteStream() ?: return@withContext null
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                onDelta(read.toLong())
            }
            output.toByteArray()
        }
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
