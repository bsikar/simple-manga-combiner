package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Root package element for OPF (Open Packaging Format) used in EPUB files.
 * This is a simplified representation focusing on the manifest section.
 *
 * @property metadata Optional metadata section (not used in current implementation)
 * @property manifest The manifest listing all resources in the EPUB
 * @property spine Optional spine section defining reading order (not used in current implementation)
 */
@Serializable
@XmlSerialName("package", "http://www.idpf.org/2007/opf", "")
data class OpfPackage(
    @XmlElement(false)
    val metadata: OpfMetadata? = null,

    @XmlElement(true)
    val manifest: Manifest,

    @XmlElement(false)
    val spine: OpfSpine? = null
)
