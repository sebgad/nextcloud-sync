package org.example.file

import java.net.URI
import java.time.ZonedDateTime

data class RemoteFile(
    val file: URI,
    val displayName: String,
    val lastModified: ZonedDateTime,
)
