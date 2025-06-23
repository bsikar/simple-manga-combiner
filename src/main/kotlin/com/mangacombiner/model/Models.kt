package com.mangacombiner.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ComicInfo")
data class ComicInfo(
    @XmlElement(true) val Series: String,
    @XmlElement(true) val Title: String,
    @XmlElement(true) val PageCount: Int,
    @XmlElement(true) val Pages: Pages
)

@Serializable
@XmlSerialName("Pages")
data class Pages(
    @SerialName("Page")
    val Page: List<PageInfo>
)

@Serializable
@XmlSerialName("Page")
data class PageInfo(
    @XmlSerialName("Image", "", "") val Image: Int,
    @XmlSerialName("Bookmark", "", "") val Bookmark: String? = null,
    @XmlSerialName("Type", "", "") val Type: String? = null
)

@Serializable
@XmlSerialName("package", "http://www.idpf.org/2007/opf", "")
data class OpfPackage(
    val manifest: Manifest
)

@Serializable
@XmlSerialName("manifest", "http://www.idpf.org/2007/opf", "")
data class Manifest(
    @SerialName("item")
    val items: List<Item>
)

@Serializable
@XmlSerialName("item", "http://www.idpf.org/2007/opf", "")
data class Item(
    val id: String,
    val href: String,
    @XmlSerialName("media-type", "", "")
    val mediaType: String
)
