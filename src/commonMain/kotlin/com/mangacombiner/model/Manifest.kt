package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

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
