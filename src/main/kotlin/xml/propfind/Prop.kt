package org.example.xml.propfind

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("prop", namespace = "DAV:", prefix = "d")
data class Prop(
    @XmlElement(true)
    val getcontenttype: String? = null,
    @XmlElement(true)
    val displayname: String? = null,
    @XmlElement(true)
    val getlastmodified: String? = null,
    @XmlElement(true)
    val resourcetype: ResourceType? = null,
    @XmlElement(true)
    val getcontentlength: String? = null,
    @XmlElement(true)
    @SerialName("quota-used-bytes")
    val usedBytes: Int? = null,
    @XmlElement(true)
    @SerialName("quota-available-bytes")
    val availableBytes: Int? = null,
    @XmlElement(true)
    @SerialName("getetag")
    val etag: String? = null,
)
