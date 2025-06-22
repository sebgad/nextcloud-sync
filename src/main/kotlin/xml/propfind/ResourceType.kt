package org.example.xml.propfind

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("resourcetype", namespace = "DAV:", prefix = "d")
data class ResourceType(
    @XmlElement(true)
    val collection: String? = null,
)
