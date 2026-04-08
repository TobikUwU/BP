package com.example.bp.download

data class ModelInfo(
    val name: String = "",
    val entryFile: String = "",
    val entryUrl: String = "",
    val assetDirectory: String = "",
    val manifestUrl: String = "",
    val bootstrapUrl: String = "",
    val streamingStrategy: String = "",
    val upgradeOrder: List<String> = emptyList(),
    val overviewStageCount: Int = 0,
    val tileCount: Int = 0,
    val type: String = "glb-stream",
    val size: Long = 0L,
    val sizeInMB: Double = 0.0,
    val created: String = "",
    val modified: String = "",
    val chunked: Boolean = false,
    val totalChunks: Int = 0,
    val id: String = name,
    val totalSizeInMB: Double = sizeInMB,
    val lodCount: Int = overviewStageCount
)

data class StreamStage(
    val id: String,
    val file: String,
    val url: String,
    val size: Long,
    val ratio: Double,
    val error: Double,
    val geometricError: Double,
    val triangleCount: Int,
    val primitiveCount: Int,
    val meshNodeCount: Int,
    val firstFrameCandidate: Boolean = false
)

data class StreamTile(
    val id: String,
    val parentId: String? = null,
    val name: String = "",
    val depth: Int = 0,
    val refinement: String = "replace",
    val format: String = "glb",
    val file: String,
    val url: String,
    val size: Long,
    val ratio: Double,
    val error: Double,
    val geometricError: Double,
    val triangleCount: Int = 0,
    val primitiveCount: Int = 0,
    val meshNodeCount: Int = 0,
    val children: List<String> = emptyList(),
    val priority: Int = 0,
    val screenCoverageHint: Double = 0.0
)

data class StreamMetadata(
    val modelName: String,
    val type: String,
    val entryFile: String,
    val entryUrl: String,
    val assetDirectory: String,
    val manifestUrl: String,
    val bootstrapUrl: String,
    val streamingStrategy: String,
    val entryStage: String,
    val upgradeOrder: List<String>,
    val overviewStages: List<StreamStage>,
    val tileCount: Int,
    val size: Long,
    val sizeInMB: Double,
    val created: String
)

data class StreamManifest(
    val strategy: String,
    val entryStage: String,
    val upgradeOrder: List<String>,
    val firstFrameStageId: String,
    val firstFrameUrl: String,
    val firstFrameSize: Long,
    val overviewStages: List<StreamStage>,
    val tiles: List<StreamTile>,
    val tileCount: Int,
    val rootTiles: List<String>,
    val tileTraversalOrder: List<String>
)

data class StreamBootstrap(
    val modelName: String,
    val metadata: StreamMetadata,
    val manifest: StreamManifest
)

data class StreamSession(
    val model: ModelInfo,
    val bootstrap: StreamBootstrap,
    val cacheDirPath: String,
    val entryFilePath: String
)

data class DownloadProgress(
    val downloadedChunks: Int,
    val totalChunks: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentSpeed: Long,
    val eta: Long
)

data class ModelMetadata(
    val modelName: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val totalSize: Long,
    val fileHash: String,
    val chunkHashes: List<String>
)
