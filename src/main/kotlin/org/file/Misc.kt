package org.file

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import kotlin.use

fun remoteToLocalPath(
    uri: URI,
    localPath: String,
): String {
    val uriList = uri.path.split("/").filter { it.isNotEmpty() }
    return localPath + "/" + uriList.subList(4, uriList.size).joinToString("/")
}

fun remoteToBasePath(uri: String): String {
    val uriList = uri.split("/").filter { it.isNotEmpty() }
    val uriBase = uriList.subList(4, uriList.size).joinToString("/")
    return URLDecoder.decode(uriBase, StandardCharsets.UTF_8.toString())
}

fun baseToRemotePath(
    uriPath: String,
    basePath: String,
): String {
    val encodedUrl = URLEncoder.encode(uriPath, StandardCharsets.UTF_8.toString()).toString().replace("+", "%20")
    return "$basePath/$encodedUrl"
}

fun localPathToRemote(
    uri: String,
    localRootPath: File,
    localFilePath: File,
): String {
    val relativePath = localFilePath.relativeTo(localRootPath)
    val encodedUrl = URLEncoder.encode("$relativePath", StandardCharsets.UTF_8.toString()).toString().replace("+", "%20")
    return "$uri/$encodedUrl"
}

fun executeSqlQuery(
    dbConnection: Connection,
    sqlString: String,
    nextCloudFiles: MutableList<NextcloudFile>,
    remoteBase: String,
    localBase: String,
) {
    dbConnection.createStatement().use { statement ->
        val resultSet = statement.executeQuery(sqlString)

        while (resultSet.next()) {
            nextCloudFiles.add(
                NextcloudFile(
                    remoteUrl = baseToRemotePath(resultSet.getString("path"), remoteBase),
                    remoteLastModified = resultSet.getLong("remoteLastModified"),
                    localPath = "$localBase/${resultSet.getString("path")}",
                    localLastModified = resultSet.getLong("localLastModified"),
                    captured = resultSet.getLong("captured"),
                ),
            )
        }
    }
}
