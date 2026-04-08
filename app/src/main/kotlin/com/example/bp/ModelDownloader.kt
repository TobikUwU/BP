package com.example.bp

import android.content.Context
import android.util.Log
import com.example.bp.download.DownloadProgress
import com.example.bp.download.Http2ClientManager
import com.example.bp.download.ModelInfo
import com.example.bp.download.StreamBootstrap
import com.example.bp.download.StreamManifest
import com.example.bp.download.StreamMetadata
import com.example.bp.download.StreamSession
import com.example.bp.download.StreamStage
import com.example.bp.download.StreamTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
                .url(buildUrl("models"))
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
                        add(parseModelInfo(modelsArray.getJSONObject(index)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting models list", e)
            emptyList()
        }
    }

    suspend fun getStreamBootstrap(modelName: String): StreamBootstrap? = withContext(Dispatchers.IO) {
        val local = getLocalBootstrap(modelName)
        if (local != null) {
            return@withContext local
        }

        try {
            val request = Request.Builder()
                .url(buildUrl("stream-bootstrap", modelName))
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get bootstrap for $modelName: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val payload = JSONObject(body)
                val bootstrapObject = payload.optJSONObject("bootstrap") ?: return@withContext null
                val bootstrap = parseBootstrap(modelName, bootstrapObject)
                cacheBootstrap(bootstrap)
                bootstrap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bootstrap for $modelName", e)
            null
        }
    }

    suspend fun prepareStreamSession(
        modelInfo: ModelInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): StreamSession? = withContext(Dispatchers.IO) {
        try {
            val bootstrap = getStreamBootstrap(modelInfo.name) ?: return@withContext null
            val cacheDir = getModelCacheDir(modelInfo.name)
            cacheDir.mkdirs()

            val entryStage = resolveEntryStage(bootstrap) ?: return@withContext null
            val entryFile = ensureAssetCached(
                modelInfo = modelInfo,
                assetId = entryStage.id,
                relativePath = entryStage.file,
                assetUrl = entryStage.url,
                assetSize = entryStage.size,
                cacheDir = cacheDir,
                assetIndex = 1,
                assetCount = maxOf(bootstrap.manifest.overviewStages.size, 1),
                onProgress = onProgress,
            ) ?: return@withContext null

            cacheBootstrap(bootstrap)

            StreamSession(
                model = modelInfo,
                bootstrap = bootstrap,
                cacheDirPath = cacheDir.absolutePath,
                entryFilePath = entryFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare stream session", e)
            null
        }
    }

    suspend fun ensureOverviewStageCached(
        session: StreamSession,
        stage: StreamStage,
        stageIndex: Int,
        stageCount: Int,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? {
        return ensureAssetCached(
            modelInfo = session.model,
            assetId = stage.id,
            relativePath = stage.file,
            assetUrl = stage.url,
            assetSize = stage.size,
            cacheDir = File(session.cacheDirPath),
            assetIndex = stageIndex,
            assetCount = stageCount,
            onProgress = onProgress,
        )
    }

    suspend fun ensureTileCached(
        session: StreamSession,
        tile: StreamTile,
        tileIndex: Int,
        tileCount: Int,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File? {
        return ensureAssetCached(
            modelInfo = session.model,
            assetId = tile.id,
            relativePath = tile.file,
            assetUrl = tile.url,
            assetSize = tile.size,
            cacheDir = File(session.cacheDirPath),
            assetIndex = tileIndex,
            assetCount = tileCount,
            onProgress = onProgress,
        )
    }

    fun isModelDownloaded(modelKey: String): Boolean {
        val bootstrap = getLocalBootstrap(modelKey)
        if (bootstrap != null) {
            val entryStage = resolveEntryStage(bootstrap)
            if (entryStage != null) {
                return File(getModelCacheDir(bootstrap.modelName), entryStage.file).exists()
            }
        }
        return getModelCacheDir(modelKey).exists()
    }

    fun getCachedSession(modelInfo: ModelInfo): StreamSession? {
        val bootstrap = getLocalBootstrap(modelInfo.name) ?: return null
        val entryStage = resolveEntryStage(bootstrap) ?: return null
        val cacheDir = getModelCacheDir(modelInfo.name)
        val entryFile = File(cacheDir, entryStage.file)
        if (!entryFile.exists()) {
            return null
        }

        return StreamSession(
            model = modelInfo,
            bootstrap = bootstrap,
            cacheDirPath = cacheDir.absolutePath,
            entryFilePath = entryFile.absolutePath
        )
    }

    fun getModelPath(modelKey: String): String? {
        val bootstrap = getLocalBootstrap(modelKey) ?: return null
        val entryStage = resolveEntryStage(bootstrap) ?: return null
        val entryFile = File(getModelCacheDir(bootstrap.modelName), entryStage.file)
        return entryFile.takeIf(File::exists)?.absolutePath
    }

    fun getModelSize(modelKey: String): Long {
        val cacheDir = getModelCacheDir(modelKey)
        if (!cacheDir.exists()) return 0L
        return cacheDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024 * 1024)
    }

    fun deleteModel(modelKey: String): Boolean {
        return try {
            val cacheDir = getModelCacheDir(modelKey)
            cacheDir.exists() && cacheDir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting cached model", e)
            false
        }
    }

    fun clearCache() {
        modelsDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun getCacheSize(): Long {
        return if (modelsDir.exists()) {
            modelsDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } else {
            0L
        }
    }

    private fun parseModelInfo(json: JSONObject): ModelInfo {
        val upgradeOrder = json.optJSONArray("upgradeOrder")?.toStringList().orEmpty()
        val name = json.optString("name")
        return ModelInfo(
            name = name,
            entryFile = json.optString("entryFile"),
            entryUrl = json.optString("entryUrl"),
            assetDirectory = json.optString("assetDirectory"),
            manifestUrl = json.optString("manifestUrl"),
            bootstrapUrl = json.optString("bootstrapUrl"),
            streamingStrategy = json.optString("streamingStrategy"),
            upgradeOrder = upgradeOrder,
            overviewStageCount = json.optInt("overviewStageCount", 0),
            tileCount = json.optInt("tileCount", 0),
            type = json.optString("type", "glb-stream"),
            size = json.optLong("size", 0L),
            sizeInMB = json.optDouble("sizeInMB", 0.0),
            created = json.optString("created"),
            modified = json.optString("modified"),
            chunked = json.optBoolean("chunked", false),
            totalChunks = json.optInt("totalChunks", 0),
            id = name
        )
    }

    private fun parseBootstrap(modelName: String, json: JSONObject): StreamBootstrap {
        val metadataJson = json.getJSONObject("metadata")
        val manifestJson = json.getJSONObject("manifest")

        val overviewStages = parseStages(
            metadataJson.optJSONArray("overviewStages") ?: JSONArray(),
        )

        val metadata = StreamMetadata(
            modelName = metadataJson.optString("modelName", modelName),
            type = metadataJson.optString("type", "mesh-stream-package"),
            entryFile = metadataJson.optString("entryFile"),
            entryUrl = metadataJson.optString("entryUrl"),
            assetDirectory = metadataJson.optString("assetDirectory"),
            manifestUrl = metadataJson.optString("manifestUrl"),
            bootstrapUrl = metadataJson.optString("bootstrapUrl"),
            streamingStrategy = metadataJson.optString("streamingStrategy"),
            entryStage = metadataJson.optString("entryStage"),
            upgradeOrder = metadataJson.optJSONArray("upgradeOrder")?.toStringList().orEmpty(),
            overviewStages = overviewStages,
            tileCount = metadataJson.optInt("tileCount", 0),
            size = metadataJson.optLong("size", 0L),
            sizeInMB = metadataJson.optDouble("sizeInMB", 0.0),
            created = metadataJson.optString("created")
        )

        val manifestOverview = manifestJson.optJSONObject("overview")
        val manifestStages = parseStages(
            manifestOverview?.optJSONArray("stages") ?: JSONArray(),
        )
        val manifestTiles = parseTiles(
            manifestJson.optJSONArray("tiles") ?: JSONArray(),
        )
        val bootstrapSection = manifestJson.optJSONObject("bootstrap")
        val rootTiles = manifestJson.optJSONArray("rootTiles")?.toStringList().orEmpty()
        val tileTraversalOrder = manifestJson.optJSONArray("tileTraversalOrder")?.toStringList().orEmpty()

        val manifest = StreamManifest(
            strategy = manifestJson.optString("strategy"),
            entryStage = manifestJson.optString("entryStage", metadata.entryStage),
            upgradeOrder = manifestJson.optJSONArray("upgradeOrder")?.toStringList().orEmpty(),
            firstFrameStageId = bootstrapSection?.optString("firstFrameStageId")
                ?: metadata.entryStage,
            firstFrameUrl = bootstrapSection?.optString("firstFrameUrl")
                ?: metadata.entryUrl,
            firstFrameSize = bootstrapSection?.optLong("firstFrameSize") ?: 0L,
            overviewStages = if (manifestStages.isNotEmpty()) manifestStages else overviewStages,
            tiles = manifestTiles,
            tileCount = if (manifestTiles.isNotEmpty()) {
                manifestTiles.size
            } else {
                manifestJson.optJSONArray("tiles")?.length() ?: metadata.tileCount
            },
            rootTiles = rootTiles,
            tileTraversalOrder = tileTraversalOrder,
        )

        return StreamBootstrap(
            modelName = metadata.modelName,
            metadata = metadata,
            manifest = manifest
        )
    }

    private fun parseStages(array: JSONArray): List<StreamStage> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.optString("id")
                val file = item.optString("file")
                if (id.isBlank() || file.isBlank()) {
                    continue
                }

                add(
                    StreamStage(
                        id = id,
                        file = file,
                        url = item.optString("url"),
                        size = item.optLong("size", 0L),
                        ratio = item.optDouble("ratio", 0.0),
                        error = item.optDouble("error", 0.0),
                        geometricError = item.optDouble("geometricError", 0.0),
                        triangleCount = item.optInt("triangleCount", 0),
                        primitiveCount = item.optInt("primitiveCount", 0),
                        meshNodeCount = item.optInt("meshNodeCount", 0),
                        firstFrameCandidate = item.optBoolean("firstFrameCandidate", false)
                    )
                )
            }
        }
    }

    private fun parseTiles(array: JSONArray): List<StreamTile> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.optString("id")
                val file = item.optString("file")
                if (id.isBlank() || file.isBlank()) {
                    continue
                }

                add(
                    StreamTile(
                        id = id,
                        parentId = item.optString("parentId").ifBlank { null },
                        name = item.optString("name"),
                        depth = item.optInt("depth", 0),
                        refinement = item.optString("refinement", "replace"),
                        format = item.optString("format", "glb"),
                        file = file,
                        url = item.optString("url"),
                        size = item.optLong("size", 0L),
                        ratio = item.optDouble("ratio", 0.0),
                        error = item.optDouble("error", 0.0),
                        geometricError = item.optDouble("geometricError", 0.0),
                        triangleCount = item.optInt("triangleCount", 0),
                        primitiveCount = item.optInt("primitiveCount", 0),
                        meshNodeCount = item.optInt("meshNodeCount", 0),
                        children = item.optJSONArray("children")?.toStringList().orEmpty(),
                        priority = item.optInt("priority", 0),
                        screenCoverageHint = item.optDouble("screenCoverageHint", 0.0),
                    )
                )
            }
        }
    }

    private fun resolveEntryStage(bootstrap: StreamBootstrap): StreamStage? {
        val stageMap = bootstrap.manifest.overviewStages.associateBy { it.id }
        val entryStageId = bootstrap.manifest.firstFrameStageId.ifBlank {
            bootstrap.manifest.entryStage
        }.ifBlank {
            bootstrap.metadata.entryStage
        }

        return stageMap[entryStageId]
            ?: bootstrap.manifest.overviewStages.firstOrNull { it.firstFrameCandidate }
            ?: bootstrap.manifest.overviewStages.lastOrNull()
            ?: bootstrap.metadata.overviewStages.lastOrNull()
    }

    private suspend fun ensureAssetCached(
        modelInfo: ModelInfo,
        assetId: String,
        relativePath: String,
        assetUrl: String,
        assetSize: Long,
        cacheDir: File,
        assetIndex: Int,
        assetCount: Int,
        onProgress: ((DownloadProgress) -> Unit)?
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = File(cacheDir, relativePath)
        if (outputFile.exists() && outputFile.length() > 0L) {
            return@withContext outputFile
        }

        outputFile.parentFile?.mkdirs()
        val resolvedUrl = assetUrl.ifBlank {
            if (relativePath.isNotBlank() && modelInfo.assetDirectory.isNotBlank()) {
                "${modelInfo.assetDirectory.trimEnd('/')}/${relativePath.replace('\\', '/')}"
            } else {
                modelInfo.entryUrl
            }
        }

        val totalBytes = assetSize.coerceAtLeast(1L)
        var downloadedBytes = 0L
        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L

        val request = Request.Builder()
            .url(absoluteUrl(resolvedUrl))
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download asset $assetId: ${response.code}")
                return@withContext null
            }

            response.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val now = System.currentTimeMillis()
                        val timeDiff = now - lastTime
                        if (timeDiff > 250) {
                            val byteDiff = downloadedBytes - lastBytes
                            val speed = if (timeDiff > 0) (byteDiff * 1000) / timeDiff else 0
                            val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
                            val eta = if (speed > 0) remaining / speed else 0
                            onProgress?.invoke(
                                DownloadProgress(
                                    downloadedChunks = assetIndex,
                                    totalChunks = assetCount.coerceAtLeast(1),
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    currentSpeed = speed,
                                    eta = eta
                                )
                            )
                            lastTime = now
                            lastBytes = downloadedBytes
                        }
                    }
                }
            }
        }

        onProgress?.invoke(
            DownloadProgress(
                downloadedChunks = assetIndex,
                totalChunks = assetCount.coerceAtLeast(1),
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                currentSpeed = 0,
                eta = 0
            )
        )

        outputFile
    }

    private fun getModelCacheDir(modelName: String): File {
        return File(modelsDir, modelName)
    }

    private fun getLocalBootstrap(modelName: String): StreamBootstrap? {
        return try {
            val bootstrapFile = File(getModelCacheDir(modelName), "stream-bootstrap.json")
            if (!bootstrapFile.exists()) return null
            val bootstrap = parseBootstrap(modelName, JSONObject(bootstrapFile.readText()))
            if (bootstrap.manifest.tileCount > 0 && bootstrap.manifest.tiles.isEmpty()) {
                Log.w(TAG, "Cached bootstrap for $modelName is missing tile metadata, refetching")
                null
            } else {
                bootstrap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached bootstrap for $modelName", e)
            null
        }
    }

    private fun cacheBootstrap(bootstrap: StreamBootstrap) {
        try {
            val cacheDir = getModelCacheDir(bootstrap.modelName)
            cacheDir.mkdirs()
            val bootstrapFile = File(cacheDir, "stream-bootstrap.json")
            bootstrapFile.writeText(bootstrapToJson(bootstrap).toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache bootstrap for ${bootstrap.modelName}", e)
        }
    }

    private fun bootstrapToJson(bootstrap: StreamBootstrap): JSONObject {
        return JSONObject()
            .put("strategy", bootstrap.manifest.strategy)
            .put("metadata", JSONObject()
                .put("modelName", bootstrap.metadata.modelName)
                .put("type", bootstrap.metadata.type)
                .put("entryFile", bootstrap.metadata.entryFile)
                .put("entryUrl", bootstrap.metadata.entryUrl)
                .put("assetDirectory", bootstrap.metadata.assetDirectory)
                .put("manifestUrl", bootstrap.metadata.manifestUrl)
                .put("bootstrapUrl", bootstrap.metadata.bootstrapUrl)
                .put("streamingStrategy", bootstrap.metadata.streamingStrategy)
                .put("entryStage", bootstrap.metadata.entryStage)
                .put("upgradeOrder", JSONArray(bootstrap.metadata.upgradeOrder))
                .put("overviewStages", JSONArray(bootstrap.metadata.overviewStages.map(::stageToJson)))
                .put("tileCount", bootstrap.metadata.tileCount)
                .put("size", bootstrap.metadata.size)
                .put("sizeInMB", bootstrap.metadata.sizeInMB)
                .put("created", bootstrap.metadata.created)
            )
            .put("manifest", JSONObject()
                .put("strategy", bootstrap.manifest.strategy)
                .put("entryStage", bootstrap.manifest.entryStage)
                .put("upgradeOrder", JSONArray(bootstrap.manifest.upgradeOrder))
                .put("bootstrap", JSONObject()
                    .put("firstFrameStageId", bootstrap.manifest.firstFrameStageId)
                    .put("firstFrameUrl", bootstrap.manifest.firstFrameUrl)
                    .put("firstFrameSize", bootstrap.manifest.firstFrameSize)
                )
                .put("overview", JSONObject().put("stages", JSONArray(bootstrap.manifest.overviewStages.map(::stageToJson))))
                .put("tiles", JSONArray(bootstrap.manifest.tiles.map(::tileToJson)))
                .put("rootTiles", JSONArray(bootstrap.manifest.rootTiles))
                .put("tileTraversalOrder", JSONArray(bootstrap.manifest.tileTraversalOrder))
            )
    }

    private fun stageToJson(stage: StreamStage): JSONObject {
        return JSONObject()
            .put("id", stage.id)
            .put("file", stage.file)
            .put("url", stage.url)
            .put("size", stage.size)
            .put("ratio", stage.ratio)
            .put("error", stage.error)
            .put("geometricError", stage.geometricError)
            .put("triangleCount", stage.triangleCount)
            .put("primitiveCount", stage.primitiveCount)
            .put("meshNodeCount", stage.meshNodeCount)
            .put("firstFrameCandidate", stage.firstFrameCandidate)
    }

    private fun tileToJson(tile: StreamTile): JSONObject {
        return JSONObject()
            .put("id", tile.id)
            .put("parentId", tile.parentId)
            .put("name", tile.name)
            .put("depth", tile.depth)
            .put("refinement", tile.refinement)
            .put("format", tile.format)
            .put("file", tile.file)
            .put("url", tile.url)
            .put("size", tile.size)
            .put("ratio", tile.ratio)
            .put("error", tile.error)
            .put("geometricError", tile.geometricError)
            .put("triangleCount", tile.triangleCount)
            .put("primitiveCount", tile.primitiveCount)
            .put("meshNodeCount", tile.meshNodeCount)
            .put("children", JSONArray(tile.children))
            .put("priority", tile.priority)
            .put("screenCoverageHint", tile.screenCoverageHint)
    }

    private fun buildUrl(vararg segments: String): String {
        val builder = serverUrl.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    private fun absoluteUrl(path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            buildUrl(*path.trimStart('/').split('/').filter { it.isNotBlank() }.toTypedArray())
        }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }
}
