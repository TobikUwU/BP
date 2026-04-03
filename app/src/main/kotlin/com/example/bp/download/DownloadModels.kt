package com.example.bp.download

data class ModelInfo(
    val id: String,
    val name: String,
    val createdAt: String,
    val lodCount: Int,
    val totalSizeInMB: Double,
    val previewUrl: String? = null,
    val chunked: Boolean = false,
    val totalChunks: Int = 0,
    val sizeInMB: Double = totalSizeInMB,
    val modified: String = createdAt
)

data class LodInfo(
    val id: String,
    val label: String,
    val gltf: String,
    val textureMaxSize: Int,
    val byteSize: Long
)

data class ModelManifest(
    val id: String,
    val name: String,
    val sourceFile: String,
    val createdAt: String,
    val mode: String,
    val notes: String,
    val lods: List<LodInfo>
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
