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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { ModelDownloader(context) }

    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }
    var downloadedModelPath by remember { mutableStateOf<String?>(null) }
    var isModelLoading by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Nový progress state pro chunked download
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var cacheSize by remember { mutableStateOf(0L) }

    // Načti seznam modelů při startu
    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            availableModels = downloader.getAvailableModels()
            cacheSize = downloader.getCacheSize()
            if (availableModels.isEmpty()) {
                errorMessage = "Žádné modely na serveru. Zkontrolujte, zda server běží."
            }
        } catch (e: Exception) {
            errorMessage = "Chyba připojení k serveru: ${e.message}"
            android.util.Log.e("ModelScreen", "Error loading models", e)
        } finally {
            isLoading = false
        }
    }

    // Formátovací funkce
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

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Toolbar s tlačítky
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "3D Model Viewer",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Refresh button
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    availableModels = downloader.getAvailableModels()
                                    cacheSize = downloader.getCacheSize()
                                    isLoading = false
                                }
                            },
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Refresh, "Obnovit seznam")
                        }

                        // Clear cache button
                        if (cacheSize > 0) {
                            IconButton(
                                onClick = {
                                    downloader.clearCache()
                                    cacheSize = 0
                                }
                            ) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error
                                ) {
                                    Text(formatBytes(cacheSize))
                                }
                                Icon(Icons.Default.Delete, "Vyčistit cache")
                            }
                        }
                    }
                }

                // Debug info
                selectedModel?.let { model ->
                    if (downloader.isModelDownloaded(model.name)) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "✓ Stažený model",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${model.name} (${downloader.getModelSize(model.name)} MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Dropdown pro výběr modelu
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
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
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.name)
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "${model.sizeInMB} MB",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                                if (model.chunked) {
                                                    Text(
                                                        text = "⚡ ${model.totalChunks} chunks",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                        if (downloader.isModelDownloaded(model.name)) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Staženo",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
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

                // Tlačítka pro akce
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Tlačítko pro stažení/zobrazení
                    Button(
                        onClick = {
                            selectedModel?.let { model ->
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    downloadProgress = null

                                    // Zkontroluj, zda už je stažený
                                    if (downloader.isModelDownloaded(model.name)) {
                                        downloadedModelPath = downloader.getModelPath(model.name)
                                        isLoading = false
                                        return@launch
                                    }

                                    // Stáhni s chunked supportem
                                    val file = downloader.downloadModelChunked(model.name) { progress ->
                                        downloadProgress = progress
                                    }

                                    if (file != null) {
                                        downloadedModelPath = file.absolutePath
                                        cacheSize = downloader.getCacheSize()
                                    } else {
                                        errorMessage = "Stahování selhalo - zkontrolujte připojení"
                                    }

                                    isLoading = false
                                    downloadProgress = null
                                }
                            }
                        },
                        enabled = selectedModel != null && !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoading && downloadProgress == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = when {
                                selectedModel?.name?.let { downloader.isModelDownloaded(it) } == true ->
                                    "Zobrazit model"
                                else -> "Stáhnout model"
                            }
                        )
                    }

                    // Tlačítko pro smazání staženého modelu
                    if (selectedModel?.name?.let { downloader.isModelDownloaded(it) } == true) {
                        OutlinedButton(
                            onClick = {
                                selectedModel?.let { model ->
                                    if (downloader.deleteModel(model.name)) {
                                        if (downloadedModelPath == downloader.getModelPath(model.name)) {
                                            downloadedModelPath = null
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Smazat",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Pokročilý progress bar s detaily
                AnimatedVisibility(
                    visible = isLoading && downloadProgress != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    downloadProgress?.let { progress ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // Hlavní info
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Stahování...",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${((progress.downloadedBytes.toFloat() / progress.totalBytes) * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Progress bar
                                val animatedProgress by animateFloatAsState(
                                    targetValue = progress.downloadedBytes.toFloat() / progress.totalBytes,
                                    label = "progress"
                                )
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Detaily
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (progress.totalChunks > 0) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "📦 Chunks:",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                text = "${progress.downloadedChunks} / ${progress.totalChunks}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "💾 Staženo:",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "⚡ Rychlost:",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = formatSpeed(progress.currentSpeed),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (progress.eta > 0) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "⏱️ Zbývá:",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                text = formatTime(progress.eta),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Chybová zpráva
                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "❌ $error",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        // 3D Viewer
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                downloadedModelPath != null -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ModelViewer(
                            modifier = Modifier.fillMaxSize(),
                            modelSource = ModelSource.FILE,
                            modelPath = downloadedModelPath!!
                        )

                        // Overlay pro velké modely
                        if (selectedModel?.sizeInMB ?: 0.0 > 50.0 && isModelLoading) {
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
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Načítání velkého modelu...",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "${selectedModel?.sizeInMB} MB - může trvat několik sekund",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Po načtení modelu skryj loading
                    LaunchedEffect(downloadedModelPath) {
                        isModelLoading = true
                        kotlinx.coroutines.delay(10000)
                        isModelLoading = false
                    }
                }
                isLoading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = if (downloadProgress != null)
                                "Stahování..."
                            else
                                "Načítání...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "👆",
                            style = MaterialTheme.typography.displayMedium
                        )
                        Text(
                            text = "Vyberte model ze seznamu",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (availableModels.isNotEmpty()) {
                            Text(
                                text = "${availableModels.size} modelů k dispozici",
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