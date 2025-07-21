package org.nextcloud

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import org.file.NextcloudFile
import org.file.executeSqlQuery
import org.file.remoteToBasePath
import org.xml.propfind.MultiStatus
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.Long
import kotlin.String
import kotlin.collections.forEach

class NextcloudDAV(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val localPath: String,
) {
    var requestOnGoing = false
    private val remoteUrl = "$baseUrl/remote.php/dav/files/$username"

    private val semaphore = Semaphore(10)
    private var captured: Long = 0

    // Creates a database file
    private val dbFile = File("$localPath/.nextcloud-dav-sync.db")
    private val dbConnection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbFile")

    private val auth = "$username:$password"
    private val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray())
    private val authHeader = "Basic $encodedAuth"

    fun updateRemoteFileList() {
        requestOnGoing = true

        val xmlBody =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind  xmlns:d="DAV:">
                <d:prop>
                    <d:displayname/>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:getcontenttype/>
                    <d:resourcetype/>
                </d:prop>
            </d:propfind>
            """.trimIndent()

        val client = HttpClient.newHttpClient()

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(remoteUrl))
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(xmlBody))
                .header("Depth", "10")
                .header("Authorization", authHeader)
                .header("Content-Type", "application/xml")
                .build()

        client
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() in 200..299) {
                    val body = response.body()
                    if (body != null) {
                        deserializePropFindReq(body)
                    }
                } else {
                    println("Error: ${response.statusCode()}")
                }
                requestOnGoing = false
            }.exceptionally { ex ->
                println("Failed: ${ex.message}")
                requestOnGoing = false
                null
            }
    }

    fun deserializePropFindReq(xmlBody: String) {
        val format =
            XML {
                xmlVersion = XmlVersion.XML10
                xmlDeclMode = XmlDeclMode.Charset
                indentString = "    "
            }

        val sqlInsert =
            """
            INSERT INTO syncTable (path, remoteLastModified, existsRemote, captured)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                remoteLastModified = excluded.remoteLastModified,
                existsRemote = TRUE,
                captured = excluded.captured;
            """.trimIndent()

        val multistatus = format.decodeFromString<MultiStatus>(xmlBody)

        dbConnection.prepareStatement(sqlInsert).use { stmt ->
            dbConnection.autoCommit = false

            // Iterate over all Remote files
            multistatus.response.forEach {
                if (it.propstat.prop.getlastmodified != null) {
                    val remoteLastModifiedTime =
                        ZonedDateTime
                            .parse(
                                // text =
                                it.propstat.prop.getlastmodified
                                    .toString(),
                                DateTimeFormatter.RFC_1123_DATE_TIME,
                            )

                    stmt.setString(1, remoteToBasePath(it.href.toString()))
                    stmt.setLong(2, remoteLastModifiedTime.toInstant().toEpochMilli())
                    stmt.setBoolean(3, true)
                    stmt.setLong(4, captured)
                    stmt.addBatch()
                }
            }

            stmt.executeBatch()
            dbConnection.commit()
            dbConnection.autoCommit = true
        }
    }

    fun updateLocalFileList() {
        val sqlInsert =
            """
            INSERT INTO syncTable (path, localLastModified, existsLocal, captured)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                localLastModified = excluded.localLastModified,
                existsLocal = TRUE,
                captured = excluded.captured;
            """.trimIndent()

        val localFileList =
            File(localPath)
                .walkTopDown()
                .filterNot { it == dbFile }
                .filter { it.isFile }
                .toMutableList()

        dbConnection.prepareStatement(sqlInsert).use { stmt ->
            dbConnection.autoCommit = false

            localFileList.forEach {
                stmt.setString(1, it.relativeTo(File(localPath)).toString())
                stmt.setLong(2, (it.lastModified() / 1000L) * 1000L)
                stmt.setBoolean(3, true)
                stmt.setLong(4, captured)
                stmt.addBatch()
            }
            stmt.executeBatch()
            dbConnection.commit()
            dbConnection.autoCommit = true
        }
    }

    fun initialize() {
        dbConnection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS syncTable (
                                path TEXT PRIMARY KEY,
                                remoteLastModified INTEGER,
                                remoteLastModifiedPrev INTEGER,
                                existsRemote BOOLEAN DEFAULT FALSE,
                                localLastModified INTEGER,
                                localLastModifiedPrev INTEGER,
                                existsLocal BOOLEAN DEFAULT FALSE,
                                synced BOOLEAN DEFAULT FALSE,
                                captured INTEGER
                )
                """.trimIndent(),
            )

            stmt.executeUpdate(
                """
                UPDATE syncTable
                SET existsRemote = FALSE,
                    existsLocal = FALSE
                """.trimIndent(),
            )

            captured = Instant.now().toEpochMilli()
        }
    }

    fun resolveConflicts() {
        var fileList: MutableList<NextcloudFile> = mutableListOf()

        var sqlString =
            """
            SELECT *
            FROM syncTable
            WHERE
                (localLastModifiedPrev != localLastModified) AND
                (remoteLastModifiedPrev != remoteLastModified) AND
                (remoteLastModifiedPrev != 0) AND
                (localLastModifiedPrev != 0) AND
                (existsRemote = TRUE) AND
                (existsLocal = TRUE)
            """.trimIndent()

        executeSqlQuery(
            dbConnection = dbConnection,
            sqlString = sqlString,
            nextCloudFiles = fileList,
            remoteBase = remoteUrl,
            localBase = localPath,
        )

        sqlString =
            """
            INSERT INTO syncTable (path, localLastModified, existsLocal, captured)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                localLastModified = excluded.localLastModified,
                existsLocal = TRUE,
                captured = excluded.captured;
            """.trimIndent()

        dbConnection.prepareStatement(sqlString).use { stmt ->
            dbConnection.autoCommit = false
            // Copy local file with conflict in its name
            fileList.forEach {
                val localFile = File(it.localPath)
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                val newFile =
                    localFile.copyTo(
                        File(
                            "${localFile.parent}/${localFile.nameWithoutExtension}_conflict_${
                                Instant.now()
                                    .atZone(ZoneId.systemDefault())
                                    .format(formatter)
                            }.${localFile.extension}",
                        ),
                    )
                if (newFile.exists()) {
                    stmt.setString(1, newFile.relativeTo(File(localPath)).toString())
                    stmt.setLong(2, newFile.lastModified() / 1000L * 1000L)
                    stmt.setBoolean(3, true)
                    stmt.setLong(4, captured)
                    stmt.addBatch()
                }
            }
            stmt.executeBatch()
            dbConnection.commit()
            dbConnection.autoCommit = true
        }

        sqlString =
            """
            UPDATE syncTable
            SET localLastModified = 0
            WHERE path = ?
            """.trimIndent()

        dbConnection.prepareStatement(sqlString).use { stmt ->
            dbConnection.autoCommit = false
            // Copy local file with conflict in its name
            fileList.forEach {
                val localFile = File(it.localPath)
                stmt.setString(1, localFile.relativeTo(File(localPath)).toString())
                stmt.addBatch()
            }
            stmt.executeBatch()
            dbConnection.commit()
            dbConnection.autoCommit = true
        }
    }

    fun download() =
        runBlocking {
            // Load files from server which are not on client and have never been synced
            var downloadList: MutableList<NextcloudFile> = mutableListOf()

            var sqlString =
                """
                SELECT *
                FROM syncTable
                WHERE (existsLocal = FALSE) AND (synced = FALSE);
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = downloadList,
                remoteBase = remoteUrl,
                localBase = localPath,
            )

            // Load files from server which are newer and have already been synced
            sqlString =
                """
                SELECT *
                FROM syncTable
                WHERE (remoteLastModified > localLastModified) AND (synced = TRUE);
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = downloadList,
                remoteBase = remoteUrl,
                localBase = localPath,
            )

            val jobs =
                downloadList.map<NextcloudFile, Job> { nextcloudFile ->
                    launch {
                        downloadRemoteFileAsync(
                            nextcloudFile.remoteUrl,
                            nextcloudFile.localPath,
                            nextcloudFile.remoteLastModified,
                        )
                    }
                }
            jobs.joinAll()
        }

    fun upload() =
        runBlocking {
            var uploadList: MutableList<NextcloudFile> = mutableListOf()
            var sqlString =
                """
                SELECT *
                FROM syncTable
                WHERE (existsRemote = FALSE) AND (synced = FALSE);
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = uploadList,
                remoteBase = remoteUrl,
                localBase = localPath,
            )

            // Load files from server which are newer and have already been synced
            sqlString =
                """
                SELECT *
                FROM syncTable
                WHERE (remoteLastModified < localLastModified) AND (synced = TRUE);
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = uploadList,
                remoteBase = remoteUrl,
                localBase = localPath,
            )

            val jobs =
                uploadList.map<NextcloudFile, Job> { nextcloudFile ->
                    launch {
                        println("uploading file: ${nextcloudFile.localPath}")
                        uploadLocalFileAsync(
                            nextcloudFile.remoteUrl,
                            File(nextcloudFile.localPath),
                        )
                    }
                }
            jobs.joinAll()
        }

    fun deleteOnRemote() =
        runBlocking {
            var deleteList: MutableList<NextcloudFile> = mutableListOf()

            val sqlString =
                """
                SELECT * FROM syncTable
                WHERE 
                    (existsRemote = TRUE) AND
                    (existsLocal = FALSE) AND
                    (synced = TRUE)
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = deleteList,
                remoteBase = remoteUrl,
                localBase = localPath,
            )

            val jobs =
                deleteList.map<NextcloudFile, Job> { nextcloudFile ->
                    launch {
                        deleteOnRemoteAsync(
                            nextcloudFile.remoteUrl,
                        )
                    }
                }
            jobs.joinAll()
        }

    fun deleteOnLocal() {
        var deleteList: MutableList<NextcloudFile> = mutableListOf()

        var sqlString =
            """
            SELECT *
            FROM syncTable
            WHERE (existsRemote = FALSE) AND 
                  (synced = TRUE);
            """.trimIndent()

        executeSqlQuery(
            dbConnection = dbConnection,
            sqlString = sqlString,
            nextCloudFiles = deleteList,
            remoteBase = remoteUrl,
            localBase = localPath,
        )

        sqlString =
            """
            DELETE FROM syncTable
            WHERE path = ?
            """.trimIndent()

        dbConnection.prepareStatement(sqlString).use { stmt ->
            dbConnection.autoCommit = false
            deleteList.forEach {
                val deletedFile = File(it.localPath)

                if (deletedFile.delete() or !(deletedFile.exists())) {
                    println("local file ${it.localPath} deleted")
                    stmt.setString(1, deletedFile.relativeTo(File(localPath)).toString())
                    stmt.addBatch()
                }
            }
            stmt.executeBatch()
            dbConnection.commit()
            dbConnection.autoCommit = true
        }
    }

    suspend fun uploadLocalFileAsync(
        remoteFileUrl: String,
        localFile: File,
    ) {
        semaphore.withPermit {
            val sqlString =
                """
                UPDATE syncTable
                SET existsRemote = TRUE, 
                    remoteLastModified = ?,
                    synced = TRUE
                WHERE path = ?
                """.trimIndent()

            dbConnection.prepareStatement(sqlString).use { stmt ->
                dbConnection.autoCommit = false
                try {
                    println("try uploading $remoteFileUrl")

                    val client = HttpClient.newHttpClient()

                    val request =
                        HttpRequest
                            .newBuilder()
                            .uri(URI.create(remoteFileUrl))
                            .header("Authorization", authHeader)
                            .header("Content-Type", "application/octet-stream") // or "text/plain", "application/xml", etc., as needed
                            .header("X-OC-MTime", (localFile.lastModified() / 1000L * 1000L).toString())
                            .PUT(HttpRequest.BodyPublishers.ofFile(localFile.toPath()))
                            .build()

                    val response = client.send(request, HttpResponse.BodyHandlers.discarding())

                    if (response.statusCode() !in 200..299) {
                        println("Failed to upload ${localFile.name}: ${response.statusCode()} ${response.body()}")
                    } else {
                        val modTime = (localFile.lastModified() / 1000L) * 1000L
                        println("Uploaded ${localFile.name} with mtime $modTime")
                        stmt.setLong(1, modTime)
                        stmt.setString(2, localFile.relativeTo(File(localPath)).toString())
                        stmt.addBatch()
                    }
                } catch (e: Exception) {
                    println("Error uploading $remoteFileUrl: ${e.message}")
                }
                stmt.executeBatch()
                dbConnection.commit()
                dbConnection.autoCommit = true
            }
        }
    }

    suspend fun downloadRemoteFileAsync(
        fileUri: String,
        localFilePath: String,
        lastModifiedTime: Long,
    ) {
        semaphore.withPermit {
            val sqlString =
                """
                UPDATE syncTable
                SET existsLocal = TRUE, 
                    localLastModified = ?,
                    synced = TRUE
                WHERE path = ?
                """.trimIndent()

            dbConnection.prepareStatement(sqlString).use { stmt ->
                dbConnection.autoCommit = false
                try {
                    val client = HttpClient.newHttpClient()

                    val request =
                        HttpRequest
                            .newBuilder()
                            .uri(URI.create(fileUri))
                            .header("Authorization", authHeader)
                            .GET()
                            .build()

                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

                    if (response.statusCode() !in 200..299) {
                        println("Failed to download $fileUri: ${response.statusCode()}")
                        return
                    }

                    val inputStream = response.body()
                    val localFile = File(localFilePath)
                    localFile.parentFile?.mkdirs()

                    FileOutputStream(localFile).use { outputStream ->
                        inputStream.use { it.copyTo(outputStream) }
                    }

                    localFile.setLastModified(lastModifiedTime)
                    println("Downloaded $fileUri to $localFilePath")

                    if (localFile.exists()) {
                        stmt.setLong(1, lastModifiedTime)
                        stmt.setString(2, localFile.relativeTo(File(localPath)).toString())
                        stmt.addBatch()
                        println("Test ${localFile.relativeTo(File(localPath))}")
                    }
                } catch (e: Exception) {
                    println("Error downloading $fileUri: ${e.message}")
                }

                stmt.executeBatch()
                dbConnection.commit()
                dbConnection.autoCommit = true
            }
        }
    }

    suspend fun deleteOnRemoteAsync(fileUri: String) {
        val sqlString =
            """
            DELETE FROM syncTable
            WHERE path = ?
            """.trimIndent()

        semaphore.withPermit {
            dbConnection.prepareStatement(sqlString).use { stmt ->
                dbConnection.autoCommit = false
                try {
                    val client = HttpClient.newHttpClient()

                    val request =
                        HttpRequest
                            .newBuilder()
                            .uri(URI.create(fileUri))
                            .header("Authorization", authHeader)
                            .DELETE()
                            .build()

                    val response = client.send(request, HttpResponse.BodyHandlers.discarding())

                    if (response.statusCode() !in 200..299) {
                        println("Failed to delete $fileUri: ${response.statusCode()}")
                    } else {
                        println("File $fileUri deleted on Remote.")
                        stmt.setString(1, remoteToBasePath(fileUri))
                        stmt.addBatch()
                    }
                } catch (e: Exception) {
                    println("Error delete $fileUri: ${e.message}")
                }
                stmt.executeBatch()
                dbConnection.commit()
                dbConnection.autoCommit = true
            }
        }
    }

    fun close() {
        dbConnection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                UPDATE syncTable
                SET synced = TRUE
                WHERE
                    (existsRemote = TRUE) AND
                    (existsLocal = TRUE) AND
                    (localLastModified = remoteLastModified) AND
                    (synced = FALSE)
                """.trimIndent(),
            )

            stmt.executeUpdate(
                """
                UPDATE syncTable
                SET localLastModifiedPrev = localLastModified,
                    remoteLastModifiedPrev = remoteLastModified
                """.trimIndent(),
            )
        }

        dbConnection.close()
    }
}
