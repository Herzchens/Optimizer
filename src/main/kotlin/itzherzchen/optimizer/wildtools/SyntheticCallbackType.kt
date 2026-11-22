package itzherzchen.optimizer.wildtools

/**
 * Target callback identities wrapped inside WildTools' claimingPluginsBreakMethods list.
 */
enum class SyntheticCallbackType {
    /**
     * SuperiorSkyblock2 StackedBlocksListener (Priority HIGHEST).
     */
    STACKED_BLOCKS,

    /**
     * SuperiorSkyblock2 BlockChangesListener / SignsListener (Priority MONITOR).
     */
    ACCOUNTING
}
