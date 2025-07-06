package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Container for page information in ComicInfo.xml.
 *
 * @property page List of individual page metadata entries
 */
@Serializable
@XmlSerialName("Pages", "", "")
data class Pages(
    @SerialName("Page")
    val page: List<PageInfo>
) {
    init {
        require(page.isNotEmpty()) { "Pages list cannot be empty" }
    }
}
