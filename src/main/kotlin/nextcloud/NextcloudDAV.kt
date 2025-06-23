package org.example.nextcloud

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZonedDateTime
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
                            url = baseUrl,
                            xmlBody = responseBody,
                            remoteFileList = remoteFiles,
                        )
                    } else {
                        println("Error: ${response.code}")
                    }
                    requestOnGoing = false
                }
            },
        )
    }

    fun sync() {
        download()
        upload()
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
