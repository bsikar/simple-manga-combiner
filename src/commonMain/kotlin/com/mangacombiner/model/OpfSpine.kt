package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Spine section of OPF defining reading order.
 */
@Serializable
@XmlSerialName("spine", XmlConstants.OPF_NAMESPACE, "")
data class OpfSpine(
    @XmlSerialName("toc", "", "")
    val toc: String? = null,

    @SerialName("itemref")
    val itemRefs: List<SpineItemRef> = emptyList()
)
