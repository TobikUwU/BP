package com.example.bp

import android.view.Choreographer
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

            var asset: FilamentAsset? = null

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
                    camera.lookAt(
                        3.0, 2.0, 6.0,
                        c[0].toDouble(), c[1].toDouble(), c[2].toDouble(),
                        0.0, 1.0, 0.0
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ---------- SURFACE ----------
            val surfaceView = SurfaceView(context)
            val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            val displayHelper = DisplayHelper(context)

            var swapChain: SwapChain? = null

            uiHelper.renderCallback = object : UiHelper.RendererCallback {

                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface)
                    displayHelper.attach(renderer, surfaceView.display)

                    val frameCallback = object : Choreographer.FrameCallback {
                        private var startTime = 0L

                        override fun doFrame(frameTimeNanos: Long) {
                            if (startTime == 0L) startTime = frameTimeNanos

                            asset?.let {
                                val seconds =
                                    (frameTimeNanos - startTime) / 1_000_000_000.0

                                val angle =
                                    (seconds * (25.0 * PI / 180.0)).toFloat()

                                val c = cos(angle)
                                val s = sin(angle)

                                val rotationY = floatArrayOf(
                                    c,  0f, -s, 0f,
                                    0f, 1f,  0f, 0f,
                                    s,  0f,  c, 0f,
                                    0f, 0f,  0f, 1f
                                )

                                val tm = engine.transformManager
                                tm.setTransform(
                                    tm.getInstance(it.root),
                                    rotationY
                                )
                            }

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
