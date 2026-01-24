package com.example.bp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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

    // Když se model stáhne, nastav loading state
    LaunchedEffect(downloadedModelPath) {
        if (downloadedModelPath != null) {
            isModelLoading = true
            // Po 10 sekundách skryj loading (model by měl být načtený)
            kotlinx.coroutines.delay(10000)
            isModelLoading = false
        }
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedMB by remember { mutableStateOf(0L) }
    var totalMB by remember { mutableStateOf(0L) }

    // Načti seznam modelů při startu
    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            availableModels = downloader.getAvailableModels()
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
                Text(
                    text = "3D Model Viewer",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Debug info
                selectedModel?.let { model ->
                    if (downloader.isModelDownloaded(model.name)) {
                        Text(
                            text = "Stažený model: ${model.name} (${downloader.getModelSize(model.name)} MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Dropdown pro výběr modelu
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedModel?.name ?: "Vyberte model",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text("Model ze serveru") }
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.name)
                                        Text(
                                            text = "${model.sizeInMB} MB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
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

                // Tlačítko pro stažení
                Button(
                    onClick = {
                        selectedModel?.let { model ->
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                downloadProgress = 0f

                                // Zkontroluj, zda už je stažený
                                if (downloader.isModelDownloaded(model.name)) {
                                    downloadedModelPath = downloader.getModelPath(model.name)
                                    isLoading = false
                                    return@launch
                                }

                                // Jinak stáhni s progress tracking
                                val file = downloader.downloadModel(model.name) { downloaded, total ->
                                    downloadedMB = downloaded / (1024 * 1024)
                                    totalMB = total / (1024 * 1024)
                                    downloadProgress = if (total > 0) downloaded.toFloat() / total else 0f
                                }

                                if (file != null) {
                                    downloadedModelPath = file.absolutePath
                                } else {
                                    errorMessage = "Stahování selhalo"
                                }
                                isLoading = false
                                downloadProgress = 0f
                            }
                        }
                    },
                    enabled = selectedModel != null && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (selectedModel?.name?.let { downloader.isModelDownloaded(it) } == true)
                            "Model je stažený (${downloader.getModelSize(selectedModel!!.name)} MB) - Zobrazit"
                        else
                            "Stáhnout a zobrazit"
                    )
                }

                // Progress bar při stahování
                if (isLoading && downloadProgress > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Staženo: $downloadedMB MB / $totalMB MB (${(downloadProgress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Chybová zpráva
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
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
                    // Zobraz loading při načítání modelu
                    Box(modifier = Modifier.fillMaxSize()) {
                        ModelViewer(
                            modifier = Modifier.fillMaxSize(),
                            modelSource = ModelSource.FILE,
                            modelPath = downloadedModelPath!!
                        )

                        // Overlay text pro velké modely
                        if (selectedModel?.sizeInMB ?: 0.0 > 50.0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(
                                    text = "⚠️ Načítání velkého modelu (${selectedModel?.sizeInMB} MB)\nMůže trvat několik sekund...",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        if (downloadProgress > 0f) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Stahování: ${(downloadProgress * 100).toInt()}%")
                        }
                    }
                }
                else -> {
                    Text("Vyberte a stáhněte model ze serveru")
                }
            }
        }
    }
}