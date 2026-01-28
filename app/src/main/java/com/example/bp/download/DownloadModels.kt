package com.example.bp.download

data class ModelInfo(
    val name: String,
    val sizeInMB: Double,
    val modified: String,
    val chunked: Boolean = false,
    val totalChunks: Int = 0
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