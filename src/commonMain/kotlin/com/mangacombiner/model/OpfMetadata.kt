package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Metadata section of OPF (Open Packaging Format).
 */
@Serializable
@XmlSerialName("metadata", XmlConstants.OPF_NAMESPACE, "")
data class OpfMetadata(
    @XmlElement(true)
    @SerialName("title")
    val title: String? = Defaults.TITLE,

    @XmlElement(true)
    @SerialName("creator")
    val creator: String = Defaults.CREATOR,

    @XmlElement(true)
    @SerialName("language")
    val language: String? = Defaults.LANGUAGE
) {
    companion object Defaults {
        val TITLE: String? = null
        const val CREATOR: String = "MangaCombiner"
        val LANGUAGE: String? = null
    }
}
