package com.mangacombiner.data

/**
 * An expect class that defines the contract for a platform-specific
 * repository responsible for saving and loading a list of manually
 * marked "safe" book paths, which overrides the NSFW filter.
 */
expect class SafeOverrideRepository {
    /**
     * Updates the safe override status for a given book path.
     */
    fun setSafe(bookPath: String, isSafe: Boolean)

    /**
     * Loads the set of all book paths that have been manually marked as safe.
     */
    fun loadSafePaths(): Set<String>
}
