package com.example.bp.download

/**
 * Společné datové třídy pro download systém
 */

/**
 * Informace o modelu na serveru
 */
data class ModelInfo(
    val name: String,
    val sizeInMB: Double,
    val modified: String,
    val chunked: Boolean = false,
    val totalChunks: Int = 0
)

/**
 * Progress stahování s detailními informacemi
 */
data class DownloadProgress(
    val downloadedChunks: Int,
    val totalChunks: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentSpeed: Long,      // bytes per second
    val eta: Long                 // estimated time in seconds
)

/**
 * Metadata modelu pro chunked download
 */
data class ModelMetadata(
    val modelName: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val totalSize: Long,
    val fileHash: String,
    val chunkHashes: List<String>
)