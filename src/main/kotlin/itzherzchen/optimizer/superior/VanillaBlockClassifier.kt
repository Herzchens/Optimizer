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
 * - Empty or null `subKey`.
 * - Non-SPAWNER material (both Material.SPAWNER and material name contains 'SPAWNER').
 * - Any null, custom key, ItemsAdder key, spawner, exception, or nonstandard key -> original.
 * - Never normalizes unknown keys into vanilla keys.
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

    fun classify(block: Block): ClassificationResult {
        // 1. Spawner gate
        val mat = block.type
        if (mat == Material.SPAWNER || mat.name.contains("SPAWNER")) {
            return ClassificationResult(ClassificationStatus.SPAWNER, null)
        }

        // 2. Query key via SuperiorSkyblock KeysManager
        val rawKey: Any? = try {
            keyProvider(block)
        } catch (_: Throwable) {
            return ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)
        }

        if (rawKey == null) {
            return ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)
        }

        // 3. Exact class equality: must be exactly MaterialKey, not a subclass or custom Key
        val rawClassName = rawKey.javaClass.name
        val isExact = rawClassName == targetClassName
        if (!isExact) {
            return ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)
        }

        val key = rawKey as? Key ?: return ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)

        // 4. Strict member validation: globalKey must match block.type.name exactly.
        // In SuperiorSkyblock2 b963, MaterialKey.of(Material, 0, BLOCK) sets durabilityAsString = "0".
        // Therefore, safe vanilla keys have subKey as null, empty, or "0". Custom or legacy keys have other subKeys.
        val globalKey = key.globalKey
        val subKey = key.subKey

        if (globalKey != mat.name) {
            return ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)
        }

        val isSubKeyVanilla = subKey.isNullOrEmpty() || subKey == "0"
        if (!isSubKeyVanilla) {
            return ClassificationResult(ClassificationStatus.CUSTOM_OR_NONSTANDARD, null)
        }

        return ClassificationResult(ClassificationStatus.SAFE_VANILLA, key)
    }
}
