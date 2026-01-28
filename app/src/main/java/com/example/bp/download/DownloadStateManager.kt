package com.example.bp.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File


class DownloadStateManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "download_states",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val TAG = "DownloadStateManager"
        private const val KEY_ACTIVE_DOWNLOADS = "active_downloads"
        private const val PREFIX_DOWNLOAD_STATE = "download_"
    }


     // Data struktura pro uložený stav downloadu

    data class DownloadStateData(
        val modelName: String,
        val totalChunks: Int,
        val completedChunks: Set<Int>,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isPaused: Boolean,
        val metadata: ModelMetadata,
        val timestamp: Long = System.currentTimeMillis(),
        val tmpFilePath: String? = null
    )


    @Synchronized
    fun saveDownloadState(
        modelName: String,
        completedChunks: Set<Int>,
        totalChunks: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        isPaused: Boolean,
        metadata: ModelMetadata,
        tmpFilePath: String? = null
    ) {
        try {
            val state = DownloadStateData(
                modelName = modelName,
                totalChunks = totalChunks,
                completedChunks = completedChunks,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                isPaused = isPaused,
                metadata = metadata,
                timestamp = System.currentTimeMillis(),
                tmpFilePath = tmpFilePath
            )

            val json = gson.toJson(state)
            prefs.edit()
                .putString(getStateKey(modelName), json)
                .apply()

            updateActiveDownloadsList(modelName, add = true)

            Log.d(TAG, "Saved state for $modelName: ${completedChunks.size}/$totalChunks chunks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save download state", e)
        }
    }


    @Synchronized
    fun getDownloadState(modelName: String): DownloadStateData? {
        return try {
            val json = prefs.getString(getStateKey(modelName), null) ?: return null
            gson.fromJson(json, DownloadStateData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load download state", e)
            null
        }
    }


    @Synchronized
    fun deleteDownloadState(modelName: String) {
        try {
            prefs.edit()
                .remove(getStateKey(modelName))
                .apply()

            updateActiveDownloadsList(modelName, add = false)

            Log.d(TAG, "Deleted state for $modelName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete download state", e)
        }
    }


    @Synchronized
    fun getAllPendingDownloads(): List<DownloadStateData> {
        return try {
            val activeList = getActiveDownloadsList()
            activeList.mapNotNull { modelName ->
                getDownloadState(modelName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pending downloads", e)
            emptyList()
        }
    }

    @Synchronized
    fun updateCompletedChunks(modelName: String, chunkIndex: Int) {
        try {
            val state = getDownloadState(modelName) ?: return
            val updatedChunks = state.completedChunks + chunkIndex

            saveDownloadState(
                modelName = state.modelName,
                completedChunks = updatedChunks,
                totalChunks = state.totalChunks,
                downloadedBytes = state.downloadedBytes,
                totalBytes = state.totalBytes,
                isPaused = state.isPaused,
                metadata = state.metadata,
                tmpFilePath = state.tmpFilePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update completed chunks", e)
        }
    }


    fun verifyTmpFile(state: DownloadStateData): Boolean {
        val tmpFile = state.tmpFilePath?.let { File(it) } ?: return false
        if (!tmpFile.exists()) {
            Log.w(TAG, "Tmp file doesn't exist: ${state.tmpFilePath}")
            return false
        }
        if (tmpFile.length() != state.totalBytes) {
            Log.w(TAG, "Tmp file size mismatch: ${tmpFile.length()} != ${state.totalBytes}")
            return false
        }
        return true
    }


    @Synchronized
    fun cleanupOldDownloads() {
        try {
            val now = System.currentTimeMillis()
            val maxAge = 7L * 24 * 60 * 60 * 1000 // 7 dní

            val toDelete = mutableListOf<String>()
            getAllPendingDownloads().forEach { state ->
                if (now - state.timestamp > maxAge) {
                    toDelete.add(state.modelName)
                    Log.d(TAG, "Cleaning up old download: ${state.modelName}")
                }
            }

            toDelete.forEach { modelName ->
                deleteDownloadState(modelName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old downloads", e)
        }
    }


    // PRIVATE METODY


    private fun getStateKey(modelName: String): String {
        return "$PREFIX_DOWNLOAD_STATE$modelName"
    }

    private fun getActiveDownloadsList(): List<String> {
        return try {
            val json = prefs.getString(KEY_ACTIVE_DOWNLOADS, null) ?: return emptyList()
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateActiveDownloadsList(modelName: String, add: Boolean) {
        try {
            val currentList = getActiveDownloadsList().toMutableSet()

            if (add) {
                currentList.add(modelName)
            } else {
                currentList.remove(modelName)
            }

            val json = gson.toJson(currentList.toList())
            prefs.edit()
                .putString(KEY_ACTIVE_DOWNLOADS, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update active downloads list", e)
        }
    }
}
