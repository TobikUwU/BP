package com.example.bp

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bp.download.DownloadManager
import com.example.bp.download.DownloadProgress
import com.example.bp.download.ModelInfo
import com.example.bp.download.StreamSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember { DownloadManager(context) }

    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }
    var activeSession by remember { mutableStateOf<StreamSession?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isViewerLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var cacheSize by remember { mutableStateOf(0L) }
    var expanded by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val isSelectedModelOpen = selectedModel?.name != null && activeSession?.model?.name == selectedModel?.name

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
            else -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
        }
    }

    fun formatTime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    suspend fun refreshModels() {
        isLoading = true
        errorMessage = null
        try {
            availableModels = downloadManager.getAvailableModels()
            cacheSize = downloadManager.getCacheSize()
            if (availableModels.isEmpty()) {
                errorMessage = "Žádné modely na serveru."
            }
        } catch (e: Exception) {
            errorMessage = "Chyba připojení: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refreshModels() }

    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("3D Model Viewer", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Hybrid overview + detail tiles klient",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cacheSize > 0) {
                            Text(
                                "Cache: ${formatBytes(cacheSize)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (cacheSize > 0) {
                            OutlinedButton(
                                onClick = {
                                    downloadJob?.cancel()
                                    if (downloadManager.clearCache()) {
                                        activeSession = null
                                        cacheSize = 0L
                                        Toast.makeText(context, "Cache vyčištěna", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isLoading
                            ) {
                                Text("Vyčistit cache")
                            }
                        }

                        IconButton(onClick = { scope.launch { refreshModels() } }, enabled = !isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Obnovit")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded && !isLoading }
                ) {
                    OutlinedTextField(
                        value = selectedModel?.name ?: "Vyberte model",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text("Model ze serveru") },
                        enabled = !isLoading
                    )

                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.name)
                                            Text(
                                                "${model.overviewStageCount} overview • ${model.tileCount} tiles • ${model.sizeInMB} MB",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (downloadManager.isModelDownloaded(model.name)) {
                                                Text(
                                                    "V cache",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    selectedModel = model
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                selectedModel?.let { model ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (downloadManager.isModelDownloaded(model.name)) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(model.name, fontWeight = FontWeight.Bold)
                            Text("Strategy: ${model.streamingStrategy.ifBlank { "hybrid_overview_tiles" }}", style = MaterialTheme.typography.bodySmall)
                            Text("Entry: ${model.entryFile}", style = MaterialTheme.typography.bodySmall)
                            Text("Overview stages: ${model.overviewStageCount}", style = MaterialTheme.typography.bodySmall)
                            Text("Detail tiles: ${model.tileCount}", style = MaterialTheme.typography.bodySmall)
                            if (model.upgradeOrder.isNotEmpty()) {
                                Text("Upgrade order: ${model.upgradeOrder.joinToString(" → ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (downloadManager.isModelDownloaded(model.name)) {
                                Text("Status: v cache", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("Status: není v cache", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (isSelectedModelOpen) {
                            downloadJob?.cancel()
                            activeSession = null
                            isViewerLoading = false
                            downloadProgress = null
                            errorMessage = null
                        } else {
                            selectedModel?.let { model ->
                                downloadJob?.cancel()
                                downloadJob = scope.launch {
                                    try {
                                        errorMessage = null
                                        val cached = downloadManager.getCachedSession(model)
                                        if (cached != null) {
                                            activeSession = cached
                                            return@launch
                                        }

                                        isLoading = true
                                        downloadProgress = null
                                        val session = downloadManager.downloadModelSmart(model) { progress ->
                                            downloadProgress = progress
                                        }

                                        if (session != null) {
                                            activeSession = session
                                            cacheSize = downloadManager.getCacheSize()
                                        } else {
                                            errorMessage = "Načtení stream bootstrapu selhalo"
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ModelScreen", "Download error", e)
                                        errorMessage = "Chyba: ${e.message}"
                                    } finally {
                                        isLoading = false
                                        downloadProgress = null
                                    }
                                }
                            }
                        }
                    },
                    enabled = selectedModel != null && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading && downloadProgress == null) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        if (isSelectedModelOpen) {
                            "Zavřít model"
                        } else {
                            "Otevřít model"
                        }
                    )
                }

                downloadProgress?.let { progress ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            val progressValue = if (progress.totalBytes > 0) {
                                progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()
                            } else 0f
                            Text(
                                "Načítání první overview stage ${((progressValue) * 100).roundToInt()}%",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Staženo: ${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("Rychlost: ${formatSpeed(progress.currentSpeed)}", style = MaterialTheme.typography.bodySmall)
                            if (progress.eta > 0) {
                                Text("Zbývá: ${formatTime(progress.eta)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                activeSession != null -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ModelViewer(
                            modifier = Modifier.fillMaxSize(),
                            session = activeSession!!,
                            onModelLoaded = { isViewerLoading = false }
                        )

                        if (isViewerLoading) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator()
                                    Column {
                                        Text("Načítání první overview stage...")
                                        Text(
                                            "Po prvním zobrazení klient postupně přepíná overview LODy a stahuje detail tiles podle hierarchie manifestu.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    LaunchedEffect(activeSession?.entryFilePath) {
                        isViewerLoading = true
                    }
                }
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Načítání stream bootstrapu...")
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Vyberte model ze seznamu")
                        if (availableModels.isNotEmpty()) {
                            Text(
                                "${availableModels.size} modelů k dispozici",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
