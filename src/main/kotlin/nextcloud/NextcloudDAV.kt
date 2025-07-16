package org.example.nextcloud

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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.example.file.NextcloudFile
import org.example.file.executeSqlQuery
import org.example.file.remoteToBasePath
import org.example.xml.propfind.MultiStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

    private val client = OkHttpClient()
    private val semaphore = Semaphore(10)
    private var captured: Long = 0

    // Creates a database file
    private val dbFile = File("$localPath/.nextcloud-dav-sync.db")
    private val dbConnection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbFile")

    fun updateRemoteFileList() {
        requestOnGoing = true
        val propFindRequestBody =
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
            """.trimIndent().toRequestBody("application/xml".toMediaType())

        val request =
            Request
                .Builder()
                .url(remoteUrl)
                .method("PROPFIND", propFindRequestBody)
                .header("Depth", "10")
                .header("Authorization", Credentials.basic(username, password))
                .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    println("Failed: ${e.message}")
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        deserializePropFindReq(
                            xmlBody = responseBody,
                        )
                    } else {
                        println("Error: ${response.code}")
                    }
                    requestOnGoing = false
                }
            },
        )
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

            val request =
                Request
                    .Builder()
                    .url(remoteFileUrl)
                    .put(localFile.asRequestBody("application/octet-stream".toMediaType()))
                    .header("Authorization", Credentials.basic(username, password))
                    .header("X-OC-MTime", (localFile.lastModified() / 1000).toString())
                    .build()

            dbConnection.prepareStatement(sqlString).use { stmt ->
                dbConnection.autoCommit = false
                try {
                    println("try uploading $remoteFileUrl")
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            println("Failed to upload ${localFile.name}: ${response.code} ${response.message}")
                        } else {
                            println("Uploaded ${localFile.name} with mtime ${(localFile.lastModified() / 1000L) * 1000L}")
                            stmt.setLong(1, (localFile.lastModified() / 1000L) * 1000L)
                            stmt.setString(2, localFile.relativeTo(File(localPath)).toString())
                            stmt.addBatch()
                        }
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

            val request =
                Request
                    .Builder()
                    .url(fileUri)
                    .header("Authorization", Credentials.basic(username, password))
                    .get()
                    .build()

            dbConnection.prepareStatement(sqlString).use { stmt ->
                dbConnection.autoCommit = false
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            println("Failed to download $fileUri: ${response.code}")
                        }
                        val body = response.body
                        if (body != null) {
                            val localFile = File(localFilePath)
                            localFile.parentFile?.mkdirs()

                            FileOutputStream(localFile).use { outputStream ->
                                body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            localFile.setLastModified(lastModifiedTime)
                            println("Downloaded $fileUri to $localFilePath")
                            if (localFile.exists()) {
                                stmt.setLong(1, lastModifiedTime)
                                stmt.setString(2, localFile.relativeTo(File(localPath)).toString())
                                stmt.addBatch()
                                println("Test ${localFile.relativeTo(File(localPath))}")
                            }
                        }
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
            val request =
                Request
                    .Builder()
                    .url(fileUri)
                    .header("Authorization", Credentials.basic(username, password))
                    .delete()
                    .build()
            dbConnection.prepareStatement(sqlString).use { stmt ->
                dbConnection.autoCommit = false
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            println("Failed to delete $fileUri: ${response.code}")
                        } else {
                            println("File $fileUri deleted on Remote.")
                            stmt.setString(1, remoteToBasePath(fileUri))
                            stmt.addBatch()
                        }
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
