package com.example.bp

import com.example.bp.download.StreamTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStreamingPolicyTest {

    @Test
    fun effectiveMaxActiveTiles_usesManifestCount_forSmallTileSets() {
        val result = TileStreamingPolicy.effectiveMaxActiveTiles(
            manifestTileCount = 20,
            recommendedMaxActiveTiles = 8,
        )

        assertEquals(20, result)
    }

    @Test
    fun effectiveMaxActiveTiles_usesRecommendedBudget_forLargeTileSets() {
        val result = TileStreamingPolicy.effectiveMaxActiveTiles(
            manifestTileCount = 96,
            recommendedMaxActiveTiles = 24,
        )

        assertEquals(24, result)
    }

    @Test
    fun detailStreamingIdleRemainingMs_holdsFullWindow_whileTouchIsActive() {
        val remaining = TileStreamingPolicy.detailStreamingIdleRemainingMs(
            isTouchGestureActive = true,
            nowMs = 10_000L,
            lastUserInteractionAtMs = 1_000L,
        )

        assertEquals(TileStreamingPolicy.DETAIL_STREAMING_IDLE_DELAY_MS, remaining)
        assertFalse(
            TileStreamingPolicy.isDetailStreamingIdleReady(
                isTouchGestureActive = true,
                nowMs = 10_000L,
                lastUserInteractionAtMs = 1_000L,
            ),
        )
    }

    @Test
    fun detailStreamingIdleRemainingMs_becomesReady_afterGraceWindowExpires() {
        val remaining = TileStreamingPolicy.detailStreamingIdleRemainingMs(
            isTouchGestureActive = false,
            nowMs = 2_000L,
            lastUserInteractionAtMs = 1_300L,
        )

        assertEquals(0L, remaining)
        assertTrue(
            TileStreamingPolicy.isDetailStreamingIdleReady(
                isTouchGestureActive = false,
                nowMs = 2_000L,
                lastUserInteractionAtMs = 1_300L,
            ),
        )
    }

    @Test
    fun shouldUseDetailMode_requiresRefinementAndTriangleCoverage() {
        val root = tile(
            id = "root",
            depth = 0,
            triangleCount = 40,
        )
        val child = tile(
            id = "child",
            parentId = "root",
            depth = 1,
            triangleCount = 70,
        )

        assertFalse(
            TileStreamingPolicy.shouldUseDetailMode(
                zoomFactor = 0.8,
                visibleTiles = listOf(root),
                currentOverviewTriangleCount = 100,
                currentDetailMode = false,
            ),
        )

        assertTrue(
            TileStreamingPolicy.shouldUseDetailMode(
                zoomFactor = 0.8,
                visibleTiles = listOf(root, child),
                currentOverviewTriangleCount = 100,
                currentDetailMode = false,
            ),
        )
    }

    @Test
    fun shouldUseDetailMode_usesExitHysteresis_whenDetailModeIsAlreadyActive() {
        val childA = tile(
            id = "child-a",
            parentId = "root",
            depth = 1,
            triangleCount = 40,
        )
        val childB = tile(
            id = "child-b",
            parentId = "root",
            depth = 1,
            triangleCount = 45,
        )

        assertTrue(
            TileStreamingPolicy.shouldUseDetailMode(
                zoomFactor = 0.5,
                visibleTiles = listOf(childA, childB),
                currentOverviewTriangleCount = 100,
                currentDetailMode = true,
            ),
        )

        assertFalse(
            TileStreamingPolicy.shouldUseDetailMode(
                zoomFactor = 0.5,
                visibleTiles = listOf(childA.copy(triangleCount = 30), childB.copy(triangleCount = 40)),
                currentOverviewTriangleCount = 100,
                currentDetailMode = true,
            ),
        )
    }

    @Test
    fun nextTilesToLoad_prefersLargeRootSerially_and_blocksChildUntilParentIsReady() {
        val largeRoot = tile(
            id = "root-large",
            size = TileStreamingPolicy.LARGE_TILE_SERIAL_LOAD_BYTES + 1,
            triangleCount = 120,
            priority = 1,
        )
        val nearRoot = tile(
            id = "root-near",
            size = 4_096L,
            triangleCount = 80,
            priority = 2,
        )
        val blockedChild = tile(
            id = "child",
            parentId = nearRoot.id,
            depth = 1,
            size = 4_096L,
            triangleCount = 60,
            priority = 3,
        )

        val lookup = FakeTileLookup()
        val result = TileStreamingPolicy.nextTilesToLoad(
            zoomFactor = 0.9,
            visibleTileCount = 0,
            manifestTileCount = 20,
            recommendedMaxActiveTiles = 12,
            recommendedConcurrentTileRequests = 2,
            rootTiles = listOf(largeRoot, nearRoot),
            refinementTiles = listOf(blockedChild),
            reservedTileIds = emptySet(),
            downloadingIds = emptySet(),
            lookup = lookup,
            distanceScore = { tile ->
                when (tile.id) {
                    largeRoot.id -> 1.0
                    nearRoot.id -> 2.0
                    else -> 0.5
                }
            },
        )

        assertEquals(listOf(largeRoot), result)
        assertFalse(result.any { it.id == blockedChild.id })
    }

    private fun tile(
        id: String,
        parentId: String? = null,
        depth: Int = if (parentId == null) 0 else 1,
        size: Long = 1_024L,
        triangleCount: Int = 0,
        priority: Int = 0,
    ): StreamTile {
        return StreamTile(
            id = id,
            parentId = parentId,
            depth = depth,
            file = "$id.glb",
            url = "/$id.glb",
            size = size,
            ratio = 1.0,
            error = 0.0,
            geometricError = 0.0,
            triangleCount = triangleCount,
            priority = priority,
        )
    }

    private class FakeTileLookup(
        private val resolvedIds: Set<String> = emptySet(),
        private val visibleIds: Set<String> = emptySet(),
    ) : TileStreamingPolicy.TileLookup {
        override fun isResolved(tileId: String): Boolean = tileId in resolvedIds

        override fun isVisible(tileId: String): Boolean = tileId in visibleIds
    }
}
