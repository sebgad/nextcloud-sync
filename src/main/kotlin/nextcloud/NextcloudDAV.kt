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
import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.Long
import kotlin.String
import kotlin.collections.forEach

class NextcloudDAV(
    private val baseUrl: String,
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

    fun updateExists() {
        val stmt: PreparedStatement =
            dbConnection.prepareStatement(
                "UPDATE syncTable SET existsRemote = 0, existsLocal = 0",
            )
        stmt.executeUpdate()
        stmt.close()
    }

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

        captured = Instant.now().toEpochMilli()

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
                stmt.setLong(2, it.lastModified())
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
                                existsRemote BOOLEAN DEFAULT FALSE,
                                localLastModified INTEGER,
                                existsLocal BOOLEAN DEFAULT FALSE,
                                synced BOOLEAN DEFAULT FALSE,
                                captured INTEGER
                )
                """.trimIndent(),
            )
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
/*            systematic:
            - Take the most recent two captures of the local and remote file system
            - Filter entries
                    - where the number of unique remoteLastModified is only 1 AND
                    - where the number of unique localLastModified is two -> local file changed AND
                    - where localLastModified has no entries with 0

*/
            var sqlString =
                """
                SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
                FROM syncTable
                WHERE captured IN (
                    SELECT DISTINCT captured
                    FROM syncTable
                    ORDER BY captured DESC
                    LIMIT 2
                    )
                GROUP BY localPath
                HAVING
                COUNT(DISTINCT remoteLastModified) = 1
                AND
                COUNT(DISTINCT localLastModified) = 2
                AND
                localLastModified != 0
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = uploadList,
                remoteBase = remoteUrl,
                localBase = localPath,
            )

/*            systematic:
            - Take the most recent two captures of the local and remote file system
            - Filter entries
                    - where the number of captured entries for this file is only 1 AND
                    - where the remoteLastModified equals to 0 (No timestamp is set, because remote file n.a.) */

            sqlString =
                """
                SELECT *, COUNT(captured)
                FROM syncTable
                WHERE captured IN (
                    SELECT DISTINCT captured
                    FROM syncTable
                    ORDER BY captured DESC
                    LIMIT 2
                )
                GROUP by localPath
                HAVING COUNT(captured) = 1
                AND
                remoteLastModified = 0;
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

            /*
            systematic:
            - Take the most recent two captures of the local and remote file system
            - Filter entries
              - where the number of unique remoteLastModified is only 1 AND
              - where the number of unique localLastModified is two -> local file changed AND
              - where an entry of localLastModified equals zero
*/
            var sqlString =
                """
                SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
                FROM syncTable
                WHERE captured IN (
                    SELECT DISTINCT captured
                    FROM syncTable
                    ORDER BY captured DESC
                    LIMIT 2
                    )
                GROUP BY localPath
                HAVING
                COUNT(DISTINCT remoteLastModified) = 1
                AND
                COUNT(DISTINCT localLastModified) = 2
                AND
                localLastModified == 0
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
                        println("deleting file: ${nextcloudFile.remoteUrl}")
                        deleteOnRemoteAsync(
                            nextcloudFile.remoteUrl,
                        )
                    }
                }
            jobs.joinAll()
        }

    fun deleteOnLocal() {
        var deleteList: MutableList<NextcloudFile> = mutableListOf()
            /*
            systematic:
                - Take the most recent two captures of the local and remote file system
                - Filter entries
                  - where the number of unique remoteLastModified is only 1 AND
                  - where the number of unique localLastModified is two -> local file changed AND
                  - where an entry of localLastModified equals zero
             */

        val sqlString =
            """
            SELECT *, COUNT(DISTINCT remoteLastModified), COUNT(DISTINCT localLastModified)
            FROM syncTable
            WHERE captured IN (
                SELECT DISTINCT captured
                FROM syncTable
                ORDER BY captured DESC
                LIMIT 2
                )
            GROUP BY localPath
            HAVING
            COUNT(DISTINCT remoteLastModified) = 2
            AND
            COUNT(DISTINCT localLastModified) = 1
            AND
            remoteLastModified == 0
            """.trimIndent()

        executeSqlQuery(
            dbConnection = dbConnection,
            sqlString = sqlString,
            nextCloudFiles = deleteList,
            remoteBase = remoteUrl,
            localBase = localPath,
        )

        deleteList.forEach {
            println("delete file ${it.localPath}")
            File(it.localPath).delete()
        }
    }

    suspend fun uploadLocalFileAsync(
        remoteFileUrl: String,
        localFile: File,
    ) {
        semaphore.withPermit {
            val request =
                Request
                    .Builder()
                    .url(remoteFileUrl)
                    .put(localFile.asRequestBody("application/octet-stream".toMediaType()))
                    .header("Authorization", Credentials.basic(username, password))
                    .header("X-OC-MTime", (localFile.lastModified() / 1000).toString())
                    .build()
            try {
                println("try uploading $remoteFileUrl")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        println("Failed to upload ${localFile.name}: ${response.code} ${response.message}")
                    } else {
                        println("Uploaded ${localFile.name} with mtime ${localFile.lastModified()}")
                    }
                }
            } catch (e: Exception) {
                println("Error uploading $remoteFileUrl: ${e.message}")
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
        semaphore.withPermit {
            val request =
                Request
                    .Builder()
                    .url(fileUri)
                    .header("Authorization", Credentials.basic(username, password))
                    .delete()
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        println("Failed to delete $fileUri: ${response.code}")
                    } else {
                        println("Deleted file $fileUri")
                    }
                }
            } catch (e: Exception) {
                println("Error delete $fileUri: ${e.message}")
            }
        }
    }

    fun close() {
        dbConnection.close()
    }
}
