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
import org.example.file.RemoteFile
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
    private val remoteFiles: MutableList<RemoteFile> = mutableListOf()
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
                    localFileList.remove(localFilePath)

                    stmt.setString(1, baseUrl + it.href.toString())
                    stmt.setLong(2, remoteLastModifiedTime.toInstant().toEpochMilli())
                    stmt.setString(3, localFilePath.toString())
                    stmt.setLong(4, localFilePath.lastModified())
                    stmt.setLong(5, captured)
                    stmt.addBatch()
                }
            }

            // iterate over every leftover local file
            localFileList.forEach {
                stmt.setString(1, localPathToRemote(baseUrl, File(localPath), it))
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
            val jobs =
                remoteFiles.map<RemoteFile, Job> { remoteFile ->
                    launch {
                        val fileUrl = remoteFile.file
                        val filePath = File(remoteToLocalPath(remoteFile.file, localPath))
                        if (!useLocal(remoteFile, filePath)) {
                            downloadRemoteFileAsync(fileUrl.toString(), filePath.toString(), remoteFile.lastModified)
                        } else {
                            println("skip download of file $filePath")
                        }
                    }
                }
            jobs.joinAll()
        }

    fun upload() =
        runBlocking {
            val remoteFileList = remoteFiles.map { File(remoteToLocalPath(it.file, localPath)) }.toSet()

            // create a list of files which are not present at remote
            val localFileList =
                File(localPath)
                    .walkTopDown()
                    .filterNot { it in remoteFileList }
                    .filterNot { it == dbFile }
                    .filter { it.isFile }
                    .toMutableList()

            // extend file list with files that are newer on local drive
            remoteFiles.forEach {
                val filePath = File(remoteToLocalPath(it.file, localPath))
                if (it.lastModified.toInstant().toEpochMilli() < filePath.lastModified()) {
                    localFileList.add(filePath)
                }
            }

            val jobs =
                localFileList.map<File, Job> { localFile ->
                    launch {
                        val remoteFileUrl =
                            localPathToRemote(
                                uri = remoteUrl,
                                localRootPath = File(localPath),
                                localFilePath = localFile,
                            )
                        uploadLocalFileAsync(remoteFileUrl, localFile)
                    }
                }
            jobs.joinAll()
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

    fun useLocal(
        remote: RemoteFile,
        local: File,
    ): Boolean = remote.lastModified.toInstant().toEpochMilli() <= local.lastModified()

    suspend fun downloadRemoteFileAsync(
        fileUri: String,
        localFilePath: String,
        lastModifiedTime: ZonedDateTime,
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
                        localFile.setLastModified(lastModifiedTime.toInstant().toEpochMilli())
                        println("Downloaded $fileUri to $localFilePath")
                    }
                }
            } catch (e: Exception) {
                println("Error downloading $fileUri: ${e.message}")
            }
        }
    }

    fun printRemoteFileList() {
        remoteFiles.forEach {
            println("DisplayName: ${it.displayName}")
            println("File: ${it.file}")
            println("Modified: ${it.lastModified}")
        }
    }
}
