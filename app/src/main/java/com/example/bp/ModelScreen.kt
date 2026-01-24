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

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var downloadedModelPath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Načti seznam modelů při startu
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            availableModels = downloader.getAvailableModels()
        } catch (e: Exception) {
            errorMessage = "Chyba při načítání seznamu modelů: ${e.message}"
        }
        isLoading = false
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

                Spacer(modifier = Modifier.height(8.dp))

                // Dropdown pro výběr modelu
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedModel ?: "Vyberte model",
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
                        availableModels.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName) },
                                onClick = {
                                    selectedModel = modelName
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

                                // Zkontroluj, zda už je stažený
                                if (downloader.isModelDownloaded(model)) {
                                    downloadedModelPath = downloader.getModelPath(model)
                                    isLoading = false
                                    return@launch
                                }

                                // Jinak stáhni
                                val file = downloader.downloadModel(model)
                                if (file != null) {
                                    downloadedModelPath = file.absolutePath
                                } else {
                                    errorMessage = "Stahování selhalo"
                                }
                                isLoading = false
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
                        text = if (selectedModel?.let { downloader.isModelDownloaded(it) } == true)
                            "Model je stažený - Zobrazit"
                        else
                            "Stáhnout a zobrazit"
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
                    ModelViewer(
                        modifier = Modifier.fillMaxSize(),
                        modelSource = ModelSource.FILE,
                        modelPath = downloadedModelPath!!
                    )
                }
                isLoading -> {
                    CircularProgressIndicator()
                }
                else -> {
                    Text("Vyberte a stáhněte model ze serveru")
                }
            }
        }
    }
}