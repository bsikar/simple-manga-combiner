package com.mangacombiner.data

/**
 * An expect class that defines the contract for a platform-specific
 * repository responsible for saving and loading a list of manually
 * marked NSFW book paths.
 */
expect class NsfwRepository {
    /**
     * Updates the NSFW status for a given book path.
     */
    fun setNsfw(bookPath: String, isNsfw: Boolean)

    /**
     * Loads the set of all book paths that have been manually marked as NSFW.
     */
    fun loadNsfwPaths(): Set<String>
}
