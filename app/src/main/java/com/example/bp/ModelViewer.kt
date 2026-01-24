package com.example.bp

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ModelViewer(
    modifier: Modifier = Modifier,
    modelSource: ModelSource = ModelSource.ASSETS,
    modelPath: String = "models/DamagedHelmet.glb"
) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    DisposableEffect(Unit) {
        onDispose {
            android.util.Log.d("ModelViewer", "ModelViewer disposed")
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

            // ---------- LOAD MODEL ----------
            val materialProvider = UbershaderProvider(engine)
            val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
            val resourceLoader = ResourceLoader(engine)

            val modelCenter = FloatArray(3)

            // Načti model na background threadu
            scope.launch(Dispatchers.Main) {
                try {
                    android.util.Log.d("ModelViewer", "Starting model load...")

                    // Načti soubor do bufferu na IO threadu
                    val buffer = withContext(Dispatchers.IO) {
                        when (modelSource) {
                            ModelSource.ASSETS -> {
                                context.assets.open(modelPath).use { inputStream ->
                                    val bytes = inputStream.readBytes()
                                    ByteBuffer.allocateDirect(bytes.size)
                                        .order(ByteOrder.nativeOrder())
                                        .put(bytes)
                                        .flip() as ByteBuffer
                                }
                            }
                            ModelSource.FILE -> {
                                val file = File(modelPath)
                                if (!file.exists()) {
                                    throw IllegalArgumentException("Model soubor neexistuje: $modelPath")
                                }

                                val fileSize = file.length()
                                val maxSize = 200 * 1024 * 1024 // 200 MB limit

                                if (fileSize > maxSize) {
                                    android.util.Log.e("ModelViewer", "Model je příliš velký: ${fileSize / (1024 * 1024)} MB")
                                    throw IllegalArgumentException("Model je příliš velký (max 200 MB)")
                                }

                                android.util.Log.d("ModelViewer", "Loading model: ${fileSize / (1024 * 1024)} MB")

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
                                            android.util.Log.d("ModelViewer", "Loaded: ${totalRead / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB")
                                        }
                                    }

                                    buffer.flip()
                                    buffer
                                }
                            }
                        }
                    }

                    // Zpátky na main thread - všechny Filament operace musí být zde
                    android.util.Log.d("ModelViewer", "Creating asset from buffer...")
                    val asset = assetLoader.createAsset(buffer)

                    asset?.let {
                        android.util.Log.d("ModelViewer", "Loading resources (this may take a while)...")

                        // Synchronní načtení (musí být na main threadu)
                        resourceLoader.loadResources(it)

                        android.util.Log.d("ModelViewer", "Releasing source data...")
                        it.releaseSourceData()

                        android.util.Log.d("ModelViewer", "Adding entities to scene...")
                        scene.addEntities(it.entities)

                        val c = it.boundingBox.center
                        modelCenter[0] = c[0]
                        modelCenter[1] = c[1]
                        modelCenter[2] = c[2]

                        android.util.Log.d("ModelViewer", "Model loaded successfully!")
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e("ModelViewer", "Out of memory loading model!", e)
                } catch (e: Exception) {
                    android.util.Log.e("ModelViewer", "Error loading model", e)
                    e.printStackTrace()
                }
            }

            // ---------- CAMERA CONTROL ----------
            var cameraDistance = 6.0
            var cameraAngleX = 0.0
            var cameraAngleY = 20.0

            var lastTouchX = 0f
            var lastTouchY = 0f
            var initialDistance = 0f

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
                            cameraDistance *= scale
                            cameraDistance = cameraDistance.coerceIn(2.0, 15.0)
                            initialDistance = currentDistance
                        } else {
                            val dx = event.x - lastTouchX
                            val dy = event.y - lastTouchY

                            cameraAngleX += dx * 0.3
                            cameraAngleY -= dy * 0.3
                            cameraAngleY = cameraAngleY.coerceIn(-89.0, 89.0)

                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        updateCamera()
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

                    updateCamera()

                    val frameCallback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
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