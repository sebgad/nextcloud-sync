package org.example.nextcloud

import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import org.example.file.RemoteFile
import org.example.xml.propfind.MultiStatus
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun deserializePropFindReq(
    url: String,
    xmlBody: String,
    remoteFileList: MutableList<RemoteFile>,
) {
    val format =
        XML {
            xmlVersion = XmlVersion.XML10
            xmlDeclMode = XmlDeclMode.Charset
            indentString = "    "
        }

    val multistatus = format.decodeFromString<MultiStatus>(xmlBody)

    multistatus.response.forEach {
        if (it.propstat.prop.getlastmodified != null) {
            // Only add file with a lastModified Date
            val newFile =
                RemoteFile(
                    displayName =
                        it.propstat.prop.displayname
                            .toString(),
                    file = URI(url + it.href.toString()),
                    lastModified =
                        ZonedDateTime
                            .parse(
                                // text =
                                it.propstat.prop.getlastmodified
                                    .toString(),
                                DateTimeFormatter.RFC_1123_DATE_TIME,
                            ),
                )
            remoteFileList.add(newFile)
        }
    }
}
