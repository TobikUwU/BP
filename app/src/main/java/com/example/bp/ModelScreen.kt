package com.example.bp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import com.example.bp.download.DownloadProgress
import com.example.bp.download.ModelInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloadManager = remember {
        com.example.bp.download.DownloadManager(context)
    }

    var debugForceParallel by remember { mutableStateOf(false) }
    var debugParallelCount by remember { mutableStateOf(5) }

    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }
    var downloadedModelPath by remember { mutableStateOf<String?>(null) }
    var isModelLoading by remember { mutableStateOf(false) }
    var uiRefreshTrigger by remember { mutableStateOf(0) } // Trigger pro recomposition po smazání

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var cacheSize by remember { mutableStateOf(0L) }

    var showMeteredWarning by remember { mutableStateOf(false) }
    var pendingDownloadModel by remember { mutableStateOf<ModelInfo?>(null) }

    // Job tracking pro cancellation
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Cleanup při dispose
    DisposableEffect(Unit) {
        onDispose {
            downloadJob?.cancel()
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            // Inicializuj download manager
            downloadManager.initialize()

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

    // Observe paused downloads
    val pausedDownloads by downloadManager.pausedDownloads.collectAsState()

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

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

        if (showMeteredWarning && pendingDownloadModel != null) {
            AlertDialog(
                onDismissRequest = {
                    showMeteredWarning = false
                    pendingDownloadModel = null
                },
                icon = { Text("⚠️", style = MaterialTheme.typography.displaySmall) },
                title = { Text("Mobilní data") },
                text = {
                    Column {
                        Text("Stahujete ${pendingDownloadModel!!.sizeInMB} MB přes mobilní data.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Doporučujeme připojit se k WiFi.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showMeteredWarning = false
                        val model = pendingDownloadModel!!
                        pendingDownloadModel = null

                        downloadJob?.cancel()
                        downloadJob = scope.launch {
                            try {
                                isLoading = true
                                val file = downloadManager.downloadModelSmart(model) { progress ->
                                    downloadProgress = progress
                                }
                                if (file != null) {
                                    downloadedModelPath = file.absolutePath
                                    cacheSize = downloadManager.getCacheSize()
                                    uiRefreshTrigger++ // Aktualizuj UI po stažení
                                } else {
                                    errorMessage = "Stahování selhalo"
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                Log.d("ModelScreen", "Download cancelled")
                            } catch (e: Exception) {
                                errorMessage = "Chyba: ${e.message}"
                            } finally {
                                isLoading = false
                                downloadProgress = null
                            }
                        }
                    }) {
                        Text("Pokračovat")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showMeteredWarning = false
                        pendingDownloadModel = null
                    }) {
                        Text("Zrušit")
                    }
                }
            )
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("3D Model Viewer", style = MaterialTheme.typography.headlineSmall)

                    IconButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                availableModels = downloadManager.getAvailableModels()
                                cacheSize = downloadManager.getCacheSize()
                                isLoading = false
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, "Obnovit")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // DEBUG SECTION
                var showDebug by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🐛 Debug Mode", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = showDebug, onCheckedChange = { showDebug = it })
                }

                if (showDebug) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("⚠️ Debug Options", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Force Parallel Download")
                                    Text("Ignoruje network detection", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = debugForceParallel,
                                    onCheckedChange = {
                                        debugForceParallel = it
                                        downloadManager.debugForceParallel = it
                                    }
                                )
                            }

                            if (debugForceParallel) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Parallel Chunks: $debugParallelCount")
                                Slider(
                                    value = debugParallelCount.toFloat(),
                                    onValueChange = {
                                        debugParallelCount = it.toInt()
                                        downloadManager.debugParallelCount = it.toInt()
                                    },
                                    valueRange = 1f..10f,
                                    steps = 8
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            val networkStats = downloadManager.getNetworkStats()
                            val http2Stats = com.example.bp.download.Http2ClientManager.getConnectionStats()
                            Text(
                                """
                                Network Info:
                                Type: ${networkStats.connectionType}
                                Speed: ${networkStats.averageSpeed / 1024} KB/s
                                Recommended: ${networkStats.recommendedParallelism}
                                Metered: ${networkStats.isMetered}

                                HTTP/2 Connection Pool:
                                $http2Stats
                                """.trimIndent(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }

                selectedModel?.let { model ->
                    key(model.name, uiRefreshTrigger) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (downloadManager.isModelDownloaded(model.name))
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (downloadManager.isModelDownloaded(model.name)) {
                                    Text("✓ Stažený model", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text("${model.name} (${downloadManager.getModelSize(model.name)} MB)", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("📊 Doporučení stahování", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text(downloadManager.getDownloadRecommendation(model), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded && !isLoading }
                ) {
                    OutlinedTextField(
                        value = selectedModel?.name ?: "Vyberte model",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        label = { Text("Model ze serveru") },
                        enabled = !isLoading
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
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
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("${model.sizeInMB} MB", style = MaterialTheme.typography.bodySmall)
                                                if (model.chunked) {
                                                    Text("⚡ ${model.totalChunks} chunks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                                }
                                            }
                                        }
                                        // Vždy zobrazuj delete button pokud je model stažený, díky uiRefreshTrigger se aktualizuje
                                        key(uiRefreshTrigger) {
                                            if (downloadManager.isModelDownloaded(model.name)) {
                                                IconButton(
                                                    onClick = {
                                                        if (downloadManager.deleteModel(model.name)) {
                                                            // Pokud je to aktuálně zobrazený model, schovej ho
                                                            if (downloadedModelPath?.contains(model.name) == true) {
                                                                downloadedModelPath = null
                                                            }
                                                            // Vyvolej recomposition
                                                            uiRefreshTrigger++

                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "Model ${model.name} smazán",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        "Smazat",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
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

                Spacer(modifier = Modifier.height(8.dp))

                key(selectedModel?.name, uiRefreshTrigger) {
                    Button(
                        onClick = {
                            selectedModel?.let { model ->
                                downloadJob?.cancel()
                                downloadJob = scope.launch {
                                    try {
                                        if (downloadManager.isModelDownloaded(model.name)) {
                                            downloadedModelPath = downloadManager.getModelPath(model.name)
                                            return@launch
                                        }

                                        if (downloadManager.shouldShowMeteredWarning(model)) {
                                            pendingDownloadModel = model
                                            showMeteredWarning = true
                                            return@launch
                                        }

                                        isLoading = true
                                        errorMessage = null
                                        downloadProgress = null

                                        val file = downloadManager.downloadModelSmart(model) { progress ->
                                            downloadProgress = progress
                                        }

                                        if (file != null) {
                                            downloadedModelPath = file.absolutePath
                                            cacheSize = downloadManager.getCacheSize()
                                            uiRefreshTrigger++ // Aktualizuj UI po stažení
                                        } else {
                                            errorMessage = "Stahování selhalo"
                                        }

                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        Log.d("ModelScreen", "Download cancelled")
                                    } catch (e: Exception) {
                                        errorMessage = "Chyba: ${e.message}"
                                        Log.e("ModelScreen", "Download error", e)
                                    } finally {
                                        isLoading = false
                                        downloadProgress = null
                                    }
                                }
                            }
                        },
                        enabled = selectedModel != null && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading && downloadProgress == null) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (selectedModel?.name?.let { downloadManager.isModelDownloaded(it) } == true) "Zobrazit model" else "Stáhnout model")
                    }
                }

                // DOWNLOAD PROGRESS
                AnimatedVisibility(
                    visible = isLoading && downloadProgress != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    downloadProgress?.let { progress ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Stahování...", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${((progress.downloadedBytes.toFloat() / progress.totalBytes) * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                val animatedProgress by animateFloatAsState(progress.downloadedBytes.toFloat() / progress.totalBytes, label = "progress")
                                LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(8.dp))

                                Spacer(modifier = Modifier.height(8.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (progress.totalChunks > 0) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("📦 Chunks:", style = MaterialTheme.typography.bodySmall)
                                            Text("${progress.downloadedChunks} / ${progress.totalChunks}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        }
                                    }

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("💾 Staženo:", style = MaterialTheme.typography.bodySmall)
                                        Text("${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    }

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("⚡ Rychlost:", style = MaterialTheme.typography.bodySmall)
                                        Text(formatSpeed(progress.currentSpeed), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                                    }

                                    if (progress.eta > 0) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("⏱️ Zbývá:", style = MaterialTheme.typography.bodySmall)
                                            Text(formatTime(progress.eta), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Pause/Resume/Cancel buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            selectedModel?.let { model ->
                                                scope.launch {
                                                    downloadJob?.cancel()
                                                    downloadManager.pauseDownload(model.name)
                                                    isLoading = false
                                                    downloadProgress = null
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("⏸ Pozastavit")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            selectedModel?.let { model ->
                                                scope.launch {
                                                    downloadJob?.cancel()
                                                    downloadManager.cancelDownload(model.name)
                                                    isLoading = false
                                                    downloadProgress = null
                                                    uiRefreshTrigger++
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("✕ Zrušit")
                                    }
                                }
                            }
                        }
                    }
                }

                // PAUSED DOWNLOADS
                if (pausedDownloads.isNotEmpty()) {
                    Text(
                        "Pozastavená stahování",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    pausedDownloads.forEach { pausedDownload ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            pausedDownload.modelName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val progressPercent = ((pausedDownload.downloadedBytes.toFloat() / pausedDownload.totalBytes) * 100).roundToInt()
                                        Text(
                                            "$progressPercent% dokončeno (${pausedDownload.completedChunks.size}/${pausedDownload.totalChunks} chunks)",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isLoading = true
                                                errorMessage = null
                                                downloadProgress = null
                                                selectedModel = ModelInfo(
                                                    pausedDownload.modelName,
                                                    0.0,
                                                    "",
                                                    false,
                                                    0
                                                )

                                                val file = downloadManager.resumeDownload(pausedDownload.modelName) { progress ->
                                                    downloadProgress = progress
                                                }

                                                if (file != null) {
                                                    downloadedModelPath = file.absolutePath
                                                    cacheSize = downloadManager.getCacheSize()
                                                    uiRefreshTrigger++
                                                } else {
                                                    errorMessage = "Obnovení selhalo"
                                                }

                                                isLoading = false
                                                downloadProgress = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !isLoading
                                    ) {
                                        Text("▶ Pokračovat")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                downloadManager.cancelDownload(pausedDownload.modelName)
                                                uiRefreshTrigger++
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("✕ Zrušit")
                                    }
                                }
                            }
                        }
                    }
                }

                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text("❌ $error", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                downloadedModelPath != null -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Hot-swap: ModelViewer zůstane, jen se vymění model
                        ModelViewer(
                            modifier = Modifier.fillMaxSize(),
                            modelSource = ModelSource.FILE,
                            modelPath = downloadedModelPath!!,
                            onModelLoaded = {
                                Log.d("ModelScreen", "✅ Model fully loaded in viewer")
                                isModelLoading = false
                            }
                        )

                        if (selectedModel?.sizeInMB ?: 0.0 > 50.0 && isModelLoading) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Column {
                                        Text("Načítání velkého modelu...", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text("${selectedModel?.sizeInMB} MB - může trvat několik sekund", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    // Nastavit isModelLoading při změně cesty - callback onModelLoaded ho vypne
                    LaunchedEffect(downloadedModelPath) {
                        isModelLoading = true
                    }
                }
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(
                            if (downloadProgress != null) "Stahování..." else "Načítání...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("👆", style = MaterialTheme.typography.displayMedium)
                        Text("Vyberte model ze seznamu", style = MaterialTheme.typography.bodyLarge)
                        if (availableModels.isNotEmpty()) {
                            Text("${availableModels.size} modelů k dispozici", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}