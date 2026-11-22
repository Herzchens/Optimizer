package itzherzchen.optimizer.superior

import com.bgsoftware.superiorskyblock.api.key.Key
import org.bukkit.Material
import org.bukkit.block.Block
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class VanillaBlockClassifierTest {

    open class DummyVanillaKey(
        private val global: String,
        private val sub: String? = null
    ) : Key {
        override fun getGlobalKey(): String = global
        override fun getSubKey(): String? = sub
        override fun compareTo(other: Key): Int = 0
    }

    class SubclassVanillaKey(global: String) : DummyVanillaKey(global, null)

    private val testTargetClassName = DummyVanillaKey::class.java.name

    @Test
    fun `safe vanilla block with exact target class matches`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.STONE)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { DummyVanillaKey("STONE", "") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.SAFE_VANILLA, result.status)
        assertNotNull(result.key)
        assertEquals("STONE", result.key?.globalKey)
    }

    @Test
    fun `safe vanilla block with null subKey matches`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.DIRT)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { DummyVanillaKey("DIRT", null) }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.SAFE_VANILLA, result.status)
        assertNotNull(result.key)
        assertTrue(result.key?.subKey.isNullOrEmpty())
    }

    @Test
    fun `safe vanilla block with subKey '0' matches (real SuperiorSkyblock2 b963 behavior)`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.STONE)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { DummyVanillaKey("STONE", "0") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.SAFE_VANILLA, result.status)
        assertNotNull(result.key)
        assertEquals("STONE", result.key?.globalKey)
        assertEquals("0", result.key?.subKey)
    }

    @Test
    fun `non-zero numeric subKey (e-g- legacy data value 1) is classified as CUSTOM_OR_NONSTANDARD`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.WHITE_WOOL)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { DummyVanillaKey("WHITE_WOOL", "1") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }

    @Test
    fun `subclass of target class is rejected for exact class equality invariant`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.STONE)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { SubclassVanillaKey("STONE") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }

    @Test
    fun `foreign Key class is classified as CUSTOM_OR_NONSTANDARD`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.OAK_LOG)

        val foreignKey = mock(Key::class.java)
        `when`(foreignKey.globalKey).thenReturn("OAK_LOG")
        `when`(foreignKey.subKey).thenReturn("")

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { foreignKey }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }

    @Test
    fun `spawner block is classified as SPAWNER without querying key`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.SPAWNER)

        var providerCalled = false
        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = {
                providerCalled = true
                null
            }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.SPAWNER, result.status)
        assertNull(result.key)
        assertFalse(providerCalled, "keyProvider must not be queried for SPAWNER")
    }

    @Test
    fun `custom key with non-empty subKey (e-g- ItemsAdder) is classified as CUSTOM_OR_NONSTANDARD`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.DIAMOND_ORE)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { DummyVanillaKey("DIAMOND_ORE", "custom_ruby") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }

    @Test
    fun `key globalKey mismatch is classified as CUSTOM_OR_NONSTANDARD`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.GOLD_ORE)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { DummyVanillaKey("IRON_ORE", "") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }

    @Test
    fun `null key is classified as CUSTOM_OR_NONSTANDARD`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.BEDROCK)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { null }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }

    @Test
    fun `exception in key provider fails closed to CUSTOM_OR_NONSTANDARD`() {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(Material.STONE)

        val classifier = VanillaBlockClassifier(
            targetClassName = testTargetClassName,
            keyProvider = { throw RuntimeException("KeysManager database error") }
        )

        val result = classifier.classify(block)
        assertEquals(ClassificationStatus.CUSTOM_OR_NONSTANDARD, result.status)
        assertNull(result.key)
    }
}
