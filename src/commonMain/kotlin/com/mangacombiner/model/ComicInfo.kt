package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Root element for ComicInfo.xml metadata used in CBZ files.
 */
@Serializable
@XmlSerialName("ComicInfo", XmlConstants.COMIC_INFO_NAMESPACE, "")
data class ComicInfo(
    @XmlElement(true)
    @XmlSerialName("Series", XmlConstants.COMIC_INFO_NAMESPACE, "")
    val series: String,

    @XmlElement(true)
    @XmlSerialName("Title", XmlConstants.COMIC_INFO_NAMESPACE, "")
    val title: String,

    @XmlElement(true)
    @XmlSerialName("Web", XmlConstants.COMIC_INFO_NAMESPACE, "")
    val web: String? = null,

    @XmlElement(true)
    @XmlSerialName("PageCount", XmlConstants.COMIC_INFO_NAMESPACE, "")
    val pageCount: Int,

    @XmlElement(true)
    @XmlSerialName("Pages", XmlConstants.COMIC_INFO_NAMESPACE, "")
    val pages: Pages
) {
    init {
        require(pageCount >= 0) { "PageCount must be non-negative" }
        require(series.isNotBlank()) { "Series name cannot be blank" }
        require(title.isNotBlank()) { "Title cannot be blank" }
    }
}
