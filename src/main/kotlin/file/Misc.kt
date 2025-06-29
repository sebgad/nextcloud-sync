package org.example.file

import java.io.File
import java.net.URI
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
) {
    dbConnection.createStatement().use { statement ->
        val resultSet = statement.executeQuery(sqlString)

        while (resultSet.next()) {
            nextCloudFiles.add(
                NextcloudFile(
                    remoteUrl = resultSet.getString("remoteUrl"),
                    remoteLastModified = resultSet.getLong("remoteLastModified"),
                    localPath = resultSet.getString("localPath"),
                    localLastModified = resultSet.getLong("localLastModified"),
                    captured = resultSet.getLong("captured"),
                ),
            )
        }
    }
}
