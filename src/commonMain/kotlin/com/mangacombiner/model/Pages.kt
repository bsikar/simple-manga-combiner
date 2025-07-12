package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Container for page information in ComicInfo.xml.
 */
@Serializable
@XmlSerialName("Pages", XmlConstants.COMIC_INFO_NAMESPACE, "")
data class Pages(
    @SerialName("Page")
    val page: List<PageInfo>
) {
    init {
        require(page.isNotEmpty()) { "Pages list cannot be empty" }
    }
}
