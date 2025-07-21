package com.mangacombiner.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("collection", "DAV:", "")
class WebDavCollection

@Serializable
@XmlSerialName("resourcetype", "DAV:", "")
data class WebDavResourceType(
    @XmlSerialName("collection", "DAV:", "")
    val collection: WebDavCollection? = null
)

@Serializable
@XmlSerialName("prop", "DAV:", "")
data class WebDavProp(
    @XmlElement(true)
    @XmlSerialName("displayname", "DAV:", "")
    val displayName: String? = null,

    @XmlElement(true)
    @XmlSerialName("getcontentlength", "DAV:", "")
    val contentLength: Long? = 0,

    @XmlElement(true)
    @XmlSerialName("getlastmodified", "DAV:", "")
    val lastModified: String? = "",

    @XmlElement(true)
    @XmlSerialName("resourcetype", "DAV:", "")
    val resourceType: WebDavResourceType
)

@Serializable
@XmlSerialName("propstat", "DAV:", "")
data class WebDavPropstat(
    @XmlSerialName("prop", "DAV:", "")
    val prop: WebDavProp
)

@Serializable
@XmlSerialName("response", "DAV:", "")
data class WebDavResponse(
    @XmlElement(true)
    @XmlSerialName("href", "DAV:", "")
    val href: String,

    @XmlSerialName("propstat", "DAV:", "")
    val propstat: WebDavPropstat
)

@Serializable
@XmlSerialName("multistatus", "DAV:", "")
data class WebDavMultiStatus(
    @XmlSerialName("response", "DAV:", "")
    val responses: List<WebDavResponse> = emptyList()
)
