package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Reference to an item in the reading order.
 */
@Serializable
@XmlSerialName("itemref", "http://www.idpf.org/2007/opf", "")
data class SpineItemRef(
    @XmlSerialName("idref", "", "")
    val idref: String,

    @XmlSerialName("linear", "", "")
    val linear: String? = null
)
