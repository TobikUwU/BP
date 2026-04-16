package com.example.bp

import android.content.Context
import android.util.Log
import com.example.bp.download.DownloadProgress
import com.example.bp.download.Http2ClientManager
import com.example.bp.download.ModelInfo
import com.example.bp.download.StreamBootstrap
import com.example.bp.download.StreamBounds
import com.example.bp.download.StreamClientBudgets
import com.example.bp.download.StreamManifest
import com.example.bp.download.StreamMetadata
import com.example.bp.download.StreamSession
import com.example.bp.download.StreamStage
import com.example.bp.download.StreamTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModelDownloader(private val context: Context) {

    private val serverUrl = BuildConfig.SERVER_BASE_URL
    private val httpClient = Http2ClientManager.client
    private val modelsDir = File(context.cacheDir, "stream-models")

    companion object {
        private const val TAG = "ModelDownloader"
        private const val DEFAULT_MAX_CACHE_BYTES = 800L * 1024L * 1024L
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

            executeRequest(request).use { response ->
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
            markModelAccessed(local.modelName)
            return@withContext local
        }

        try {
            val request = Request.Builder()
                .url(buildUrl("stream-bootstrap", modelName))
                .get()
                .build()

            executeRequest(request).use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get bootstrap for $modelName: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val payload = JSONObject(body)
                val bootstrapObject = payload.optJSONObject("bootstrap") ?: return@withContext null
                val bootstrap = parseBootstrap(modelName, bootstrapObject)
                cacheBootstrap(bootstrap)
                markModelAccessed(bootstrap.modelName)
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
            markModelAccessed(modelInfo.name)
            val resolvedModelInfo = mergeModelInfo(modelInfo, bootstrap)

            val entryStage = resolveEntryStage(bootstrap) ?: return@withContext null
            val entryFile = ensureAssetCached(
                modelInfo = resolvedModelInfo,
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
            enforceCacheBudget(preserveModelName = modelInfo.name)

            StreamSession(
                model = resolvedModelInfo,
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
        return false
    }

    fun getCachedSession(modelInfo: ModelInfo): StreamSession? {
        val bootstrap = getLocalBootstrap(modelInfo.name) ?: return null
        val entryStage = resolveEntryStage(bootstrap) ?: return null
        val cacheDir = getModelCacheDir(modelInfo.name)
        val entryFile = File(cacheDir, entryStage.file)
        if (!entryFile.exists()) {
            return null
        }
        markModelAccessed(modelInfo.name)

        return StreamSession(
            model = mergeModelInfo(modelInfo, bootstrap),
            bootstrap = bootstrap,
            cacheDirPath = cacheDir.absolutePath,
            entryFilePath = entryFile.absolutePath
        )
    }

    fun clearCache(): Boolean {
        return try {
            if (modelsDir.exists()) {
                modelsDir.deleteRecursively()
            } else {
                true
            }.also {
                if (it) {
                    modelsDir.mkdirs()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
            false
        }
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
            entryFile = normalizeServerPath(json.optString("entryFile")),
            entryUrl = normalizeServerPath(json.optString("entryUrl")),
            assetDirectory = normalizeServerPath(json.optString("assetDirectory")),
            manifestUrl = normalizeServerPath(json.optString("manifestUrl")),
            bootstrapUrl = normalizeServerPath(json.optString("bootstrapUrl")),
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
            entryFile = normalizeServerPath(metadataJson.optString("entryFile")),
            entryUrl = normalizeServerPath(metadataJson.optString("entryUrl")),
            assetDirectory = normalizeServerPath(metadataJson.optString("assetDirectory")),
            manifestUrl = normalizeServerPath(metadataJson.optString("manifestUrl")),
            bootstrapUrl = normalizeServerPath(metadataJson.optString("bootstrapUrl")),
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
        val clientBudgets = parseClientBudgets(manifestJson.optJSONObject("clientBudgets"))
        val bootstrapSection = manifestJson.optJSONObject("bootstrap")
        val rootTiles = manifestJson.optJSONArray("rootTiles")?.toStringList().orEmpty()
        val tileTraversalOrder = manifestJson.optJSONArray("tileTraversalOrder")?.toStringList().orEmpty()

        val manifest = StreamManifest(
            strategy = manifestJson.optString("strategy"),
            entryStage = manifestJson.optString("entryStage", metadata.entryStage),
            upgradeOrder = manifestJson.optJSONArray("upgradeOrder")?.toStringList().orEmpty(),
            firstFrameStageId = bootstrapSection?.optString("firstFrameStageId")
                ?: metadata.entryStage,
            firstFrameUrl = normalizeServerPath(
                bootstrapSection?.optString("firstFrameUrl") ?: metadata.entryUrl,
            ),
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
            clientBudgets = clientBudgets,
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
                        file = normalizeServerPath(file),
                        url = normalizeServerPath(item.optString("url")),
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
                        parentId = normalizeOptionalId(item.opt("parentId")?.toString()),
                        name = item.optString("name"),
                        depth = item.optInt("depth", 0),
                        refinement = item.optString("refinement", "replace"),
                        format = item.optString("format", "glb"),
                        file = normalizeServerPath(file),
                        url = normalizeServerPath(item.optString("url")),
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
                        bounds = parseBounds(item.optJSONObject("bounds")),
                    )
                )
            }
        }
    }

    private fun normalizeOptionalId(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        return normalized.takeUnless {
            it.isEmpty() || it.equals("null", ignoreCase = true)
        }
    }

    private fun parseClientBudgets(json: JSONObject?): StreamClientBudgets {
        if (json == null) {
            return StreamClientBudgets()
        }

        return StreamClientBudgets(
            recommendedMaxResidentOverviewStages = json.optInt("recommendedMaxResidentOverviewStages", 1)
                .coerceAtLeast(1),
            recommendedMaxActiveTiles = json.optInt("recommendedMaxActiveTiles", 24)
                .coerceAtLeast(4),
            recommendedConcurrentTileRequests = json.optInt("recommendedConcurrentTileRequests", 2)
                .coerceIn(1, 6),
        )
    }

    private fun parseBounds(json: JSONObject?): StreamBounds? {
        if (json == null) {
            return null
        }

        val center = json.optJSONArray("center")?.toDoubleList().orEmpty()
        val radius = json.optDouble("radius", 0.0)
        if (center.size < 3 || radius <= 0.0) {
            return null
        }

        return StreamBounds(
            min = json.optJSONArray("min")?.toDoubleList().orEmpty(),
            max = json.optJSONArray("max")?.toDoubleList().orEmpty(),
            center = center,
            radius = radius,
        )
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

    private fun mergeModelInfo(modelInfo: ModelInfo, bootstrap: StreamBootstrap): ModelInfo {
        val metadata = bootstrap.metadata
        val manifest = bootstrap.manifest

        return modelInfo.copy(
            name = metadata.modelName.ifBlank { modelInfo.name },
            entryFile = metadata.entryFile.ifBlank { modelInfo.entryFile },
            entryUrl = metadata.entryUrl.ifBlank { modelInfo.entryUrl },
            assetDirectory = metadata.assetDirectory.ifBlank { modelInfo.assetDirectory },
            manifestUrl = metadata.manifestUrl.ifBlank { modelInfo.manifestUrl },
            bootstrapUrl = metadata.bootstrapUrl.ifBlank { modelInfo.bootstrapUrl },
            streamingStrategy = metadata.streamingStrategy.ifBlank { modelInfo.streamingStrategy },
            upgradeOrder = manifest.upgradeOrder.ifEmpty {
                metadata.upgradeOrder.ifEmpty { modelInfo.upgradeOrder }
            },
            overviewStageCount = manifest.overviewStages.size.takeIf { it > 0 }
                ?: modelInfo.overviewStageCount,
            tileCount = manifest.tileCount.takeIf { it > 0 } ?: modelInfo.tileCount,
            type = metadata.type.ifBlank { modelInfo.type },
            size = metadata.size.takeIf { it > 0 } ?: modelInfo.size,
            sizeInMB = metadata.sizeInMB.takeIf { it > 0.0 } ?: modelInfo.sizeInMB,
            created = metadata.created.ifBlank { modelInfo.created },
        )
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
            markModelAccessed(modelInfo.name)
            return@withContext outputFile
        }

        outputFile.parentFile?.mkdirs()

        try {
            val resolvedUrl = resolveAssetUrl(
                modelInfo = modelInfo,
                assetUrl = assetUrl,
                relativePath = relativePath,
            )
            Log.d(TAG, "Caching asset $assetId from $resolvedUrl")

            val totalBytes = assetSize.coerceAtLeast(1L)
            var downloadedBytes = 0L
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L

            val request = Request.Builder()
                .url(absoluteUrl(resolvedUrl))
                .get()
                .build()

            executeRequest(request).use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download asset $assetId: ${response.code}")
                    return@withContext null
                }

                response.body?.byteStream()?.use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
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
            }

            markModelAccessed(modelInfo.name)
            enforceCacheBudget(preserveModelName = modelInfo.name)
            outputFile
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to cache asset $assetId", e)
            null
        }
    }

    private fun getModelCacheDir(modelName: String): File {
        return File(modelsDir, modelName)
    }

    private fun getLocalBootstrap(modelName: String): StreamBootstrap? {
        val cacheDir = getModelCacheDir(modelName)
        return try {
            val bootstrapFile = File(cacheDir, "stream-bootstrap.json")
            if (!bootstrapFile.exists()) return null
            if (bootstrapFile.length() == 0L) {
                cacheDir.deleteRecursively()
                return null
            }
            val bootstrap = parseBootstrap(modelName, JSONObject(bootstrapFile.readText()))
            if (bootstrap.manifest.tileCount > 0 && bootstrap.manifest.tiles.isEmpty()) {
                Log.w(TAG, "Cached bootstrap for $modelName is missing tile metadata, refetching")
                cacheDir.deleteRecursively()
                null
            } else {
                bootstrap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached bootstrap for $modelName", e)
            cacheDir.deleteRecursively()
            null
        }
    }

    private fun cacheBootstrap(bootstrap: StreamBootstrap) {
        try {
            val cacheDir = getModelCacheDir(bootstrap.modelName)
            cacheDir.mkdirs()
            val bootstrapFile = File(cacheDir, "stream-bootstrap.json")
            bootstrapFile.writeText(bootstrapToJson(bootstrap).toString(2))
            markModelAccessed(bootstrap.modelName)
            enforceCacheBudget(preserveModelName = bootstrap.modelName)
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
                .put(
                    "clientBudgets",
                    JSONObject()
                        .put(
                            "recommendedMaxResidentOverviewStages",
                            bootstrap.manifest.clientBudgets.recommendedMaxResidentOverviewStages,
                        )
                        .put(
                            "recommendedMaxActiveTiles",
                            bootstrap.manifest.clientBudgets.recommendedMaxActiveTiles,
                        )
                        .put(
                            "recommendedConcurrentTileRequests",
                            bootstrap.manifest.clientBudgets.recommendedConcurrentTileRequests,
                        ),
                )
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
            .put(
                "bounds",
                tile.bounds?.let {
                    JSONObject()
                        .put("min", JSONArray(it.min))
                        .put("max", JSONArray(it.max))
                        .put("center", JSONArray(it.center))
                        .put("radius", it.radius)
                },
            )
    }

    private suspend fun executeRequest(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }

    private fun markModelAccessed(modelName: String) {
        val dir = getModelCacheDir(modelName)
        if (!dir.exists()) {
            return
        }
        val now = System.currentTimeMillis()
        dir.setLastModified(now)
        File(dir, "stream-bootstrap.json").takeIf { it.exists() }?.setLastModified(now)
    }

    private fun enforceCacheBudget(
        preserveModelName: String? = null,
        maxBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    ) {
        if (!modelsDir.exists()) {
            return
        }

        var totalSize = getCacheSize()
        if (totalSize <= maxBytes) {
            return
        }

        val candidates = modelsDir.listFiles()
            ?.filter { it.isDirectory && it.name != preserveModelName }
            ?.map { dir ->
                CachedModelEntry(
                    name = dir.name,
                    directory = dir,
                    size = directorySize(dir),
                    lastAccess = directoryLastAccess(dir),
                )
            }
            ?.sortedBy { it.lastAccess }
            .orEmpty()

        for (candidate in candidates) {
            if (totalSize <= maxBytes) {
                break
            }
            if (candidate.directory.deleteRecursively()) {
                totalSize -= candidate.size
                Log.i(TAG, "Evicted cached model ${candidate.name} (${candidate.size} B)")
            }
        }
    }

    private fun directorySize(directory: File): Long {
        return directory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun directoryLastAccess(directory: File): Long {
        val dirModified = directory.lastModified()
        val newestFile = directory.walkTopDown()
            .filter { it.isFile }
            .map { it.lastModified() }
            .maxOrNull() ?: 0L
        return maxOf(dirModified, newestFile)
    }

    private data class CachedModelEntry(
        val name: String,
        val directory: File,
        val size: Long,
        val lastAccess: Long,
    )

    private fun buildUrl(vararg segments: String): String {
        val builder = serverUrl.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    private fun normalizeServerPath(path: String): String {
        return path.replace('\\', '/')
    }

    private fun resolveAssetUrl(
        modelInfo: ModelInfo,
        assetUrl: String,
        relativePath: String,
    ): String {
        val normalizedAssetUrl = normalizeServerPath(assetUrl).trim()
        val normalizedRelativePath = normalizeServerPath(relativePath).trim().trimStart('/')
        val assetDirectory = normalizeServerPath(modelInfo.assetDirectory).trim()
        val entryDirectory = directoryUrlOrPath(modelInfo.entryUrl)

        return when {
            normalizedAssetUrl.startsWith("http://") || normalizedAssetUrl.startsWith("https://") -> {
                normalizedAssetUrl
            }
            normalizedAssetUrl.startsWith("/") -> {
                absoluteUrl(normalizedAssetUrl)
            }
            normalizedAssetUrl.isNotBlank() && assetDirectory.isNotBlank() -> {
                absoluteUrl(joinServerPaths(assetDirectory, normalizedAssetUrl))
            }
            normalizedAssetUrl.isNotBlank() && entryDirectory.isNotBlank() -> {
                absoluteUrl(joinServerPaths(entryDirectory, normalizedAssetUrl))
            }
            normalizedAssetUrl.isNotBlank() -> {
                absoluteUrl(normalizedAssetUrl)
            }
            normalizedRelativePath.isNotBlank() && assetDirectory.isNotBlank() -> {
                absoluteUrl(joinServerPaths(assetDirectory, normalizedRelativePath))
            }
            normalizedRelativePath.isNotBlank() && entryDirectory.isNotBlank() -> {
                absoluteUrl(joinServerPaths(entryDirectory, normalizedRelativePath))
            }
            else -> {
                absoluteUrl(modelInfo.entryUrl)
            }
        }
    }

    private fun directoryUrlOrPath(location: String): String {
        val normalized = normalizeServerPath(location).trim()
        if (normalized.isBlank()) return ""
        if (normalized.endsWith("/")) return normalized.trimEnd('/')
        val separatorIndex = normalized.lastIndexOf('/')
        return if (separatorIndex >= 0) {
            normalized.substring(0, separatorIndex)
        } else {
            ""
        }
    }

    private fun joinServerPaths(base: String, child: String): String {
        val normalizedBase = normalizeServerPath(base).trim().trimEnd('/')
        val normalizedChild = normalizeServerPath(child).trim().trimStart('/')
        return when {
            normalizedBase.isBlank() -> normalizedChild
            normalizedChild.isBlank() -> normalizedBase
            else -> "$normalizedBase/$normalizedChild"
        }
    }

    private fun absoluteUrl(path: String): String {
        val normalizedPath = normalizeServerPath(path)
        return if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            normalizedPath
        } else {
            buildUrl(*normalizedPath.trimStart('/').split('/').filter { it.isNotBlank() }.toTypedArray())
        }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }

    private fun JSONArray.toDoubleList(): List<Double> = buildList {
        for (index in 0 until length()) {
            add(optDouble(index))
        }
    }
}
