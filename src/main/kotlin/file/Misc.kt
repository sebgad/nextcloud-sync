package org.example.file

import java.io.File
import java.net.URI

fun remoteToLocalPath(
    uri: URI,
    localPath: String,
): String {
    val uriList = uri.path.split("/").filter { it.isNotEmpty() }
    return localPath + "/" + uriList.subList(4, uriList.size).joinToString("/")
}

fun localPathToRemote(
    uri: String,
    localRootPath: File,
    localFilePath: File,
): String {
    val relativePath = localFilePath.relativeTo(localRootPath)
    val url = "$uri/$relativePath"
    return url
}
