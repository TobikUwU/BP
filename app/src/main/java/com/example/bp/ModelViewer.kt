package com.example.bp

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ModelViewer(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->

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

            var asset: FilamentAsset?
            val modelCenter = FloatArray(3)

            try {
                val bytes = context.assets.open("models/DamagedHelmet.glb").readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                buffer.put(bytes).flip()

                asset = assetLoader.createAsset(buffer)
                asset?.let {
                    resourceLoader.loadResources(it)
                    it.releaseSourceData()
                    scene.addEntities(it.entities)

                    val c = it.boundingBox.center
                    modelCenter[0] = c[0]
                    modelCenter[1] = c[1]
                    modelCenter[2] = c[2]
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ---------- CAMERA CONTROL ----------
            var cameraDistance = 6.0
            var cameraAngleX = 0.0 // rotace kolem Y osy (vlevo-vpravo)
            var cameraAngleY = 20.0 // rotace kolem X osy (nahoru-dolů)

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
            val surfaceView = SurfaceView(context)
            val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            val displayHelper = DisplayHelper(context)

            var swapChain: SwapChain? = null

            // Touch listener pro ovládání kamery
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
                            // Pinch to zoom
                            val currentDistance = getDistance(event)
                            val scale = initialDistance / currentDistance
                            cameraDistance *= scale
                            cameraDistance = cameraDistance.coerceIn(2.0, 15.0)
                            initialDistance = currentDistance
                        } else {
                            // Rotace kamery
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
                                val aspect =
                                    surfaceView.width.toDouble() / surfaceView.height
                                camera.setProjection(
                                    45.0,
                                    aspect,
                                    0.1,
                                    20.0,
                                    Camera.Fov.VERTICAL
                                )
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