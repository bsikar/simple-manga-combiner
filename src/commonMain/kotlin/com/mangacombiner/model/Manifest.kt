package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Manifest section of OPF listing all resources.
 */
@Serializable
@XmlSerialName("manifest", XmlConstants.OPF_NAMESPACE, "")
data class Manifest(
    @SerialName("item")
    val items: List<Item>
) {
    init {
        require(items.isNotEmpty()) { "Manifest must contain at least one item" }
    }
}
