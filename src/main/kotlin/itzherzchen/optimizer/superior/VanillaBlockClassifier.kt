package itzherzchen.optimizer.superior

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI
import com.bgsoftware.superiorskyblock.api.key.Key
import org.bukkit.Material
import org.bukkit.block.Block

enum class ClassificationStatus {
    SAFE_VANILLA,
    SPAWNER,
    CUSTOM_OR_NONSTANDARD
}

data class ClassificationResult(
    val status: ClassificationStatus,
    val key: Key?
)

/**
 * Strict fail-closed classifier for vanilla blocks.
 *
 * Implements Plan.md Section 5.2:
 * - Accept only exact MaterialKey class (`rawKey.javaClass.name == targetClassName`).
 * - Match `globalKey == block.type.name`.
 * - Empty, null, or "0" `subKey`.
 * - Non-SPAWNER material (both Material.SPAWNER and material name contains 'SPAWNER').
 * - Any null, custom key, ItemsAdder key, spawner, exception, or nonstandard key -> original.
 * - Never normalizes unknown keys into vanilla keys.
 * - Zero heap allocation per block on hot path via safeVanillaKey and isSpawner.
 */
class VanillaBlockClassifier(
    private val targetClassName: String = DEFAULT_MATERIAL_KEY_CLASS_NAME,
    private val keyProvider: (Block) -> Any? = { block ->
        SuperiorSkyblockAPI.getKeys()?.getKey(block)
    }
) {
    companion object {
        const val DEFAULT_MATERIAL_KEY_CLASS_NAME = "com.bgsoftware.superiorskyblock.core.key.types.MaterialKey"
    }

    /**
     * Spawner check without querying key or allocating objects.
     */
    fun isSpawner(material: Material): Boolean =
        material == Material.SPAWNER || material.name.contains("SPAWNER")

    /**
     * Safe zero-allocation classification for the hot path.
     * Returns the non-null Key if and only if the block is a strictly validated vanilla block.
     * Returns null if spawner, custom key, ItemsAdder key, non-standard key, or on error.
     */
    fun safeVanillaKey(block: Block): Key? {
        val mat = block.type
        if (isSpawner(mat)) {
            return null
        }

        val rawKey = try {
            keyProvider(block)
        } catch (_: Throwable) {
            null
        } ?: return null

        if (rawKey.javaClass.name != targetClassName) {
            return null
        }

        val key = rawKey as? Key ?: return null

        val globalKey = key.globalKey
        if (globalKey != mat.name) {
            return null
        }

        val subKey = key.subKey
        val isSubKeyVanilla = subKey.isNullOrEmpty() || subKey == "0"
        if (!isSubKeyVanilla) {
            return null
        }

        return key
    }

    /**
     * Backward-compatible result-allocating classification for tests or audit inspection.
     */
    fun classify(block: Block): ClassificationResult {
        val mat = block.type
        if (isSpawner(mat)) {
            return ClassificationResult(ClassificationStatus.SPAWNER, null)
        }
        val key = safeVanillaKey(block)
        return if (key != null) {
            ClassificationResult(ClassificationStatus.SAFE_VANILLA, key)
        } else {
            ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)
        }
    }
}
