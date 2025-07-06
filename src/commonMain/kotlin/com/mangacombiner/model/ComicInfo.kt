package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Root element for ComicInfo.xml metadata used in CBZ files.
 * This format is widely supported by comic readers.
 *
 * @property series The name of the manga series
 * @property title The title of this specific volume/collection
 * @property pageCount Total number of pages in the archive
 * @property pages Container for individual page metadata
 */
@Serializable
@XmlSerialName("ComicInfo", "", "")
data class ComicInfo(
    @XmlElement(true)
    @XmlSerialName("Series", "", "")
    val series: String,

    @XmlElement(true)
    @XmlSerialName("Title", "", "")
    val title: String,

    @XmlElement(true)
    @XmlSerialName("PageCount", "", "")
    val pageCount: Int,

    @XmlElement(true)
    @XmlSerialName("Pages", "", "")
    val pages: Pages
) {
    init {
        require(pageCount >= 0) { "PageCount must be non-negative" }
        require(series.isNotBlank()) { "Series name cannot be blank" }
        require(title.isNotBlank()) { "Title cannot be blank" }
    }
}
