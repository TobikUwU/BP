package com.example.bp

import android.annotation.SuppressLint
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bp.download.StreamSession
import com.example.bp.download.StreamStage
import com.example.bp.download.StreamTile
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun loadAssetAsByteBuffer(context: android.content.Context, assetName: String): ByteBuffer {
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

private class FilamentState(
    val engine: Engine,
    val renderer: Renderer,
    val scene: Scene,
    val view: View,
    val camera: Camera,
    val assetLoader: AssetLoader,
    val resourceLoader: ResourceLoader
) {
    private var overviewAsset: FilamentAsset? = null
    private var overviewVisible = false
    private val tileAssets = linkedMapOf<String, FilamentAsset>()
    private val resolvedTileIds = linkedSetOf<String>()

    val modelCenter = FloatArray(3)
    var cameraDistance = 6.0
    var cameraAngleX = 0.0
    var cameraAngleY = 20.0
    private var modelExtent = 1.0

    fun updateCamera() {
        val angleXRad = cameraAngleX * PI / 180.0
        val angleYRad = cameraAngleY * PI / 180.0

        val eyeX = modelCenter[0] + cameraDistance * cos(angleYRad) * sin(angleXRad)
        val eyeY = modelCenter[1] + cameraDistance * sin(angleYRad)
        val eyeZ = modelCenter[2] + cameraDistance * cos(angleYRad) * cos(angleXRad)

        camera.lookAt(
            eyeX,
            eyeY,
            eyeZ,
            modelCenter[0].toDouble(),
            modelCenter[1].toDouble(),
            modelCenter[2].toDouble(),
            0.0,
            1.0,
            0.0
        )
    }

    fun cameraPosition(): DoubleArray {
        val angleXRad = cameraAngleX * PI / 180.0
        val angleYRad = cameraAngleY * PI / 180.0
        val eyeX = modelCenter[0] + cameraDistance * cos(angleYRad) * sin(angleXRad)
        val eyeY = modelCenter[1] + cameraDistance * sin(angleYRad)
        val eyeZ = modelCenter[2] + cameraDistance * cos(angleYRad) * cos(angleXRad)
        return doubleArrayOf(eyeX, eyeY, eyeZ)
    }

    fun zoomFactor(): Double {
        val nearDistance = (modelExtent * 1.8).coerceAtLeast(1.0)
        val farDistance = (modelExtent * 7.5).coerceAtLeast(nearDistance + 1.0)
        val normalized = ((cameraDistance - nearDistance) / (farDistance - nearDistance))
            .coerceIn(0.0, 1.0)
        return 1.0 - normalized
    }

    fun visibleTileCount(): Int = tileAssets.size

    fun visibleTileIds(): List<String> = tileAssets.keys.toList()

    private fun updateBoundsFromAsset(asset: FilamentAsset) {
        val center = asset.boundingBox.center
        modelCenter[0] = center[0]
        modelCenter[1] = center[1]
        modelCenter[2] = center[2]

        val halfExtent = asset.boundingBox.halfExtent
        val maxExtent = maxOf(halfExtent[0], halfExtent[1], halfExtent[2])
        modelExtent = maxExtent.toDouble().coerceAtLeast(1.0)
        cameraDistance = (maxExtent * 3.5).coerceAtLeast(6.0)
        updateCamera()
    }

    fun setOverviewAsset(newAsset: FilamentAsset, updateViewBounds: Boolean) {
        val shouldShowOverview = overviewAsset == null || overviewVisible
        overviewAsset?.let { previous ->
            scene.removeEntities(previous.entities)
            assetLoader.destroyAsset(previous)
        }

        overviewAsset = newAsset
        overviewVisible = shouldShowOverview
        if (shouldShowOverview) {
            scene.addEntities(newAsset.entities)
        }
        if (updateViewBounds) {
            updateBoundsFromAsset(newAsset)
        }
    }

    fun hideOverview() {
        val asset = overviewAsset ?: return
        if (!overviewVisible) return
        scene.removeEntities(asset.entities)
        overviewVisible = false
    }

    fun showOverview() {
        val asset = overviewAsset ?: return
        if (overviewVisible) return
        scene.addEntities(asset.entities)
        overviewVisible = true
    }

    fun destroyOverview() {
        overviewAsset?.let { asset ->
            if (overviewVisible) {
                scene.removeEntities(asset.entities)
            }
            assetLoader.destroyAsset(asset)
        }
        overviewAsset = null
        overviewVisible = false
    }

    fun registerTile(tileId: String, asset: FilamentAsset, updateViewBounds: Boolean = false) {
        removeTile(tileId)
        scene.addEntities(asset.entities)
        tileAssets[tileId] = asset
        resolvedTileIds.add(tileId)
        if (updateViewBounds) {
            updateBoundsFromAsset(asset)
        }
    }

    fun removeTile(tileId: String) {
        val asset = tileAssets.remove(tileId) ?: return
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
    }

    fun isTileResolved(tileId: String): Boolean {
        return tileId in resolvedTileIds
    }

    fun isTileVisible(tileId: String): Boolean {
        return tileId in tileAssets
    }

    fun markTileResolved(tileId: String) {
        resolvedTileIds.add(tileId)
    }

    fun forgetTile(tileId: String) {
        resolvedTileIds.remove(tileId)
    }

    fun areTilesResolved(tileIds: Collection<String>): Boolean {
        return tileIds.isNotEmpty() && tileIds.all(::isTileResolved)
    }

    fun clearSceneAssets() {
        destroyOverview()
        tileAssets.keys.toList().forEach(::removeTile)
        resolvedTileIds.clear()
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ModelViewer(
    modifier: Modifier = Modifier,
    session: StreamSession,
    onModelLoaded: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val downloader = remember(context) { ModelDownloader(context) }
    var filamentState by remember { mutableStateOf<FilamentState?>(null) }
    var loadToken by remember { mutableIntStateOf(0) }

    fun overviewSequence(currentSession: StreamSession): List<StreamStage> {
        val stages = currentSession.bootstrap.manifest.overviewStages
        if (stages.isEmpty()) return emptyList()

        val stageMap = stages.associateBy { it.id }
        val ordered = buildList {
            currentSession.bootstrap.manifest.upgradeOrder.forEach { stageId ->
                stageMap[stageId]?.let(::add)
            }
            stages.forEach { stage ->
                if (none { it.id == stage.id }) add(stage)
            }
        }

        return ordered
    }

    fun entryOverviewStage(currentSession: StreamSession): StreamStage? {
        val stages = overviewSequence(currentSession)
        if (stages.isEmpty()) return null

        val stageMap = stages.associateBy { it.id }
        val entryId = currentSession.bootstrap.manifest.firstFrameStageId
            .ifBlank { currentSession.bootstrap.manifest.entryStage }
            .ifBlank { currentSession.bootstrap.metadata.entryStage }

        return stageMap[entryId] ?: stages.first()
    }

    fun tilePriorityMap(currentSession: StreamSession): Map<String, Int> {
        return currentSession.bootstrap.manifest.tileTraversalOrder.withIndex()
            .associate { it.value to it.index }
    }

    fun rootTileSequence(currentSession: StreamSession): List<StreamTile> {
        val manifest = currentSession.bootstrap.manifest
        if (manifest.tiles.isEmpty()) return emptyList()

        val tileMap = manifest.tiles.associateBy { it.id }
        val priorityMap = tilePriorityMap(currentSession)
        val orderedIds = buildList {
            manifest.rootTiles.forEach { tileId ->
                if (!contains(tileId)) add(tileId)
            }
            manifest.tiles.filter { it.parentId == null }.forEach { tile ->
                if (!contains(tile.id)) add(tile.id)
            }
        }

        return orderedIds
            .mapNotNull(tileMap::get)
            .sortedBy { priorityMap[it.id] ?: Int.MAX_VALUE }
    }

    fun refinementTileSequence(currentSession: StreamSession): List<StreamTile> {
        val manifest = currentSession.bootstrap.manifest
        if (manifest.tiles.isEmpty()) return emptyList()

        val tileMap = manifest.tiles.associateBy { it.id }
        val priorityMap = tilePriorityMap(currentSession)
        val rootIds = rootTileSequence(currentSession).map { it.id }.toSet()
        val ordered = mutableListOf<StreamTile>()
        val visited = mutableSetOf<String>()

        fun visitChildren(tile: StreamTile) {
            tile.children
                .mapNotNull(tileMap::get)
                .sortedBy { priorityMap[it.id] ?: Int.MAX_VALUE }
                .forEach { child ->
                    if (visited.add(child.id)) {
                        ordered += child
                    }
                    visitChildren(child)
                }
        }

        rootTileSequence(currentSession).forEach(::visitChildren)

        currentSession.bootstrap.manifest.tileTraversalOrder
            .mapNotNull(tileMap::get)
            .forEach { tile ->
                if (tile.id !in rootIds && visited.add(tile.id)) {
                    ordered += tile
                    visitChildren(tile)
                }
            }

        currentSession.bootstrap.manifest.tiles.forEach { tile ->
            if (tile.id !in rootIds && visited.add(tile.id)) {
                ordered += tile
                visitChildren(tile)
            }
        }

        return ordered
    }

    fun tileDistanceScore(state: FilamentState, tile: StreamTile): Double {
        val bounds = tile.bounds ?: return Double.MAX_VALUE
        if (bounds.center.size < 3) return Double.MAX_VALUE
        val camera = state.cameraPosition()
        val dx = camera[0] - bounds.center[0]
        val dy = camera[1] - bounds.center[1]
        val dz = camera[2] - bounds.center[2]
        return sqrt(dx * dx + dy * dy + dz * dz) - bounds.radius
    }

    fun nextTilesToLoad(
        state: FilamentState,
        currentSession: StreamSession,
        rootTiles: List<StreamTile>,
        refinementTiles: List<StreamTile>,
    ): List<StreamTile> {
        val budgets = currentSession.bootstrap.manifest.clientBudgets
        val maxActiveTiles = budgets.recommendedMaxActiveTiles
            .coerceAtLeast(4)
            .coerceAtMost(8)
        val rootTileCount = rootTiles.size
        val visibleTiles = state.visibleTileCount()
        if (visibleTiles >= maxActiveTiles) return emptyList()

        val zoomFactor = state.zoomFactor()
        val minimumVisible = minOf(rootTileCount, (maxActiveTiles / 2).coerceAtLeast(4))
        val targetVisible = (minimumVisible + ((maxActiveTiles - minimumVisible) * zoomFactor))
            .toInt()
            .coerceAtLeast(minimumVisible)
            .coerceAtMost(maxActiveTiles)

        val loadBudget = (targetVisible - visibleTiles).coerceAtLeast(0)
        if (loadBudget == 0) return emptyList()

        val maxConcurrent = budgets.recommendedConcurrentTileRequests.coerceIn(1, 2)
        val candidates = rootTiles + refinementTiles

        return candidates
            .asSequence()
            .filter { !state.isTileResolved(it.id) }
            .filter { tile ->
                val parentId = tile.parentId
                parentId == null || state.isTileResolved(parentId) || state.isTileVisible(parentId)
            }
            .sortedWith(
                compareBy<StreamTile>(
                    { if (it.parentId == null) 0 else 1 },
                    { tileDistanceScore(state, it) },
                    { it.priority.takeIf { value -> value > 0 } ?: Int.MAX_VALUE },
                    { it.depth },
                ),
            )
            .take(loadBudget.coerceAtMost(maxConcurrent))
            .toList()
    }

    fun desiredOverviewStageIndex(state: FilamentState, overviewStages: List<StreamStage>): Int {
        if (overviewStages.isEmpty()) return -1
        val zoom = state.zoomFactor()
        return when {
            overviewStages.size >= 3 && zoom >= 0.65 -> 2
            overviewStages.size >= 2 && zoom >= 0.35 -> 1
            else -> 0
        }.coerceAtMost(overviewStages.lastIndex)
    }

    fun shouldUseDetailMode(state: FilamentState, rootTiles: List<StreamTile>): Boolean {
        if (rootTiles.isEmpty()) return false
        val minimumDetailTiles = minOf(rootTiles.size, 4)
        return state.zoomFactor() >= 0.55 && state.visibleTileCount() >= minimumDetailTiles
    }

    fun pruneVisibleTiles(
        state: FilamentState,
        currentSession: StreamSession,
        tileMap: Map<String, StreamTile>,
        rootTiles: List<StreamTile>,
    ) {
        val maxActiveTiles = currentSession.bootstrap.manifest.clientBudgets.recommendedMaxActiveTiles
            .coerceAtLeast(4)
            .coerceAtMost(8)
        val rootTileCount = rootTiles.size
        val zoomFactor = state.zoomFactor()
        val minimumVisible = minOf(rootTileCount, (maxActiveTiles / 2).coerceAtLeast(4))
        val targetVisible = (minimumVisible + ((maxActiveTiles - minimumVisible) * zoomFactor))
            .toInt()
            .coerceAtLeast(minimumVisible)
            .coerceAtMost(maxActiveTiles)

        if (state.visibleTileCount() <= targetVisible) return

        val removableTiles = state.visibleTileIds()
            .mapNotNull(tileMap::get)
            .filter { tile -> tile.children.none(state::isTileVisible) }
            .sortedWith(
                compareByDescending<StreamTile> { tileDistanceScore(state, it) }
                    .thenBy { if (it.parentId == null) 0 else 1 }
                    .thenByDescending { it.depth }
                    .thenByDescending { it.priority }
            )

        removableTiles.forEach { tile ->
            if (state.visibleTileCount() <= targetVisible) return
            state.removeTile(tile.id)
            state.forgetTile(tile.id)
        }
    }

    suspend fun createAsset(state: FilamentState, file: File, label: String): FilamentAsset? {
        val buffer = withContext(Dispatchers.IO) { loadFileAsByteBuffer(file) }
        val asset = state.assetLoader.createAsset(buffer)
        if (asset == null) {
            Log.e("ModelViewer", "Failed to create GLB asset for $label")
            return null
        }

        state.resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        return asset
    }

    suspend fun loadStage(state: FilamentState, stage: StreamStage, token: Int) {
        val sequence = overviewSequence(session)
        val stageIndex = sequence.indexOfFirst { it.id == stage.id }.coerceAtLeast(0) + 1
        val stageFile = downloader.ensureOverviewStageCached(
            session = session,
            stage = stage,
            stageIndex = stageIndex,
            stageCount = sequence.size,
        ) ?: return

        if (token != loadToken) return

        val asset = createAsset(state, stageFile, stage.id) ?: return
        if (token != loadToken) {
            state.assetLoader.destroyAsset(asset)
            return
        }

        state.setOverviewAsset(asset, updateViewBounds = false)
    }

    fun promoteResolvedTiles(
        state: FilamentState,
        tileMap: Map<String, StreamTile>,
        loadedTileId: String
    ) {
        var parentId = tileMap[loadedTileId]?.parentId
        while (parentId != null) {
            val parentTile = tileMap[parentId] ?: break
            if (parentTile.children.isNotEmpty() && parentTile.children.all(state::isTileResolved)) {
                if (state.isTileVisible(parentId)) {
                    state.removeTile(parentId)
                }
                state.markTileResolved(parentId)
                parentId = parentTile.parentId
            } else {
                break
            }
        }
    }

    suspend fun loadTile(
        state: FilamentState,
        tile: StreamTile,
        tileIndex: Int,
        tileCount: Int,
        token: Int,
        updateViewBounds: Boolean,
    ) {
        val tileFile = downloader.ensureTileCached(
            session = session,
            tile = tile,
            tileIndex = tileIndex,
            tileCount = tileCount,
        ) ?: return

        if (token != loadToken) return

        val asset = createAsset(state, tileFile, tile.id) ?: return
        if (token != loadToken) {
            state.assetLoader.destroyAsset(asset)
            return
        }

        state.registerTile(tile.id, asset, updateViewBounds)
    }

    LaunchedEffect(session, filamentState) {
        val state = filamentState ?: return@LaunchedEffect
        val entryStage = entryOverviewStage(session)
        val overviewStages = overviewSequence(session)
        val rootTiles = rootTileSequence(session)
        val refinementTiles = refinementTileSequence(session)
        val tileMap = session.bootstrap.manifest.tiles.associateBy { it.id }

        loadToken += 1
        val token = loadToken
        var firstVisualDelivered = false
        var currentOverviewIndex = overviewStages.indexOfFirst { it.id == entryStage?.id }

        state.clearSceneAssets()

        if (entryStage != null) {
            val stageFile = downloader.ensureOverviewStageCached(
                session = session,
                stage = entryStage,
                stageIndex = (currentOverviewIndex.coerceAtLeast(0) + 1),
                stageCount = overviewStages.size.coerceAtLeast(1),
            ) ?: return@LaunchedEffect
            if (token != loadToken) return@LaunchedEffect
            val asset = createAsset(state, stageFile, entryStage.id) ?: return@LaunchedEffect
            if (token != loadToken) {
                state.assetLoader.destroyAsset(asset)
                return@LaunchedEffect
            }
            state.setOverviewAsset(asset, updateViewBounds = true)
            if (token != loadToken) return@LaunchedEffect
            if (!firstVisualDelivered) {
                onModelLoaded?.invoke()
                firstVisualDelivered = true
            }
        }

        while (token == loadToken) {
            val desiredOverviewIndex = desiredOverviewStageIndex(state, overviewStages)
            if (currentOverviewIndex in 0 until desiredOverviewIndex) {
                val nextIndex = currentOverviewIndex + 1
                loadStage(state, overviewStages[nextIndex], token)
                if (token != loadToken) return@LaunchedEffect
                currentOverviewIndex = nextIndex
            }

            pruneVisibleTiles(
                state = state,
                currentSession = session,
                tileMap = tileMap,
                rootTiles = rootTiles,
            )

            val nextTiles = nextTilesToLoad(
                state = state,
                currentSession = session,
                rootTiles = rootTiles,
                refinementTiles = refinementTiles,
            )

            if (nextTiles.isEmpty()) {
                delay(250)
                continue
            }

            nextTiles.forEachIndexed { index, tile ->
                loadTile(
                    state = state,
                    tile = tile,
                    tileIndex = index + 1,
                    tileCount = nextTiles.size,
                    token = token,
                    updateViewBounds = false,
                )
                if (token != loadToken) return@LaunchedEffect
                if (!state.isTileVisible(tile.id)) {
                    return@forEachIndexed
                }

                promoteResolvedTiles(state, tileMap, tile.id)
            }

            if (shouldUseDetailMode(state, rootTiles)) {
                state.hideOverview()
            } else {
                state.showOverview()
            }

            delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            filamentState?.clearSceneAssets()
            Log.d("ModelViewer", "ModelViewer disposed")
        }
    }

    AndroidView(
        factory = { ctx ->
            Gltfio.init()
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
