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
import org.example.file.localPathToRemote
import org.example.file.remoteToLocalPath
import org.example.xml.propfind.MultiStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
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

    fun getRemoteFileList() {
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

        val localFileList =
            File(localPath)
                .walkTopDown()
                .filterNot { it == dbFile }
                .filter { it.isFile }
                .toMutableList()

        val sqlInsert =
            """
            INSERT INTO syncTable (remoteUrl, remoteLastModified, localPath, localLastModified, captured)
            VALUES (?, ?, ?, ?, ?)
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

                    val localFilePath = File(remoteToLocalPath(URI(baseUrl + it.href.toString()), localPath))
                    println("fetch file information $localFilePath")
                    localFileList.remove(localFilePath)

                    stmt.setString(1, baseUrl + it.href.toString())
                    stmt.setLong(2, remoteLastModifiedTime.toInstant().toEpochMilli())
                    stmt.setString(3, localFilePath.toString())
                    stmt.setLong(4, (localFilePath.lastModified() / 1000) * 1000)
                    stmt.setLong(5, captured)
                    stmt.addBatch()
                }
            }

            // iterate over every leftover local file
            localFileList.forEach {
                stmt.setString(1, localPathToRemote(remoteUrl, File(localPath), it))
                stmt.setLong(2, 0)
                stmt.setString(3, it.toString())
                stmt.setLong(4, it.lastModified())
                stmt.setLong(5, captured)
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
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                remoteUrl TEXT,
                                remoteLastModified INTEGER,
                                localPath TEXT,
                                localLastModified INTEGER,
                                captured INTEGER
                )
                """.trimIndent(),
            )
        }
    }

    fun download() =
        runBlocking {
            var downloadList: MutableList<NextcloudFile> = mutableListOf()
            /* systematic:
                - Take the most recent two captures of the local and remote file system
                - Filter entries
                    - where the number of unique remoteLastModified is two AND
                    - where the number of unique localLastModified is one -> remote file changed
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
                COUNT(DISTINCT remoteLastModified) = 2
                AND
                COUNT(DISTINCT localLastModified) = 1
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = downloadList,
            )

            /*
            systematic:
            - Take the most recent two captures of the local and remote file system
            - Filter entries
                - where the number of captured entries for this file is only 1 AND
                - where the localLastModified equals to 0 (No timestamp is set, because remote file n.a.)
             */
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
                localLastModified = 0;
                """.trimIndent()

            executeSqlQuery(
                dbConnection = dbConnection,
                sqlString = sqlString,
                nextCloudFiles = downloadList,
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
            val request =
                Request
                    .Builder()
                    .url(fileUri)
                    .header("Authorization", Credentials.basic(username, password))
                    .get()
                    .build()
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
                    }
                }
            } catch (e: Exception) {
                println("Error downloading $fileUri: ${e.message}")
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
}
