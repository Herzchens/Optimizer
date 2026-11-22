package itzherzchen.optimizer.runtime

import com.bgsoftware.superiorskyblock.api.island.Island
import org.bukkit.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class ChunkCoverageTest {

    private lateinit var coverage: ChunkCoverage
    private lateinit var world: World
    private lateinit var island: Island

    @BeforeEach
    fun setup() {
        coverage = ChunkCoverage(16)
        world = mock(World::class.java)
        island = mock(Island::class.java)
    }

    @Test
    fun `empty coverage returns false for any chunk`() {
        assertFalse(coverage.isCovered(world, 0, 0, island))
        assertFalse(coverage.isCovered(world, -5, 12, island))
        assertEquals(0, coverage.count)
    }

    @Test
    fun `marking chunk covered returns true for subsequent lookups with exact identity`() {
        val marked = coverage.markCovered(world, 3, -7, island)
        assertTrue(marked)
        assertEquals(1, coverage.count)

        // Same chunk, world, and island must be covered
        assertTrue(coverage.isCovered(world, 3, -7, island))

        // Different chunk coordinates must NOT be covered
        assertFalse(coverage.isCovered(world, 3, -8, island))
        assertFalse(coverage.isCovered(world, 4, -7, island))

        // Different world must NOT be covered
        val otherWorld = mock(World::class.java)
        assertFalse(coverage.isCovered(otherWorld, 3, -7, island))

        // Different island must NOT be covered
        val otherIsland = mock(Island::class.java)
        assertFalse(coverage.isCovered(world, 3, -7, otherIsland))
    }

    @Test
    fun `negative chunk coordinates are hashed uniquely without collision`() {
        coverage.markCovered(world, -1, -1, island)
        coverage.markCovered(world, 1, 1, island)
        coverage.markCovered(world, -1, 1, island)
        coverage.markCovered(world, 1, -1, island)

        assertEquals(4, coverage.count)
        assertTrue(coverage.isCovered(world, -1, -1, island))
        assertTrue(coverage.isCovered(world, 1, 1, island))
        assertTrue(coverage.isCovered(world, -1, 1, island))
        assertTrue(coverage.isCovered(world, 1, -1, island))

        assertFalse(coverage.isCovered(world, 0, 0, island))
    }

    @Test
    fun `marking same chunk repeatedly is idempotent and does not increase count`() {
        assertTrue(coverage.markCovered(world, 5, 5, island))
        assertEquals(1, coverage.count)

        assertTrue(coverage.markCovered(world, 5, 5, island))
        assertEquals(1, coverage.count)
    }

    @Test
    fun `overflow fallback safely rejects anchoring past capacity`() {
        val smallCoverage = ChunkCoverage(4)

        for (i in 0 until 4) {
            assertTrue(smallCoverage.markCovered(world, i, i, island))
        }
        assertEquals(4, smallCoverage.count)

        // 5th distinct chunk exceeds capacity
        val overflowResult = smallCoverage.markCovered(world, 99, 99, island)
        assertFalse(overflowResult)
        assertEquals(4, smallCoverage.count)
        assertFalse(smallCoverage.isCovered(world, 99, 99, island))
    }

    @Test
    fun `reset clears all entries and references`() {
        coverage.markCovered(world, 1, 2, island)
        coverage.markCovered(world, 3, 4, island)
        assertEquals(2, coverage.count)

        coverage.reset()
        assertEquals(0, coverage.count)
        assertFalse(coverage.isCovered(world, 1, 2, island))
        assertFalse(coverage.isCovered(world, 3, 4, island))
    }
}
