package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Root package element for OPF (Open Packaging Format) used in EPUB files.
 */
@Serializable
@XmlSerialName("package", XmlConstants.OPF_NAMESPACE, "")
data class OpfPackage(
    @XmlElement(false)
    val metadata: OpfMetadata? = null,

    @XmlElement(true)
    val manifest: Manifest,

    @XmlElement(false)
    val spine: OpfSpine? = null
)
