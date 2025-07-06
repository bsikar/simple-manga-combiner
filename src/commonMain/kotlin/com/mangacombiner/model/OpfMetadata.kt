package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

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
