package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Root element for ComicInfo.xml metadata used in CBZ files.
 * This format is widely supported by comic readers.
 *
 * @property Series The name of the manga series
 * @property Title The title of this specific volume/collection
 * @property PageCount Total number of pages in the archive
 * @property Pages Container for individual page metadata
 */
@Serializable
@XmlSerialName("ComicInfo", "", "")
data class ComicInfo(
    @XmlElement(true)
    val Series: String,

    @XmlElement(true)
    val Title: String,

    @XmlElement(true)
    val PageCount: Int,

    @XmlElement(true)
    val Pages: Pages
) {
    init {
        require(PageCount >= 0) { "PageCount must be non-negative" }
        require(Series.isNotBlank()) { "Series name cannot be blank" }
        require(Title.isNotBlank()) { "Title cannot be blank" }
    }
}

/**
 * Container for page information in ComicInfo.xml.
 *
 * @property Page List of individual page metadata entries
 */
@Serializable
@XmlSerialName("Pages", "", "")
data class Pages(
    @SerialName("Page")
    val Page: List<PageInfo>
) {
    init {
        require(Page.isNotEmpty()) { "Pages list cannot be empty" }
    }
}

/**
 * Metadata for a single page in a comic archive.
 *
 * @property Image Zero-based index of the page
 * @property Bookmark Optional bookmark text (typically chapter name)
 * @property Type Optional page type (e.g., "FrontCover", "Story")
 */
@Serializable
@XmlSerialName("Page", "", "")
data class PageInfo(
    @XmlSerialName("Image", "", "")
    val Image: Int,

    @XmlSerialName("Bookmark", "", "")
    val Bookmark: String? = null,

    @XmlSerialName("Type", "", "")
    val Type: String? = null
) {
    init {
        require(Image >= 0) { "Image index must be non-negative" }
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

/**
 * Root package element for OPF (Open Packaging Format) used in EPUB files.
 * This is a simplified representation focusing on the manifest section.
 *
 * @property metadata Optional metadata section (not used in current implementation)
 * @property manifest The manifest listing all resources in the EPUB
 * @property spine Optional spine section defining reading order (not used in current implementation)
 */
@Serializable
@XmlSerialName("package", "http://www.idpf.org/2007/opf", "")
data class OpfPackage(
    @XmlElement(false)
    val metadata: OpfMetadata? = null,

    @XmlElement(true)
    val manifest: Manifest,

    @XmlElement(false)
    val spine: OpfSpine? = null
)

/**
 * Metadata section of OPF (simplified, for future expansion).
 */
@Serializable
@XmlSerialName("metadata", "http://www.idpf.org/2007/opf", "")
data class OpfMetadata(
    @XmlElement(true)
    @SerialName("title")
    val title: String? = null,

    @XmlElement(true)
    @SerialName("creator")
    val creator: String? = null,

    @XmlElement(true)
    @SerialName("language")
    val language: String? = null
)

/**
 * Manifest section of OPF listing all resources.
 *
 * @property items List of items (files) in the EPUB
 */
@Serializable
@XmlSerialName("manifest", "http://www.idpf.org/2007/opf", "")
data class Manifest(
    @SerialName("item")
    val items: List<Item>
) {
    init {
        require(items.isNotEmpty()) { "Manifest must contain at least one item" }
    }
}

/**
 * Individual item (file) reference in the EPUB manifest.
 *
 * @property id Unique identifier for this item
 * @property href Relative path to the file within the EPUB
 * @property mediaType MIME type of the file
 */
@Serializable
@XmlSerialName("item", "http://www.idpf.org/2007/opf", "")
data class Item(
    @XmlSerialName("id", "", "")
    val id: String,

    @XmlSerialName("href", "", "")
    val href: String,

    @XmlSerialName("media-type", "", "")
    val mediaType: String
) {
    init {
        require(id.isNotBlank()) { "Item ID cannot be blank" }
        require(href.isNotBlank()) { "Item href cannot be blank" }
        require(mediaType.isNotBlank()) { "Item media-type cannot be blank" }
    }

    companion object {
        // Common MIME types used in EPUB
        const val MEDIA_TYPE_XHTML = "application/xhtml+xml"
        const val MEDIA_TYPE_NCX = "application/x-dtbncx+xml"
        const val MEDIA_TYPE_CSS = "text/css"
        const val MEDIA_TYPE_JPEG = "image/jpeg"
        const val MEDIA_TYPE_PNG = "image/png"
        const val MEDIA_TYPE_GIF = "image/gif"
        const val MEDIA_TYPE_WEBP = "image/webp"
        const val MEDIA_TYPE_SVG = "image/svg+xml"
    }
}

/**
 * Spine section of OPF defining reading order (simplified, for future expansion).
 */
@Serializable
@XmlSerialName("spine", "http://www.idpf.org/2007/opf", "")
data class OpfSpine(
    @XmlSerialName("toc", "", "")
    val toc: String? = null,

    @SerialName("itemref")
    val itemRefs: List<SpineItemRef> = emptyList()
)

/**
 * Reference to an item in the reading order.
 */
@Serializable
@XmlSerialName("itemref", "http://www.idpf.org/2007/opf", "")
data class SpineItemRef(
    @XmlSerialName("idref", "", "")
    val idref: String,

    @XmlSerialName("linear", "", "")
    val linear: String? = null
)
