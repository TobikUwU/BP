package com.example.bp

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Helper funkce pro načtení asset souboru jako ByteBuffer
private fun loadAssetAsByteBuffer(context: Context, assetName: String): ByteBuffer {
    context.assets.open(assetName).use { input ->
        val bytes = input.readBytes()
        return ByteBuffer.wrap(bytes)
    }
}

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

    var isLoadingAsset = false
    var queuedModelPath: String? = null
    var onQueuedPathReady: ((String) -> Unit)? = null

    var cameraDistance = 6.0
    var cameraAngleX = 0.0
    var cameraAngleY = 20.0

    fun updateCamera() {
        val angleXRad = cameraAngleX * PI / 180.0
        val angleYRad = cameraAngleY * PI / 180.0

        // Pozice kamery
        val eyeX = modelCenter[0] + cameraDistance * cos(angleYRad) * sin(angleXRad)
        val eyeY = modelCenter[1] + cameraDistance * sin(angleYRad)
        val eyeZ = modelCenter[2] + cameraDistance * cos(angleYRad) * cos(angleXRad)

        camera.lookAt(
            eyeX, eyeY, eyeZ,
            modelCenter[0].toDouble(), modelCenter[1].toDouble(), modelCenter[2].toDouble(),
            0.0, 1.0, 0.0
        )
    }

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

        // Vypočítej optimální vzdálenost kamery podle velikosti modelu
        val halfExtent = newAsset.boundingBox.halfExtent
        val maxExtent = maxOf(halfExtent[0], halfExtent[1], halfExtent[2])
        cameraDistance = (maxExtent * 3.5).coerceAtLeast(6.0)  // Minimálně 6.0

        Log.d("ModelViewer", "Model size: ${maxExtent * 2}, Camera distance: $cameraDistance")

        updateCamera()

        currentAsset = newAsset

        Log.d("ModelViewer", "Model swap completed!")
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ModelViewer(
    modifier: Modifier = Modifier,
    modelPath: String,
    onModelLoaded: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    var loadedPath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var filamentState by remember { mutableStateOf<FilamentState?>(null) }

    fun loadModel(pathToLoad: String, state: FilamentState) {
        Log.d("ModelViewer", "Hot-swap: loading new model: $pathToLoad")

        scope.launch {
            try {
                val buffer = withContext(Dispatchers.IO) {
                    val file = File(pathToLoad)
                    if (!file.exists()) {
                        throw IllegalArgumentException("Model soubor neexistuje: $pathToLoad")
                    }

                    val fileSize = file.length()
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

                Log.d("ModelViewer", "Creating asset from buffer...")
                val asset = state.assetLoader.createAsset(buffer)

                asset?.let {
                    val resourceCount = it.resourceUris.size

                    if (resourceCount == 0) {
                        Log.d("ModelViewer", "No external resources - loading synchronously")
                        state.resourceLoader.loadResources(it)
                        it.releaseSourceData()
                        state.swapModel(it)
                        loadedPath = pathToLoad
                        isLoading = false
                        state.isLoadingAsset = false
                        Log.d("ModelViewer", "Model loaded and swapped: $pathToLoad")
                        onModelLoaded?.invoke()

                        // Zpracuj queued path
                        val nextPath = state.queuedModelPath
                        if (nextPath != null) {
                            state.queuedModelPath = null
                            val callback = state.onQueuedPathReady
                            state.onQueuedPathReady = null
                            callback?.invoke(nextPath)
                        }
                    } else {
                        Log.d("ModelViewer", "Loading $resourceCount external resources asynchronously")
                        state.resourceLoader.asyncBeginLoad(it)
                        state.isLoadingAsset = true

                        it.releaseSourceData()
                        state.swapModel(it)
                        loadedPath = pathToLoad
                        isLoading = false
                        state.isLoadingAsset = false
                        Log.d("ModelViewer", "Model swapped, textures loading in background: $pathToLoad")
                        onModelLoaded?.invoke()

                        val nextPath = state.queuedModelPath
                        if (nextPath != null) {
                            state.queuedModelPath = null
                            val callback = state.onQueuedPathReady
                            state.onQueuedPathReady = null
                            callback?.invoke(nextPath)
                        }
                    }
                } ?: run {
                    isLoading = false
                    state.isLoadingAsset = false
                    Log.e("ModelViewer", "Failed to create asset from buffer")
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
    }

    LaunchedEffect(modelPath, filamentState) {
        val state = filamentState ?: return@LaunchedEffect

        if (modelPath == loadedPath) return@LaunchedEffect

        if (isLoading || state.isLoadingAsset) {
            Log.d("ModelViewer", "Loading in progress, queueing: $modelPath")
            state.queuedModelPath = modelPath
            state.onQueuedPathReady = { queuedPath ->
                if (queuedPath != loadedPath) {
                    isLoading = true
                    loadModel(queuedPath, state)
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
            Utils.init()

            val engine = Engine.create()
            val renderer = engine.createRenderer()
            val scene = engine.createScene()
            val view = engine.createView()
            val camera = engine.createCamera(EntityManager.get().create())

            view.scene = scene
            view.camera = camera

            // ---------- IBL & SKYBOX ----------
            try {
                // Načti IBL a Skybox z assets
                val iblBuffer = loadAssetAsByteBuffer(ctx, "qwantani_ibl.ktx")
                val skyboxBuffer = loadAssetAsByteBuffer(ctx, "qwantani_skybox.ktx")

                // Vytvoř IndirectLight (IBL) z KTX souboru
                val iblBundle = KTX1Loader.createIndirectLight(engine, iblBuffer)
                scene.indirectLight = iblBundle.indirectLight
                scene.indirectLight?.intensity = 30000f

                // Vytvoř Skybox z KTX souboru
                val skyboxBundle = KTX1Loader.createSkybox(engine, skyboxBuffer)
                scene.skybox = skyboxBundle.skybox

                Log.d("ModelViewer", "IBL and Skybox (qwantani) loaded successfully")
            } catch (e: Exception) {
                Log.e("ModelViewer", "Failed to load IBL/Skybox, using fallback", e)
                e.printStackTrace()
                // Fallback: jednoduchý modrý skybox
                scene.skybox = Skybox.Builder()
                    .color(0.53f, 0.81f, 0.92f, 1f)
                    .build(engine)
            }

            // LOADERS
            val materialProvider = UbershaderProvider(engine)
            val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
            val resourceLoader = ResourceLoader(engine)

            val state = FilamentState(
                engine, renderer, scene, view, camera,
                assetLoader, resourceLoader
            )
            filamentState = state

            // TOUCH CONTROL
            var lastTouchX = 0f
            var lastTouchY = 0f
            var lastPinchDistance = 0f
            var isPinching = false

            fun getPinchDistance(event: MotionEvent): Float {
                if (event.pointerCount < 2) return 0f
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                return kotlin.math.sqrt(dx * dx + dy * dy)
            }

            // SURFACE
            val surfaceView = SurfaceView(ctx)
            val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            val displayHelper = DisplayHelper(ctx)

            var swapChain: SwapChain? = null

            surfaceView.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        isPinching = false
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount == 2) {
                            lastPinchDistance = getPinchDistance(event)
                            isPinching = true
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        when (event.pointerCount) {
                            2 -> {
                                if (isPinching) {
                                    // 2 prsty = zoom
                                    val currentDistance = getPinchDistance(event)
                                    if (lastPinchDistance > 0 && currentDistance > 0) {
                                        val scale = lastPinchDistance / currentDistance
                                        state.cameraDistance *= scale
                                        state.cameraDistance = state.cameraDistance.coerceIn(1.0, 100.0)
                                        lastPinchDistance = currentDistance
                                    }
                                }
                            }
                            1 -> {
                                if (!isPinching) {
                                    // 1 prst = rotace
                                    val dx = event.x - lastTouchX
                                    val dy = event.y - lastTouchY

                                    state.cameraAngleX += dx * 0.3
                                    state.cameraAngleY -= dy * 0.3
                                    state.cameraAngleY = state.cameraAngleY.coerceIn(-89.0, 89.0)

                                    lastTouchX = event.x
                                    lastTouchY = event.y
                                }
                            }
                        }
                        state.updateCamera()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        isPinching = false
                        lastPinchDistance = 0f
                        if (event.pointerCount == 1) {
                            v.performClick()
                        }
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

                            if (surfaceView.height > 0) {
                                val aspect = surfaceView.width.toDouble() / surfaceView.height
                                camera.setProjection(45.0, aspect, 0.1, 500.0, Camera.Fov.VERTICAL)
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