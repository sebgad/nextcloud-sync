package org.example.xml.propfind

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("propstat", namespace = "DAV:", prefix = "d")
data class PropStat(
    @XmlElement(true)
    val prop: Prop,
    @XmlElement(true)
    val status: String
)