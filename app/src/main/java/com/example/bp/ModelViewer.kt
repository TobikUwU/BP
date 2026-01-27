package com.example.bp

import android.annotation.SuppressLint
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ModelSource {
    ASSETS,     // Model z assets složky
    FILE        // Model ze souboru (stažený)
}

/**
 * Holder pro Filament stav - umožňuje hot-swap modelů
 */
private class FilamentState(
    val engine: Engine,
    val renderer: Renderer,
    val scene: Scene,
    val view: View,
    val camera: Camera,
    val assetLoader: AssetLoader,
    val resourceLoader: ResourceLoader
) {
    var currentAsset: FilamentAsset? = null
    val modelCenter = FloatArray(3)

    // Async loading state
    var pendingAsset: FilamentAsset? = null
    var asyncLoadingStarted = false
    var onAsyncComplete: (() -> Unit)? = null
    private var lastResourceCount = 0

    // Queued path - pokud přijde nová cesta během loadingu, uložíme ji sem
    var queuedModelPath: String? = null
    var onQueuedPathReady: ((String) -> Unit)? = null

    // Camera state - zachová se při swapu
    var cameraDistance = 6.0
    var cameraAngleX = 0.0
    var cameraAngleY = 20.0

    fun updateCamera() {
        val angleXRad = cameraAngleX * PI / 180.0
        val angleYRad = cameraAngleY * PI / 180.0

        val eyeX = modelCenter[0] + cameraDistance * cos(angleYRad) * sin(angleXRad)
        val eyeY = modelCenter[1] + cameraDistance * sin(angleYRad)
        val eyeZ = modelCenter[2] + cameraDistance * cos(angleYRad) * cos(angleXRad)

        camera.lookAt(
            eyeX, eyeY, eyeZ,
            modelCenter[0].toDouble(),
            modelCenter[1].toDouble(),
            modelCenter[2].toDouble(),
            0.0, 1.0, 0.0
        )
    }

    /**
     * Začni async loading - volá se jednou
     */
    fun beginAsyncLoad(asset: FilamentAsset, onComplete: () -> Unit) {
        pendingAsset = asset
        asyncLoadingStarted = true
        onAsyncComplete = onComplete
        // Reset counters
        lastResourceCount = 0
        asyncUpdateCount = 0
        stableFrameCount = 0
        lastEntityCount = 0
        resourceLoader.asyncBeginLoad(asset)
        Log.d("ModelViewer", "🔄 Async loading started for ${asset.resourceUris.size} resources")
    }

    // Počítadlo pro async loading
    private var asyncUpdateCount = 0
    private var stableFrameCount = 0
    private var lastEntityCount = 0

    /**
     * Aktualizuj async loading - volej každý frame
     * Vrací true když je loading kompletní
     */
    fun updateAsyncLoad(): Boolean {
        val asset = pendingAsset ?: return false
        if (!asyncLoadingStarted) return false

        // Posuň loading dopředu
        resourceLoader.asyncUpdateLoad()
        asyncUpdateCount++

        // Zkontroluj počet entit s renderables
        val currentEntityCount = asset.entities.size

        if (currentEntityCount == lastEntityCount) {
            stableFrameCount++
        } else {
            stableFrameCount = 0
            lastEntityCount = currentEntityCount
        }

        // Považuj za hotové když:
        // 1. Máme nějaké entity
        // 2. Stav se nezměnil po 30 framů (~0.5s při 60fps)
        // 3. Nebo jsme překročili 300 updatů (~5s)
        val isComplete = (currentEntityCount > 0 && stableFrameCount > 30) || asyncUpdateCount > 300

        if (isComplete) {
            Log.d("ModelViewer", "Async loading complete after $asyncUpdateCount updates")
            finalizeAsyncLoad()
            return true
        }

        return false
    }

    /**
     * Dokonči async loading - zavolej po dokončení
     */
    fun finalizeAsyncLoad() {
        val asset = pendingAsset
        val callback = onAsyncComplete
        val nextPath = queuedModelPath
        val nextPathCallback = onQueuedPathReady

        // Vždy vyčisti stav
        asyncLoadingStarted = false
        pendingAsset = null
        onAsyncComplete = null
        queuedModelPath = null
        onQueuedPathReady = null

        if (asset == null) {
            Log.e("ModelViewer", "⚠️ finalizeAsyncLoad called but pendingAsset is null")
            callback?.invoke()
            // Zpracuj queued path i při chybě
            if (nextPath != null) {
                Log.d("ModelViewer", "🔄 Processing queued path after error: $nextPath")
                nextPathCallback?.invoke(nextPath)
            }
            return
        }

        try {
            asset.releaseSourceData()
            swapModel(asset)
            Log.d("ModelViewer", "✅ Async loading finalized!")
        } catch (e: Exception) {
            Log.e("ModelViewer", "Error in finalizeAsyncLoad", e)
        } finally {
            callback?.invoke()
            // Zpracuj queued path - načti další model pokud čeká
            if (nextPath != null) {
                Log.d("ModelViewer", "🔄 Processing queued path: $nextPath")
                nextPathCallback?.invoke(nextPath)
            }
        }
    }

    /**
     * Načti resources synchronně (fallback)
     */
    fun loadResourcesSync(asset: FilamentAsset) {
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
    }

    /**
     * Nahradí aktuální model novým - plynulý přechod
     */
    fun swapModel(newAsset: FilamentAsset) {
        // Odstraň staré entity ze scény
        currentAsset?.let { oldAsset ->
            Log.d("ModelViewer", "Removing old model entities...")
            scene.removeEntities(oldAsset.entities)
        }

        // Přidej nové entity
        Log.d("ModelViewer", "Adding new model entities...")
        scene.addEntities(newAsset.entities)

        // Aktualizuj střed modelu
        val c = newAsset.boundingBox.center
        modelCenter[0] = c[0]
        modelCenter[1] = c[1]
        modelCenter[2] = c[2]

        // Aktualizuj kameru
        updateCamera()

        // Ulož nový asset
        currentAsset = newAsset

        Log.d("ModelViewer", "Model swap completed!")
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ModelViewer(
    modifier: Modifier = Modifier,
    modelSource: ModelSource = ModelSource.ASSETS,
    modelPath: String = "models/DamagedHelmet.glb",
    onModelLoaded: (() -> Unit)? = null  // Callback když je model načtený
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sleduj aktuálně načtenou cestu - pro detekci změny
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var filamentState by remember { mutableStateOf<FilamentState?>(null) }

    // Funkce pro načtení modelu - může být volána přímo nebo z queue
    suspend fun loadModel(pathToLoad: String, state: FilamentState) {
        Log.d("ModelViewer", "🔄 Hot-swap: loading new model: $pathToLoad")

        try {
            // Načti soubor do bufferu na IO threadu
            val buffer = withContext(Dispatchers.IO) {
                when (modelSource) {
                    ModelSource.ASSETS -> {
                        context.assets.open(pathToLoad).use { inputStream ->
                            val bytes = inputStream.readBytes()
                            ByteBuffer.allocateDirect(bytes.size)
                                .order(ByteOrder.nativeOrder())
                                .put(bytes)
                                .flip() as ByteBuffer
                        }
                    }
                    ModelSource.FILE -> {
                        val file = File(pathToLoad)
                        if (!file.exists()) {
                            throw IllegalArgumentException("Model soubor neexistuje: $pathToLoad")
                        }

                        val fileSize = file.length()
                        val maxSize = 200 * 1024 * 1024 // 200 MB limit

                        if (fileSize > maxSize) {
                            Log.e("ModelViewer", "Model je příliš velký: ${fileSize / (1024 * 1024)} MB")
                            throw IllegalArgumentException("Model je příliš velký (max 200 MB)")
                        }

                        Log.d("ModelViewer", "Loading model from file: ${fileSize / (1024 * 1024)} MB")

                        FileInputStream(file).use { inputStream ->
                            val chunkSize = 1024 * 1024 // 1 MB chunks
                            val totalBytes = fileSize.toInt()
                            val buffer = ByteBuffer.allocateDirect(totalBytes)
                                .order(ByteOrder.nativeOrder())

                            val chunk = ByteArray(chunkSize)
                            var bytesRead: Int
                            var totalRead = 0

                            while (inputStream.read(chunk).also { bytesRead = it } != -1) {
                                buffer.put(chunk, 0, bytesRead)
                                totalRead += bytesRead

                                if (totalRead % (10 * 1024 * 1024) == 0) {
                                    Log.d("ModelViewer", "Buffer loaded: ${totalRead / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB")
                                }
                            }

                            buffer.flip()
                            buffer
                        }
                    }
                }
            }

            // Zpátky na main thread - všechny Filament operace musí být zde
            withContext(Dispatchers.Main) {
                Log.d("ModelViewer", "Creating asset from buffer...")
                val asset = state.assetLoader.createAsset(buffer)

                asset?.let {
                    // Spusť async loading
                    state.beginAsyncLoad(it) {
                        loadedPath = pathToLoad
                        isLoading = false
                        Log.d("ModelViewer", "✅ Model loaded and swapped: $pathToLoad")
                        onModelLoaded?.invoke()
                    }
                } ?: run {
                    isLoading = false
                    Log.e("ModelViewer", "Failed to create asset from buffer")
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.e("ModelViewer", "Out of memory loading model!", e)
            isLoading = false
        } catch (e: CancellationException) {
            Log.d("ModelViewer", "Model loading cancelled")
            isLoading = false
            throw e
        } catch (e: Exception) {
            Log.e("ModelViewer", "Error loading model", e)
            e.printStackTrace()
            isLoading = false
        }
    }

    // Když se změní modelPath, načti nový model (hot-swap)
    LaunchedEffect(modelPath, filamentState) {
        val state = filamentState ?: return@LaunchedEffect

        // Pokud je stejná cesta, nic nedělej
        if (modelPath == loadedPath) return@LaunchedEffect

        // Pokud už něco načítáme, zařaď do fronty
        if (isLoading || state.asyncLoadingStarted) {
            Log.d("ModelViewer", "⏳ Loading in progress, queueing: $modelPath")
            state.queuedModelPath = modelPath
            state.onQueuedPathReady = { queuedPath ->
                // Toto se zavolá po dokončení aktuálního loadingu
                // Spustíme nový load ve scope vázaném na composable
                scope.launch {
                    if (queuedPath != loadedPath) {
                        isLoading = true
                        loadModel(queuedPath, state)
                    }
                }
            }
            return@LaunchedEffect
        }

        isLoading = true
        loadModel(modelPath, state)
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("ModelViewer", "ModelViewer disposed")
        }
    }

    AndroidView(
        factory = { ctx ->

            Filament.init()
            Gltfio.init()

            val engine = Engine.create()
            val renderer = engine.createRenderer()
            val scene = engine.createScene()
            val view = engine.createView()
            val camera = engine.createCamera(EntityManager.get().create())

            view.scene = scene
            view.camera = camera

            // ---------- SKYBOX ----------
            scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(engine)

            // ---------- LIGHT ----------
            val light = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1f, 1f, 1f)
                .intensity(100_000f)
                .direction(0f, -0.5f, -1f)
                .build(engine, light)
            scene.addEntity(light)

            // ---------- LOADERS ----------
            val materialProvider = UbershaderProvider(engine)
            val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
            val resourceLoader = ResourceLoader(engine)

            // Vytvoř FilamentState
            val state = FilamentState(
                engine, renderer, scene, view, camera,
                assetLoader, resourceLoader
            )
            filamentState = state

            // ---------- TOUCH CONTROL ----------
            var lastTouchX = 0f
            var lastTouchY = 0f
            var initialDistance = 0f

            fun getDistance(event: MotionEvent): Float {
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                return sqrt(dx * dx + dy * dy)
            }

            // ---------- SURFACE ----------
            val surfaceView = SurfaceView(ctx)
            val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            val displayHelper = DisplayHelper(ctx)

            var swapChain: SwapChain? = null

            surfaceView.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount == 2) {
                            initialDistance = getDistance(event)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount == 2) {
                            val currentDistance = getDistance(event)
                            val scale = initialDistance / currentDistance
                            state.cameraDistance *= scale
                            state.cameraDistance = state.cameraDistance.coerceIn(2.0, 15.0)
                            initialDistance = currentDistance
                        } else {
                            val dx = event.x - lastTouchX
                            val dy = event.y - lastTouchY

                            state.cameraAngleX += dx * 0.3
                            state.cameraAngleY -= dy * 0.3
                            state.cameraAngleY = state.cameraAngleY.coerceIn(-89.0, 89.0)

                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        state.updateCamera()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }

            uiHelper.renderCallback = object : UiHelper.RendererCallback {

                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface)
                    displayHelper.attach(renderer, surfaceView.display)

                    state.updateCamera()

                    val frameCallback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            // Aktualizuj async loading pokud probíhá
                            state.updateAsyncLoad()

                            if (surfaceView.height > 0) {
                                val aspect = surfaceView.width.toDouble() / surfaceView.height
                                camera.setProjection(45.0, aspect, 0.1, 20.0, Camera.Fov.VERTICAL)
                            }

                            swapChain?.let { sc ->
                                if (renderer.beginFrame(sc, frameTimeNanos)) {
                                    renderer.render(view)
                                    renderer.endFrame()
                                }
                            }

                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }

                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }

                override fun onDetachedFromSurface() {
                    displayHelper.detach()
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = null
                }

                override fun onResized(width: Int, height: Int) {
                    view.viewport = Viewport(0, 0, width, height)
                }
            }

            uiHelper.attachTo(surfaceView)
            surfaceView
        },
        modifier = modifier
    )
}