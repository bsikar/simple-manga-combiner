package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Reference to an item in the reading order.
 */
@Serializable
@XmlSerialName("itemref", XmlConstants.OPF_NAMESPACE, "")
data class SpineItemRef(
    @XmlSerialName("idref", "", "")
    val idref: String,

    @XmlSerialName("linear", "", "")
    val linear: String? = null
)
