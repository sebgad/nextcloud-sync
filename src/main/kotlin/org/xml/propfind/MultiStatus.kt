package org.xml.propfind

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("multistatus", namespace = "DAV:", prefix = "d")
data class MultiStatus(
    @XmlElement(true)
    val response: List<Response>,
)
