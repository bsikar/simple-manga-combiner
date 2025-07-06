package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Metadata for a single page in a comic archive.
 *
 * @property image Zero-based index of the page
 * @property bookmark Optional bookmark text (typically chapter name)
 * @property type Optional page type (e.g., "FrontCover", "Story")
 */
@Serializable
@XmlSerialName("Page", "", "")
data class PageInfo(
    @XmlSerialName("Image", "", "")
    val image: Int,

    @XmlSerialName("Bookmark", "", "")
    val bookmark: String? = null,

    @XmlSerialName("Type", "", "")
    val type: String? = null
) {
    init {
        require(image >= 0) { "Image index must be non-negative" }
    }

    companion object {
        // Standard page types used in ComicInfo.xml
        const val TYPE_FRONT_COVER = "FrontCover"
        const val TYPE_INNER_COVER = "InnerCover"
        const val TYPE_ROUNDUP = "Roundup"
        const val TYPE_STORY = "Story"
        const val TYPE_ADVERTISEMENT = "Advertisement"
        const val TYPE_EDITORIAL = "Editorial"
        const val TYPE_LETTERS = "Letters"
        const val TYPE_PREVIEW = "Preview"
        const val TYPE_BACK_COVER = "BackCover"
        const val TYPE_OTHER = "Other"
        const val TYPE_DELETED = "Deleted"
    }
}
