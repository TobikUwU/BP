package com.example.bp

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "ModelViewer"
private const val MIN_TILE_LOAD_TIMEOUT_MS = 20_000L
private const val MAX_TILE_LOAD_TIMEOUT_MS = 120_000L
private const val CAMERA_ROTATION_SENSITIVITY = 0.25
private const val CAMERA_MIN_PITCH = -80.0
private const val CAMERA_MAX_PITCH = 80.0

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

private fun normalize(vector: DoubleArray): DoubleArray? {
    val length = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
    if (length <= 1e-6) return null
    return doubleArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
}

private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray {
    return doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}

private class FilamentState(
    val engine: Engine,
    val renderer: Renderer,
    val scene: Scene,
    val view: View,
    val camera: Camera,
    val assetLoader: AssetLoader,
) {
    private var overviewAsset: FilamentAsset? = null
    private var overviewVisible = false
    private val tileAssets = linkedMapOf<String, FilamentAsset>()
    private val resolvedTileIds = linkedSetOf<String>()

    val modelCenter = FloatArray(3)
    private val cameraTarget = DoubleArray(3)
    var cameraDistance = 6.0
    var cameraAngleX = 0.0
    var cameraAngleY = 20.0
    private var modelExtent = 1.0

    private fun minimumCameraDistance(): Double = (modelExtent * 0.35).coerceAtLeast(0.25)

    private fun maximumCameraDistance(): Double = (modelExtent * 10.0).coerceAtLeast(minimumCameraDistance() + 4.0)

    fun updateCamera() {
        val angleXRad = cameraAngleX * PI / 180.0
        val angleYRad = cameraAngleY * PI / 180.0

        val eyeX = cameraTarget[0] + cameraDistance * cos(angleYRad) * sin(angleXRad)
        val eyeY = cameraTarget[1] + cameraDistance * sin(angleYRad)
        val eyeZ = cameraTarget[2] + cameraDistance * cos(angleYRad) * cos(angleXRad)

        camera.lookAt(
            eyeX,
            eyeY,
            eyeZ,
            cameraTarget[0],
            cameraTarget[1],
            cameraTarget[2],
            0.0,
            1.0,
            0.0
        )
    }

    fun cameraPosition(): DoubleArray {
        val angleXRad = cameraAngleX * PI / 180.0
        val angleYRad = cameraAngleY * PI / 180.0
        val eyeX = cameraTarget[0] + cameraDistance * cos(angleYRad) * sin(angleXRad)
        val eyeY = cameraTarget[1] + cameraDistance * sin(angleYRad)
        val eyeZ = cameraTarget[2] + cameraDistance * cos(angleYRad) * cos(angleXRad)
        return doubleArrayOf(eyeX, eyeY, eyeZ)
    }

    fun zoomFactor(): Double {
        val nearDistance = minimumCameraDistance()
        val farDistance = maximumCameraDistance()
        val normalized = ((cameraDistance - nearDistance) / (farDistance - nearDistance))
            .coerceIn(0.0, 1.0)
        return 1.0 - normalized
    }

    fun zoomBy(scale: Double) {
        if (!scale.isFinite() || scale <= 0.0) return
        cameraDistance = (cameraDistance * scale).coerceIn(minimumCameraDistance(), maximumCameraDistance())
    }

    fun panBy(deltaX: Float, deltaY: Float, viewportWidth: Int, viewportHeight: Int) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val eye = cameraPosition()
        val forward = normalize(
            doubleArrayOf(
                cameraTarget[0] - eye[0],
                cameraTarget[1] - eye[1],
                cameraTarget[2] - eye[2],
            ),
        ) ?: return
        val worldUp = doubleArrayOf(0.0, 1.0, 0.0)
        val right = normalize(cross(forward, worldUp)) ?: doubleArrayOf(1.0, 0.0, 0.0)
        val up = normalize(cross(right, forward)) ?: worldUp

        val referenceSize = minOf(viewportWidth, viewportHeight).coerceAtLeast(1)
        val panScale = (cameraDistance * 1.5) / referenceSize.toDouble()
        val moveX = -deltaX * panScale
        val moveY = deltaY * panScale

        cameraTarget[0] += right[0] * moveX + up[0] * moveY
        cameraTarget[1] += right[1] * moveX + up[1] * moveY
        cameraTarget[2] += right[2] * moveX + up[2] * moveY
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
        cameraTarget[0] = center[0].toDouble()
        cameraTarget[1] = center[1].toDouble()
        cameraTarget[2] = center[2].toDouble()
        cameraAngleX = 0.0
        cameraAngleY = 20.0
        cameraDistance = (maxExtent * 3.5).coerceIn(minimumCameraDistance(), maximumCameraDistance())
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
    val downloadingTileIds = remember(session.model.name) { mutableStateListOf<String>() }
    val pendingTileIds = remember(session.model.name) { mutableStateListOf<String>() }
    val pendingTileFiles = remember(session.model.name) { linkedMapOf<String, File>() }
    var activationTileId by remember(session.model.name) { mutableStateOf<String?>(null) }
    var visibleTileCount by remember(session.model.name) { mutableIntStateOf(0) }
    var activeOverviewError by remember(session.model.name) { mutableStateOf<Double?>(null) }
    var detailModeActive by remember(session.model.name) { mutableStateOf(false) }
    var isTouchGestureActive by remember(session.model.name) { mutableStateOf(false) }
    var lastUserInteractionAtMs by remember(session.model.name) { mutableLongStateOf(0L) }
    var lastTileActivationAtMs by remember(session.model.name) { mutableLongStateOf(0L) }
    val tileActivationMutex = remember(session.model.name) { Mutex() }
    var overviewStatusMessage by remember(session.model.name) {
        mutableStateOf("Overview: čekám")
    }
    var tileStatusMessage by remember(session.model.name) {
        mutableStateOf("Čekám na detailní tiles")
    }

    fun updateTileStatus(message: String) {
        if (tileStatusMessage == message) return
        tileStatusMessage = message
        Log.d(TAG, message)
    }

    fun updateOverviewStatus(message: String) {
        if (overviewStatusMessage == message) return
        overviewStatusMessage = message
        Log.d(TAG, message)
    }

    fun overviewStageLabel(stages: List<StreamStage>, index: Int): String {
        if (index !in stages.indices) return "žádná"
        val stage = stages[index]
        return "${stage.id} (${index + 1}/${stages.size})"
    }

    fun overviewSnapshot(
        state: FilamentState,
        stages: List<StreamStage>,
        currentIndex: Int,
        desiredIndex: Int,
    ): String {
        val zoomPercent = (state.zoomFactor() * 100).toInt()
        return "Overview current=${overviewStageLabel(stages, currentIndex)} desired=${overviewStageLabel(stages, desiredIndex)} zoom=${zoomPercent}%"
    }

    fun effectiveMaxActiveTiles(currentSession: StreamSession): Int {
        return TileStreamingPolicy.effectiveMaxActiveTiles(
            manifestTileCount = currentSession.bootstrap.manifest.tiles.size,
            recommendedMaxActiveTiles = currentSession.bootstrap.manifest.clientBudgets.recommendedMaxActiveTiles,
        )
    }

    fun detailStreamingIdleRemainingMs(): Long {
        return TileStreamingPolicy.detailStreamingIdleRemainingMs(
            isTouchGestureActive = isTouchGestureActive,
            nowMs = SystemClock.uptimeMillis(),
            lastUserInteractionAtMs = lastUserInteractionAtMs,
        )
    }

    fun isDetailStreamingIdleReady(): Boolean =
        TileStreamingPolicy.isDetailStreamingIdleReady(
            isTouchGestureActive = isTouchGestureActive,
            nowMs = SystemClock.uptimeMillis(),
            lastUserInteractionAtMs = lastUserInteractionAtMs,
        )

    fun detailCandidateSnapshot(
        state: FilamentState,
        currentSession: StreamSession,
        rootTiles: List<StreamTile>,
        refinementTiles: List<StreamTile>,
        reservedTileIds: Set<String>,
        downloadingIds: Set<String>,
    ): String {
        val budgets = currentSession.bootstrap.manifest.clientBudgets
        val maxActiveTiles = effectiveMaxActiveTiles(currentSession)
        val rootTileCount = rootTiles.size
        val zoomPercent = (state.zoomFactor() * 100).toInt()
        val visibleTiles = state.visibleTileCount() + reservedTileIds.size
        val targetVisible = TileStreamingPolicy.targetVisibleTileCount(
            zoomFactor = state.zoomFactor(),
            rootTileCount = rootTileCount,
            maxActiveTiles = maxActiveTiles,
        )
        val loadBudget = (targetVisible - visibleTiles).coerceAtLeast(0)
        val requestSlots = (budgets.recommendedConcurrentTileRequests.coerceIn(1, 2) - downloadingIds.size)
            .coerceAtLeast(0)
        val candidates = rootTiles + refinementTiles
        val readyCandidates = candidates.count { tile ->
            val parentId = tile.parentId
            !state.isTileResolved(tile.id) &&
                tile.id !in reservedTileIds &&
                (parentId == null || state.isTileResolved(parentId) || state.isTileVisible(parentId))
        }
        val blockedByParent = candidates.count { tile ->
            val parentId = tile.parentId
            !state.isTileResolved(tile.id) &&
                tile.id !in reservedTileIds &&
                parentId != null &&
                !state.isTileResolved(parentId) &&
                !state.isTileVisible(parentId)
        }
        val unresolved = candidates.count { !state.isTileResolved(it.id) }
        val idleLabel = TileStreamingPolicy.detailIdleLabel(
            isTouchGestureActive = isTouchGestureActive,
            nowMs = SystemClock.uptimeMillis(),
            lastUserInteractionAtMs = lastUserInteractionAtMs,
        )

        return "Detail kandidáti=0 zoom=${zoomPercent}% visible=${state.visibleTileCount()} reserved=${reservedTileIds.size} downloading=${downloadingIds.size} target=$targetVisible/$maxActiveTiles loadBudget=$loadBudget slots=$requestSlots idle=$idleLabel roots=${rootTiles.size} refinement=${refinementTiles.size} ready=$readyCandidates blockedParent=$blockedByParent unresolved=$unresolved"
    }

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
            if (isEmpty()) {
                val minimumDepth = manifest.tiles.minOfOrNull(StreamTile::depth) ?: 0
                manifest.tiles.filter { it.depth == minimumDepth }.forEach { tile ->
                    if (!contains(tile.id)) add(tile.id)
                }
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
        reservedTileIds: Set<String>,
        downloadingIds: Set<String>,
    ): List<StreamTile> {
        return TileStreamingPolicy.nextTilesToLoad(
            zoomFactor = state.zoomFactor(),
            visibleTileCount = state.visibleTileCount(),
            manifestTileCount = currentSession.bootstrap.manifest.tiles.size,
            recommendedMaxActiveTiles = currentSession.bootstrap.manifest.clientBudgets.recommendedMaxActiveTiles,
            recommendedConcurrentTileRequests = currentSession.bootstrap.manifest.clientBudgets.recommendedConcurrentTileRequests,
            rootTiles = rootTiles,
            refinementTiles = refinementTiles,
            reservedTileIds = reservedTileIds,
            downloadingIds = downloadingIds,
            lookup = object : TileStreamingPolicy.TileLookup {
                override fun isResolved(tileId: String): Boolean = state.isTileResolved(tileId)
                override fun isVisible(tileId: String): Boolean = state.isTileVisible(tileId)
            },
            distanceScore = { tile -> tileDistanceScore(state, tile) },
        )
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

    fun shouldUseDetailMode(
        state: FilamentState,
        tileMap: Map<String, StreamTile>,
        currentOverviewStage: StreamStage?,
        currentDetailMode: Boolean,
    ): Boolean {
        val visibleTiles = state.visibleTileIds()
            .mapNotNull(tileMap::get)
        return TileStreamingPolicy.shouldUseDetailMode(
            zoomFactor = state.zoomFactor(),
            visibleTiles = visibleTiles,
            currentOverviewTriangleCount = currentOverviewStage?.triangleCount,
            currentDetailMode = currentDetailMode,
        )
    }

    fun pruneVisibleTiles(
        state: FilamentState,
        currentSession: StreamSession,
        tileMap: Map<String, StreamTile>,
        rootTiles: List<StreamTile>,
    ) {
        if (currentSession.bootstrap.manifest.tiles.size <= TileStreamingPolicy.TILE_PRUNE_DISABLED_THRESHOLD) {
            return
        }

        val maxActiveTiles = effectiveMaxActiveTiles(currentSession)
        val rootTileCount = rootTiles.size
        val targetVisible = TileStreamingPolicy.targetVisibleTileCount(
            zoomFactor = state.zoomFactor(),
            rootTileCount = rootTileCount,
            maxActiveTiles = maxActiveTiles,
        )
        val pruneThreshold = (targetVisible + TileStreamingPolicy.TILE_PRUNE_HYSTERESIS)
            .coerceAtMost(maxActiveTiles)
        val pruneTarget = (targetVisible + (TileStreamingPolicy.TILE_PRUNE_HYSTERESIS / 2))
            .coerceAtMost(maxActiveTiles)

        if (state.visibleTileCount() <= pruneThreshold) return

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
            if (state.visibleTileCount() <= pruneTarget) return
            state.removeTile(tile.id)
            state.forgetTile(tile.id)
        }
    }

    suspend fun createAsset(
        state: FilamentState,
        file: File,
        label: String,
        token: Int,
        asyncResources: Boolean,
    ): FilamentAsset? {
        val buffer = withContext(Dispatchers.IO) { loadFileAsByteBuffer(file) }
        val asset = state.assetLoader.createAsset(buffer)
        if (asset == null) {
            Log.e(TAG, "Failed to create GLB asset for $label")
            return null
        }

        val resourceLoader = ResourceLoader(state.engine)
        try {
            if (asyncResources && resourceLoader.asyncBeginLoad(asset)) {
                while (token == loadToken) {
                    resourceLoader.asyncUpdateLoad()
                    if (resourceLoader.asyncGetLoadProgress() >= 0.999f) {
                        break
                    }
                    delay(8)
                }

                if (token != loadToken) {
                    resourceLoader.asyncCancelLoad()
                    state.assetLoader.destroyAsset(asset)
                    return null
                }
            } else {
                resourceLoader.loadResources(asset)
            }
        } finally {
            resourceLoader.destroy()
        }

        asset.releaseSourceData()
        return asset
    }

    suspend fun loadStage(state: FilamentState, stage: StreamStage, token: Int) {
        val sequence = overviewSequence(session)
        val stageIndex = sequence.indexOfFirst { it.id == stage.id }.coerceAtLeast(0) + 1
        val zoomPercent = (state.zoomFactor() * 100).toInt()
        updateOverviewStatus("Overview loading ${stage.id} ($stageIndex/${sequence.size}) zoom=${zoomPercent}%")
        updateTileStatus("Načítám overview stage ${stage.id}")
        val stageFile = downloader.ensureOverviewStageCached(
            session = session,
            stage = stage,
            stageIndex = stageIndex,
            stageCount = sequence.size,
        ) ?: return

        if (token != loadToken) return

        val asset = createAsset(
            state = state,
            file = stageFile,
            label = stage.id,
            token = token,
            asyncResources = false,
        ) ?: return
        if (token != loadToken) {
            state.assetLoader.destroyAsset(asset)
            return
        }

        state.setOverviewAsset(asset, updateViewBounds = false)
        updateTileStatus("Overview stage ${stage.id} připravena")
        updateOverviewStatus("Overview ready ${stage.id} ($stageIndex/${sequence.size}) zoom=${zoomPercent}%")
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

    fun tileSizeInMb(tile: StreamTile): String {
        val sizeMb = tile.size.toDouble() / (1024.0 * 1024.0)
        return "%.1f".format(sizeMb)
    }

    fun tileLoadTimeoutMs(tile: StreamTile): Long {
        val sizeMb = (tile.size.toDouble() / (1024.0 * 1024.0)).coerceAtLeast(1.0)
        return (15_000L + (sizeMb * 1_000.0).toLong())
            .coerceIn(MIN_TILE_LOAD_TIMEOUT_MS, MAX_TILE_LOAD_TIMEOUT_MS)
    }

    suspend fun waitForTileActivationWindow(token: Int): Boolean {
        while (token == loadToken) {
            if (isTouchGestureActive) {
                delay(16)
                continue
            }
            val now = SystemClock.uptimeMillis()
            val sinceInteraction = now - lastUserInteractionAtMs
            val sinceActivation = now - lastTileActivationAtMs
            val interactionRemaining = TileStreamingPolicy.TILE_ACTIVATION_IDLE_DELAY_MS - sinceInteraction
            val activationRemaining = TileStreamingPolicy.TILE_ACTIVATION_SPACING_MS - sinceActivation
            val waitMs = maxOf(interactionRemaining, activationRemaining)
            if (waitMs <= 0L) {
                return true
            }
            delay(waitMs.coerceAtMost(32L))
        }
        return false
    }

    fun shouldDisplayTile(tile: StreamTile): Boolean {
        val overviewError = activeOverviewError ?: return true
        return tile.error <= overviewError
    }

    suspend fun downloadTile(
        tile: StreamTile,
        tileIndex: Int,
        tileCount: Int,
        token: Int,
    ): File? {
        val timeoutMs = tileLoadTimeoutMs(tile)
        val tileSizeMb = tileSizeInMb(tile)
        updateTileStatus(
            "Načítám detail tile ${tile.id} ($tileIndex/$tileCount, ${tileSizeMb} MB, timeout ${timeoutMs / 1000}s)",
        )

        val tileFile: File? = withTimeoutOrNull(timeoutMs) {
            downloader.ensureTileCached(
                session = session,
                tile = tile,
                tileIndex = tileIndex,
                tileCount = tileCount,
            )
        }

        if (tileFile == null) {
            updateTileStatus("Tile ${tile.id} timeout nebo chyba při downloadu (${tileSizeMb} MB)")
            Log.w(TAG, "Tile ${tile.id} failed or timed out after ${timeoutMs}ms (${tileSizeMb} MB)")
            return null
        }

        if (token != loadToken) return null

        return tileFile
    }

    suspend fun activateTile(
        state: FilamentState,
        tile: StreamTile,
        tileFile: File,
        token: Int,
        updateViewBounds: Boolean,
    ): Boolean {
        if (token != loadToken) return false

        return tileActivationMutex.withLock {
            if (!waitForTileActivationWindow(token)) {
                return@withLock false
            }

            if (token != loadToken) {
                return@withLock false
            }

            if (!shouldDisplayTile(tile)) {
                state.markTileResolved(tile.id)
                Log.d(
                    TAG,
                    "Tile ${tile.id} kept hidden because overview error ${activeOverviewError} is better than tile error ${tile.error}",
                )
                updateTileStatus("Tile ${tile.id} stažena jen pro odemčení child tiles, overview je kvalitnější")
                lastTileActivationAtMs = SystemClock.uptimeMillis()
                return@withLock true
            }

            val asset = createAsset(
                state = state,
                file = tileFile,
                label = tile.id,
                token = token,
                asyncResources = true,
            ) ?: run {
                updateTileStatus("Tile ${tile.id} se nepodařilo vytvořit")
                return@withLock false
            }
            if (token != loadToken) {
                state.assetLoader.destroyAsset(asset)
                return@withLock false
            }

            state.registerTile(tile.id, asset, updateViewBounds)
            lastTileActivationAtMs = SystemClock.uptimeMillis()
            visibleTileCount = state.visibleTileCount()
            updateTileStatus("Detail tile ${tile.id} načtena, viditelných ${state.visibleTileCount()}")
            return@withLock true
        }
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
        downloadingTileIds.clear()
        pendingTileIds.clear()
        pendingTileFiles.clear()
        activationTileId = null
        visibleTileCount = 0
        activeOverviewError = null
        detailModeActive = false
        isTouchGestureActive = false
        lastUserInteractionAtMs = 0L
        lastTileActivationAtMs = 0L
        overviewStatusMessage = "Overview: inicializace"
        updateTileStatus("Připravuji stream pro ${session.model.name}")
        Log.d(
            TAG,
            "Manifest for ${session.model.name}: ${session.bootstrap.manifest.tiles.size} tiles, " +
                "${rootTiles.size} root, ${refinementTiles.size} refinement",
        )
        if (overviewStages.isEmpty()) {
            updateOverviewStatus("Overview: manifest bez stages")
        } else {
            Log.d(
                TAG,
                "Overview sequence for ${session.model.name}: ${overviewStages.joinToString(" -> ") { it.id }}, entry=${entryStage?.id ?: "žádná"}",
            )
        }
        if (session.bootstrap.manifest.tiles.isNotEmpty() && rootTiles.isEmpty()) {
            updateTileStatus("Manifest obsahuje tiles, ale nenašel jsem root tiles")
            Log.w(TAG, "Manifest has ${session.bootstrap.manifest.tiles.size} tiles but no root tiles")
        }

        if (entryStage != null) {
            updateOverviewStatus("Overview entry loading ${overviewStageLabel(overviewStages, currentOverviewIndex)}")
            updateTileStatus("Načítám vstupní overview stage ${entryStage.id}")
            val stageFile = downloader.ensureOverviewStageCached(
                session = session,
                stage = entryStage,
                stageIndex = (currentOverviewIndex.coerceAtLeast(0) + 1),
                stageCount = overviewStages.size.coerceAtLeast(1),
            ) ?: return@LaunchedEffect
            if (token != loadToken) return@LaunchedEffect
            val asset = createAsset(
                state = state,
                file = stageFile,
                label = entryStage.id,
                token = token,
                asyncResources = false,
            ) ?: return@LaunchedEffect
            if (token != loadToken) {
                state.assetLoader.destroyAsset(asset)
                return@LaunchedEffect
            }
            state.setOverviewAsset(asset, updateViewBounds = true)
            if (token != loadToken) return@LaunchedEffect
            activeOverviewError = entryStage.error
            updateTileStatus("Vstupní overview stage ${entryStage.id} připravena")
            updateOverviewStatus(
                overviewSnapshot(
                    state = state,
                    stages = overviewStages,
                    currentIndex = currentOverviewIndex,
                    desiredIndex = desiredOverviewStageIndex(state, overviewStages),
                ),
            )
            if (!firstVisualDelivered) {
                onModelLoaded?.invoke()
                firstVisualDelivered = true
            }
        }

        while (token == loadToken) {
            val desiredOverviewIndex = desiredOverviewStageIndex(state, overviewStages)
            if (currentOverviewIndex in 0 until desiredOverviewIndex) {
                val nextIndex = currentOverviewIndex + 1
                updateOverviewStatus(
                    "Overview upgrade ${overviewStageLabel(overviewStages, currentOverviewIndex)} -> ${overviewStageLabel(overviewStages, nextIndex)} target=${overviewStageLabel(overviewStages, desiredOverviewIndex)}",
                )
                loadStage(state, overviewStages[nextIndex], token)
                if (token != loadToken) return@LaunchedEffect
                currentOverviewIndex = nextIndex
                activeOverviewError = overviewStages[nextIndex].error
            }
            if (overviewStages.isNotEmpty()) {
                updateOverviewStatus(
                    overviewSnapshot(
                        state = state,
                        stages = overviewStages,
                        currentIndex = currentOverviewIndex,
                        desiredIndex = desiredOverviewIndex,
                    ),
                )
            }

            pruneVisibleTiles(
                state = state,
                currentSession = session,
                tileMap = tileMap,
                rootTiles = rootTiles,
            )
            visibleTileCount = state.visibleTileCount()

            if (activationTileId == null && pendingTileIds.isNotEmpty() && isDetailStreamingIdleReady()) {
                val nextPendingId = pendingTileIds.first()
                val pendingTile = tileMap[nextPendingId]
                val pendingFile = pendingTileFiles[nextPendingId]
                if (pendingTile == null || pendingFile == null) {
                    pendingTileFiles.remove(nextPendingId)
                    pendingTileIds.remove(nextPendingId)
                } else {
                    activationTileId = nextPendingId
                    launch {
                        try {
                            val activated = activateTile(
                                state = state,
                                tile = pendingTile,
                                tileFile = pendingFile,
                                token = token,
                                updateViewBounds = false,
                            )
                            if (token != loadToken || !activated) {
                                return@launch
                            }

                            promoteResolvedTiles(state, tileMap, pendingTile.id)
                        } finally {
                            pendingTileFiles.remove(nextPendingId)
                            pendingTileIds.remove(nextPendingId)
                            activationTileId = null
                            visibleTileCount = state.visibleTileCount()
                        }
                    }
                }
            }

            val reservedTileIds = buildSet {
                addAll(downloadingTileIds)
                addAll(pendingTileIds)
                activationTileId?.let(::add)
            }

            val nextTiles = nextTilesToLoad(
                state = state,
                currentSession = session,
                rootTiles = rootTiles,
                refinementTiles = refinementTiles,
                reservedTileIds = reservedTileIds,
                downloadingIds = downloadingTileIds.toSet(),
            )

            if (nextTiles.isEmpty()) {
                if (pendingTileIds.isNotEmpty() || activationTileId != null) {
                    val pendingCount = pendingTileIds.size + if (activationTileId != null) 1 else 0
                    if (isDetailStreamingIdleReady()) {
                        updateTileStatus("Čekám na aktivaci ${pendingCount} stažených detail tiles")
                    } else if (isTouchGestureActive) {
                        updateTileStatus("Staženo ${pendingCount} detail tiles, čekám na puštění prstu + ${TileStreamingPolicy.DETAIL_STREAMING_IDLE_DELAY_MS}ms")
                    } else {
                        updateTileStatus("Staženo ${pendingCount} detail tiles, čekám na zastavení kamery (${detailStreamingIdleRemainingMs()}ms)")
                    }
                } else if (downloadingTileIds.isNotEmpty()) {
                    updateTileStatus("Čekám na ${downloadingTileIds.size} rozpracované downloady detail tiles")
                } else if (session.bootstrap.manifest.tiles.isNotEmpty()) {
                    updateTileStatus(
                        detailCandidateSnapshot(
                            state = state,
                            currentSession = session,
                            rootTiles = rootTiles,
                            refinementTiles = refinementTiles,
                            reservedTileIds = reservedTileIds,
                            downloadingIds = downloadingTileIds.toSet(),
                        ),
                    )
                }
                delay(100)
                continue
            }

            Log.d(
                TAG,
                "Selected ${nextTiles.size} detail candidates at zoom ${(state.zoomFactor() * 100).toInt()}%: ${nextTiles.joinToString { it.id }}",
            )

            nextTiles.forEachIndexed { index, tile ->
                if (tile.id !in downloadingTileIds) {
                    downloadingTileIds += tile.id
                }

                launch {
                    try {
                        val tileFile = downloadTile(
                            tile = tile,
                            tileIndex = index + 1,
                            tileCount = nextTiles.size,
                            token = token,
                        )
                        if (token != loadToken || tileFile == null) {
                            return@launch
                        }

                        if (!shouldDisplayTile(tile)) {
                            state.markTileResolved(tile.id)
                            Log.d(
                                TAG,
                                "Tile ${tile.id} kept hidden after download because overview error ${activeOverviewError} is better than tile error ${tile.error}",
                            )
                            updateTileStatus("Tile ${tile.id} stažena jen pro odemčení child tiles, overview je kvalitnější")
                            promoteResolvedTiles(state, tileMap, tile.id)
                            return@launch
                        }

                        pendingTileFiles[tile.id] = tileFile
                        if (tile.id !in pendingTileIds) {
                            pendingTileIds += tile.id
                        }
                        if (isDetailStreamingIdleReady()) {
                            updateTileStatus("Tile ${tile.id} stažena, čeká na aktivaci do scény")
                        } else if (isTouchGestureActive) {
                            updateTileStatus("Tile ${tile.id} stažena, čeká na puštění prstu")
                        } else {
                            updateTileStatus("Tile ${tile.id} stažena, čeká na zastavení kamery")
                        }
                    } finally {
                        downloadingTileIds.remove(tile.id)
                        visibleTileCount = state.visibleTileCount()
                    }
                }
            }

            val currentOverviewStage = overviewStages.getOrNull(currentOverviewIndex)
            val nextDetailModeActive = shouldUseDetailMode(
                state = state,
                tileMap = tileMap,
                currentOverviewStage = currentOverviewStage,
                currentDetailMode = detailModeActive,
            )
            if (nextDetailModeActive != detailModeActive) {
                detailModeActive = nextDetailModeActive
                Log.d(
                    TAG,
                    "Detail mode ${if (detailModeActive) "enabled" else "disabled"} zoom=${(state.zoomFactor() * 100).toInt()}% visibleTiles=${state.visibleTileCount()}",
                )
            }
            if (detailModeActive) {
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
            Log.d(TAG, "ModelViewer disposed")
        }
    }

    Box(modifier = modifier) {
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
                    Log.e(TAG, "Failed to load IBL/Skybox, using fallback", e)
                    scene.skybox = Skybox.Builder().color(0.53f, 0.81f, 0.92f, 1f).build(engine)
                }

                val materialProvider = UbershaderProvider(engine)
                val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
                val state = FilamentState(engine, renderer, scene, view, camera, assetLoader)
                filamentState = state

                var lastTouchX = 0f
                var lastTouchY = 0f
                var lastPinchDistance = 0f
                var lastFocusX = 0f
                var lastFocusY = 0f
                var activePointerId = MotionEvent.INVALID_POINTER_ID
                var isPinching = false

                fun markUserInteraction() {
                    lastUserInteractionAtMs = SystemClock.uptimeMillis()
                }

                fun getPinchDistance(event: MotionEvent): Float {
                    if (event.pointerCount < 2) return 0f
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    return sqrt(dx * dx + dy * dy)
                }

                fun getFocusPoint(event: MotionEvent): Pair<Float, Float> {
                    if (event.pointerCount < 2) return event.x to event.y
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    return focusX to focusY
                }

                val surfaceView = SurfaceView(ctx)
                val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
                val displayHelper = DisplayHelper(ctx)
                var swapChain: SwapChain? = null
                var isRenderLoopActive = false
                var frameCallback: Choreographer.FrameCallback? = null

                surfaceView.setOnTouchListener { viewRef, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            markUserInteraction()
                            isTouchGestureActive = true
                            activePointerId = event.getPointerId(0)
                            lastTouchX = event.x
                            lastTouchY = event.y
                            isPinching = false
                            true
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            markUserInteraction()
                            isTouchGestureActive = true
                            if (event.pointerCount >= 2) {
                                val (focusX, focusY) = getFocusPoint(event)
                                lastFocusX = focusX
                                lastFocusY = focusY
                                lastPinchDistance = getPinchDistance(event)
                                isPinching = true
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            markUserInteraction()
                            isTouchGestureActive = event.pointerCount > 0
                            when {
                                event.pointerCount >= 2 -> {
                                    val currentDistance = getPinchDistance(event)
                                    val (focusX, focusY) = getFocusPoint(event)
                                    if (!isPinching) {
                                        lastPinchDistance = currentDistance
                                        lastFocusX = focusX
                                        lastFocusY = focusY
                                        isPinching = true
                                    } else {
                                        if (lastPinchDistance > 0f && currentDistance > 0f) {
                                            state.zoomBy((lastPinchDistance / currentDistance).toDouble())
                                        }
                                        state.panBy(
                                            deltaX = focusX - lastFocusX,
                                            deltaY = focusY - lastFocusY,
                                            viewportWidth = viewRef.width,
                                            viewportHeight = viewRef.height,
                                        )
                                    }
                                    lastPinchDistance = currentDistance
                                    lastFocusX = focusX
                                    lastFocusY = focusY
                                }
                                event.pointerCount == 1 -> {
                                    val pointerIndex = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                                    val x = event.getX(pointerIndex)
                                    val y = event.getY(pointerIndex)
                                    val dx = x - lastTouchX
                                    val dy = y - lastTouchY
                                    state.cameraAngleX += dx * CAMERA_ROTATION_SENSITIVITY
                                    state.cameraAngleY = (state.cameraAngleY - dy * CAMERA_ROTATION_SENSITIVITY)
                                        .coerceIn(CAMERA_MIN_PITCH, CAMERA_MAX_PITCH)
                                    lastTouchX = x
                                    lastTouchY = y
                                    isPinching = false
                                }
                            }
                            state.updateCamera()
                            true
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            markUserInteraction()
                            isTouchGestureActive = (event.pointerCount - 1) > 0
                            if (event.pointerCount - 1 < 2) {
                                isPinching = false
                                lastPinchDistance = 0f
                                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                                if (remainingIndex in 0 until event.pointerCount) {
                                    activePointerId = event.getPointerId(remainingIndex)
                                    lastTouchX = event.getX(remainingIndex)
                                    lastTouchY = event.getY(remainingIndex)
                                } else {
                                    activePointerId = MotionEvent.INVALID_POINTER_ID
                                }
                            } else {
                                isPinching = false
                                lastPinchDistance = 0f
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            markUserInteraction()
                            isTouchGestureActive = false
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            isPinching = false
                            lastPinchDistance = 0f
                            viewRef.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            markUserInteraction()
                            isTouchGestureActive = false
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            isPinching = false
                            lastPinchDistance = 0f
                            false
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
                        isRenderLoopActive = true
                        frameCallback?.let(Choreographer.getInstance()::removeFrameCallback)

                        val callback = object : Choreographer.FrameCallback {
                            override fun doFrame(frameTimeNanos: Long) {
                                if (!isRenderLoopActive) return
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

                                if (isRenderLoopActive) {
                                    Choreographer.getInstance().postFrameCallback(this)
                                }
                            }
                        }
                        frameCallback = callback
                        Choreographer.getInstance().postFrameCallback(callback)
                    }

                    override fun onDetachedFromSurface() {
                        isRenderLoopActive = false
                        frameCallback?.let(Choreographer.getInstance()::removeFrameCallback)
                        frameCallback = null
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
            modifier = Modifier.fillMaxSize()
        )

        ComposeSurface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tiles: $visibleTileCount viditelných / ${downloadingTileIds.size} downloading / ${pendingTileIds.size + if (activationTileId != null) 1 else 0} pending")
                Text(overviewStatusMessage)
                Text(tileStatusMessage)
                Text("Gesta: 1 prst orbit, 2 prsty pan + zoom")
            }
        }
    }
}
