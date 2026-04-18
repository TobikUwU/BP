package com.example.bp

import com.example.bp.download.StreamTile

internal object TileStreamingPolicy {
    const val LARGE_TILE_SERIAL_LOAD_BYTES = 24L * 1024L * 1024L
    const val MAX_ACTIVE_TILES_HARD_LIMIT = 64
    const val TILE_PRUNE_HYSTERESIS = 6
    const val TILE_PRUNE_DISABLED_THRESHOLD = 64
    const val DETAIL_MODE_ENTER_ZOOM = 0.58
    const val DETAIL_MODE_EXIT_ZOOM = 0.46
    const val DETAIL_MODE_ENTER_TRIANGLE_RATIO = 1.02
    const val DETAIL_MODE_EXIT_TRIANGLE_RATIO = 0.82
    const val DETAIL_STREAMING_IDLE_DELAY_MS = 500L
    const val TILE_ACTIVATION_IDLE_DELAY_MS = 260L
    const val TILE_ACTIVATION_SPACING_MS = 90L

    interface TileLookup {
        fun isResolved(tileId: String): Boolean
        fun isVisible(tileId: String): Boolean
    }

    fun effectiveMaxActiveTiles(
        manifestTileCount: Int,
        recommendedMaxActiveTiles: Int,
    ): Int {
        val normalizedManifestTileCount = manifestTileCount.coerceAtLeast(4)
        val recommended = recommendedMaxActiveTiles
            .coerceAtLeast(4)
            .coerceAtMost(MAX_ACTIVE_TILES_HARD_LIMIT)

        return if (normalizedManifestTileCount <= TILE_PRUNE_DISABLED_THRESHOLD) {
            normalizedManifestTileCount.coerceAtMost(MAX_ACTIVE_TILES_HARD_LIMIT)
        } else {
            recommended
        }
    }

    fun detailStreamingIdleRemainingMs(
        isTouchGestureActive: Boolean,
        nowMs: Long,
        lastUserInteractionAtMs: Long,
    ): Long {
        if (isTouchGestureActive) {
            return DETAIL_STREAMING_IDLE_DELAY_MS
        }

        val sinceInteraction = nowMs - lastUserInteractionAtMs
        return (DETAIL_STREAMING_IDLE_DELAY_MS - sinceInteraction).coerceAtLeast(0L)
    }

    fun isDetailStreamingIdleReady(
        isTouchGestureActive: Boolean,
        nowMs: Long,
        lastUserInteractionAtMs: Long,
    ): Boolean {
        return !isTouchGestureActive &&
            detailStreamingIdleRemainingMs(
                isTouchGestureActive = isTouchGestureActive,
                nowMs = nowMs,
                lastUserInteractionAtMs = lastUserInteractionAtMs,
            ) <= 0L
    }

    fun detailIdleLabel(
        isTouchGestureActive: Boolean,
        nowMs: Long,
        lastUserInteractionAtMs: Long,
    ): String {
        if (isTouchGestureActive) {
            return "touch-hold"
        }

        val remaining = detailStreamingIdleRemainingMs(
            isTouchGestureActive = false,
            nowMs = nowMs,
            lastUserInteractionAtMs = lastUserInteractionAtMs,
        )
        return if (remaining <= 0L) "ready" else "wait=${remaining}ms"
    }

    fun targetVisibleTileCount(
        zoomFactor: Double,
        rootTileCount: Int,
        maxActiveTiles: Int,
    ): Int {
        val minimumVisible = minOf(rootTileCount, (maxActiveTiles / 2).coerceAtLeast(4))
        return (minimumVisible + ((maxActiveTiles - minimumVisible) * zoomFactor))
            .toInt()
            .coerceAtLeast(minimumVisible)
            .coerceAtMost(maxActiveTiles)
    }

    fun shouldUseDetailMode(
        zoomFactor: Double,
        visibleTiles: List<StreamTile>,
        currentOverviewTriangleCount: Int?,
        currentDetailMode: Boolean,
    ): Boolean {
        val requiredZoom = if (currentDetailMode) DETAIL_MODE_EXIT_ZOOM else DETAIL_MODE_ENTER_ZOOM
        if (zoomFactor < requiredZoom) return false
        if (visibleTiles.isEmpty()) return false

        val hasRefinementVisible = visibleTiles.any { tile ->
            tile.parentId != null || tile.depth > 0
        }
        if (!hasRefinementVisible) return false

        val visibleTriangles = visibleTiles.sumOf { tile ->
            tile.triangleCount.coerceAtLeast(0)
        }
        val overviewTriangles = currentOverviewTriangleCount?.coerceAtLeast(1) ?: 0
        if (overviewTriangles <= 0) return true

        val requiredTriangles = if (currentDetailMode) {
            (overviewTriangles * DETAIL_MODE_EXIT_TRIANGLE_RATIO).toInt()
        } else {
            (overviewTriangles * DETAIL_MODE_ENTER_TRIANGLE_RATIO).toInt()
        }

        return visibleTriangles >= requiredTriangles
    }

    fun nextTilesToLoad(
        zoomFactor: Double,
        visibleTileCount: Int,
        manifestTileCount: Int,
        recommendedMaxActiveTiles: Int,
        recommendedConcurrentTileRequests: Int,
        rootTiles: List<StreamTile>,
        refinementTiles: List<StreamTile>,
        reservedTileIds: Set<String>,
        downloadingIds: Set<String>,
        lookup: TileLookup,
        distanceScore: (StreamTile) -> Double,
    ): List<StreamTile> {
        val maxActiveTiles = effectiveMaxActiveTiles(
            manifestTileCount = manifestTileCount,
            recommendedMaxActiveTiles = recommendedMaxActiveTiles,
        )
        val totalVisibleTiles = visibleTileCount + reservedTileIds.size
        if (totalVisibleTiles >= maxActiveTiles) return emptyList()

        val targetVisible = targetVisibleTileCount(
            zoomFactor = zoomFactor,
            rootTileCount = rootTiles.size,
            maxActiveTiles = maxActiveTiles,
        )
        val loadBudget = (targetVisible - totalVisibleTiles).coerceAtLeast(0)
        if (loadBudget == 0) return emptyList()

        val maxConcurrent = recommendedConcurrentTileRequests.coerceIn(1, 2)
        val availableRequestSlots = (maxConcurrent - downloadingIds.size).coerceAtLeast(0)
        if (availableRequestSlots == 0) return emptyList()

        val orderedCandidates = (rootTiles + refinementTiles)
            .asSequence()
            .filter { !lookup.isResolved(it.id) }
            .filter { it.id !in reservedTileIds }
            .filter { tile ->
                val parentId = tile.parentId
                parentId == null || lookup.isResolved(parentId) || lookup.isVisible(parentId)
            }
            .sortedWith(
                compareBy<StreamTile>(
                    { if (it.parentId == null) 0 else 1 },
                    distanceScore,
                    { it.priority.takeIf { value -> value > 0 } ?: Int.MAX_VALUE },
                    { it.depth },
                ),
            )
            .toList()

        if (orderedCandidates.isEmpty()) {
            return emptyList()
        }

        val requestBudget = if (orderedCandidates.first().size >= LARGE_TILE_SERIAL_LOAD_BYTES) {
            1
        } else {
            availableRequestSlots
        }

        return orderedCandidates.take(loadBudget.coerceAtMost(requestBudget))
    }
}
