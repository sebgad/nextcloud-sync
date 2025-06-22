package org.example.xml.propfind

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("response", namespace = "DAV:", prefix = "d")
data class Response(
    @XmlElement(true)
    val href: String,
    @XmlElement(true)
    val propstat: PropStat
)