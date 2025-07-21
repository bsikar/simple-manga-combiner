package com.mangacombiner.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Custom serializer for Long values that handles empty strings and invalid numeric values.
 * WebDAV servers sometimes return empty strings for numeric properties.
 */
object SafeLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SafeLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long {
        val stringValue = decoder.decodeString().trim()
        return when {
            stringValue.isEmpty() || stringValue.isBlank() -> 0L
            else -> stringValue.toLongOrNull() ?: 0L
        }
    }
}

/**
 * Custom serializer for String values that handles null and empty cases gracefully.
 * Ensures consistent string handling across different WebDAV server implementations.
 */
object SafeStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SafeString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value ?: "")
    }

    override fun deserialize(decoder: Decoder): String? {
        val stringValue = decoder.decodeString().trim()
        return if (stringValue.isEmpty() || stringValue.isBlank()) null else stringValue
    }
}

/**
 * Represents a WebDAV collection element.
 * Used to identify directory/folder resources in WebDAV responses.
 */
@Serializable
@XmlSerialName("collection", "DAV:", "")
class WebDavCollection

/**
 * Represents the resource type property that indicates whether a resource is a collection (directory).
 * Contains an optional collection element for directories/folders.
 */
@Serializable
@XmlSerialName("resourcetype", "DAV:", "")
data class WebDavResourceType(
    @XmlSerialName("collection", "DAV:", "")
    val collection: WebDavCollection? = null
)

/**
 * Contains the properties of a WebDAV resource including display name, content length,
 * last modified date, and resource type information.
 * Uses custom serializers to handle malformed or empty values from various WebDAV servers.
 */
@Serializable
@XmlSerialName("prop", "DAV:", "")
data class WebDavProp(
    @XmlElement(true)
    @XmlSerialName("displayname", "DAV:", "")
    @Serializable(with = SafeStringSerializer::class)
    val displayName: String? = null,

    @XmlElement(true)
    @XmlSerialName("getcontentlength", "DAV:", "")
    @Serializable(with = SafeLongSerializer::class)
    val contentLength: Long = 0,

    @XmlElement(true)
    @XmlSerialName("getlastmodified", "DAV:", "")
    @Serializable(with = SafeStringSerializer::class)
    val lastModified: String? = null,

    @XmlElement(true)
    @XmlSerialName("resourcetype", "DAV:", "")
    val resourceType: WebDavResourceType = WebDavResourceType(),

    @XmlElement(true)
    @XmlSerialName("getcontenttype", "DAV:", "")
    @Serializable(with = SafeStringSerializer::class)
    val contentType: String? = null,

    @XmlElement(true)
    @XmlSerialName("creationdate", "DAV:", "")
    @Serializable(with = SafeStringSerializer::class)
    val creationDate: String? = null,

    @XmlElement(true)
    @XmlSerialName("getetag", "DAV:", "")
    @Serializable(with = SafeStringSerializer::class)
    val etag: String? = null
)

/**
 * Represents a property status block within a WebDAV response.
 * Contains the properties and the HTTP status code for those properties.
 */
@Serializable
@XmlSerialName("propstat", "DAV:", "")
data class WebDavPropstat(
    @XmlSerialName("prop", "DAV:", "")
    val prop: WebDavProp,

    @XmlElement(true)
    @XmlSerialName("status", "DAV:", "")
    @Serializable(with = SafeStringSerializer::class)
    val status: String? = null
)

/**
 * Represents a single resource response within a WebDAV multistatus response.
 * Contains the resource href and one or more propstat blocks.
 */
@Serializable
@XmlSerialName("response", "DAV:", "")
data class WebDavResponse(
    @XmlElement(true)
    @XmlSerialName("href", "DAV:", "")
    val href: String,

    @XmlSerialName("propstat", "DAV:", "")
    val propstat: List<WebDavPropstat> = emptyList()
) {
    /**
     * Gets the first successful propstat entry (status 200 OK).
     * Fallback to the first propstat if no successful one is found.
     */
    val successfulPropstat: WebDavPropstat?
        get() = propstat.find { it.status?.contains("200") == true } ?: propstat.firstOrNull()
}

/**
 * Root element of a WebDAV PROPFIND response containing multiple resource responses.
 * This is the top-level element returned by WebDAV servers for directory listings.
 */
@Serializable
@XmlSerialName("multistatus", "DAV:", "")
data class WebDavMultiStatus(
    @XmlSerialName("response", "DAV:", "")
    val responses: List<WebDavResponse> = emptyList()
)
