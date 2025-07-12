package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Individual item (file) reference in the EPUB manifest.
 */
@Serializable
@XmlSerialName("item", XmlConstants.OPF_NAMESPACE, "")
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
