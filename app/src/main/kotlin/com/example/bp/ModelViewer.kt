package com.example.bp

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun loadAssetAsByteBuffer(context: Context, assetName: String): ByteBuffer {
    context.assets.open(assetName).use { input ->
        return ByteBuffer.wrap(input.readBytes())
    }
}

private fun loadFileAsByteBuffer(file: File): ByteBuffer {
    val bytes = file.readBytes()
    return ByteBuffer.allocateDirect(bytes.size)
        .order(ByteOrder.nativeOrder())
        .put(bytes)
        .apply { flip() }
}

private fun addResourceData(resourceLoader: ResourceLoader, uri: String, buffer: ByteBuffer): Boolean {
    val method = resourceLoader.javaClass.methods.firstOrNull {
        it.name == "addResourceData" && it.parameterCount == 2
    } ?: return false

    method.invoke(resourceLoader, uri, buffer)
    return true
}

private fun provideExternalResources(resourceLoader: ResourceLoader, asset: FilamentAsset, modelFile: File) {
    val baseDir = modelFile.parentFile ?: return
    asset.resourceUris.forEach { uri ->
        val resourceFile = File(baseDir, uri)
        if (!resourceFile.exists()) {
            Log.w("ModelViewer", "Missing external resource: ${resourceFile.absolutePath}")
            return@forEach
        }

        val buffer = loadFileAsByteBuffer(resourceFile)
        val added = addResourceData(resourceLoader, uri, buffer)
        if (!added) {
            Log.w("ModelViewer", "ResourceLoader.addResourceData not available for $uri")
        }
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
        currentAsset?.let { scene.removeEntities(it.entities) }
        scene.addEntities(newAsset.entities)

        val center = newAsset.boundingBox.center
        modelCenter[0] = center[0]
        modelCenter[1] = center[1]
        modelCenter[2] = center[2]

        val halfExtent = newAsset.boundingBox.halfExtent
        val maxExtent = maxOf(halfExtent[0], halfExtent[1], halfExtent[2])
        cameraDistance = (maxExtent * 3.5).coerceAtLeast(6.0)
        updateCamera()

        currentAsset = newAsset
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
        scope.launch {
            try {
                val modelFile = File(pathToLoad)
                val buffer = withContext(Dispatchers.IO) {
                    if (!modelFile.exists()) {
                        throw IllegalArgumentException("Model soubor neexistuje: $pathToLoad")
                    }
                    loadFileAsByteBuffer(modelFile)
                }

                val asset = state.assetLoader.createAsset(buffer)
                if (asset == null) {
                    isLoading = false
                    state.isLoadingAsset = false
                    Log.e("ModelViewer", "Failed to create asset from $pathToLoad")
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    if (asset.resourceUris.isNotEmpty()) {
                        provideExternalResources(state.resourceLoader, asset, modelFile)
                    }
                    state.resourceLoader.loadResources(asset)
                    asset.releaseSourceData()
                }

                state.swapModel(asset)
                loadedPath = pathToLoad
                isLoading = false
                state.isLoadingAsset = false
                onModelLoaded?.invoke()

                val nextPath = state.queuedModelPath
                if (nextPath != null) {
                    state.queuedModelPath = null
                    val callback = state.onQueuedPathReady
                    state.onQueuedPathReady = null
                    callback?.invoke(nextPath)
                }
            } catch (e: CancellationException) {
                isLoading = false
                state.isLoadingAsset = false
                throw e
            } catch (e: Exception) {
                Log.e("ModelViewer", "Error loading model", e)
                isLoading = false
                state.isLoadingAsset = false
            }
        }
    }

    LaunchedEffect(modelPath, filamentState) {
        val state = filamentState ?: return@LaunchedEffect
        if (modelPath == loadedPath) return@LaunchedEffect

        if (isLoading || state.isLoadingAsset) {
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
        state.isLoadingAsset = true
        loadModel(modelPath, state)
    }

    DisposableEffect(Unit) {
        onDispose { Log.d("ModelViewer", "ModelViewer disposed") }
    }

    AndroidView(
        factory = { ctx ->            Gltfio.init()
            Utils.init()

            val engine = Engine.create()
            val renderer = engine.createRenderer()
            val scene = engine.createScene()
            val view = engine.createView()
            val camera = engine.createCamera(EntityManager.get().create())

            view.scene = scene
            view.camera = camera

            try {
                val iblBuffer = loadAssetAsByteBuffer(ctx, "qwantani_ibl.ktx")
                val skyboxBuffer = loadAssetAsByteBuffer(ctx, "qwantani_skybox.ktx")
                val iblBundle = KTX1Loader.createIndirectLight(engine, iblBuffer)
                scene.indirectLight = iblBundle.indirectLight
                scene.indirectLight?.intensity = 30000f
                val skyboxBundle = KTX1Loader.createSkybox(engine, skyboxBuffer)
                scene.skybox = skyboxBundle.skybox
            } catch (e: Exception) {
                Log.e("ModelViewer", "Failed to load IBL/Skybox, using fallback", e)
                scene.skybox = Skybox.Builder().color(0.53f, 0.81f, 0.92f, 1f).build(engine)
            }

            val materialProvider = UbershaderProvider(engine)
            val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
            val resourceLoader = ResourceLoader(engine)

            val state = FilamentState(engine, renderer, scene, view, camera, assetLoader, resourceLoader)
            filamentState = state

            var lastTouchX = 0f
            var lastTouchY = 0f
            var lastPinchDistance = 0f
            var isPinching = false

            fun getPinchDistance(event: MotionEvent): Float {
                if (event.pointerCount < 2) return 0f
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                return sqrt(dx * dx + dy * dy)
            }

            val surfaceView = SurfaceView(ctx)
            val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            val displayHelper = DisplayHelper(ctx)
            var swapChain: SwapChain? = null

            surfaceView.setOnTouchListener { viewRef, event ->
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
                                    val currentDistance = getPinchDistance(event)
                                    if (lastPinchDistance > 0 && currentDistance > 0) {
                                        val scale = lastPinchDistance / currentDistance
                                        state.cameraDistance = (state.cameraDistance * scale).coerceIn(1.0, 100.0)
                                        lastPinchDistance = currentDistance
                                    }
                                }
                            }
                            1 -> {
                                if (!isPinching) {
                                    val dx = event.x - lastTouchX
                                    val dy = event.y - lastTouchY
                                    state.cameraAngleX += dx * 0.3
                                    state.cameraAngleY = (state.cameraAngleY - dy * 0.3).coerceIn(-89.0, 89.0)
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
                            viewRef.performClick()
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

                            swapChain?.let { chain ->
                                if (renderer.beginFrame(chain, frameTimeNanos)) {
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


