package org.example

import kotlinx.coroutines.*
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.example.xml.propfind.MultiStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class FileProp(
    val file: URI,
    val displayName: String,
    val lastModified: ZonedDateTime?,
)

fun splitUriPath(uri: URI): List<String> = uri.path.split("/").filter { it.isNotEmpty() }

class NextcloudDAV(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val localPath: String,
) {
    var requestOnGoing = false
    private val remoteUrl = "$baseUrl/remote.php/dav/files/$username"

    private val client = OkHttpClient()
    private val remoteFiles: MutableList<FileProp> = mutableListOf()
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
                        val format =
                            XML {
                                xmlVersion = XmlVersion.XML10
                                xmlDeclMode = XmlDeclMode.Charset
                                indentString = "    "
                            }

                        val multistatus = format.decodeFromString<MultiStatus>(responseBody)

                        multistatus.response.forEach {
                            if (it.propstat.prop.getlastmodified != null) {
                                val newFile =
                                    FileProp(
                                        displayName =
                                            it.propstat.prop.displayname
                                                .toString(),
                                        file = URI("https://nextcloud.sebastianyue.ddns.net" + it.href.toString()),
                                        lastModified =
                                            ZonedDateTime
                                                .parse(
                                                    // text =
                                                    it.propstat.prop.getlastmodified
                                                        .toString(),
                                                    DateTimeFormatter.RFC_1123_DATE_TIME,
                                                ),
                                    )
                                remoteFiles.add(newFile)
                            }
                        }
                    } else {
                        println("Error: ${response.code}")
                    }
                    requestOnGoing = false
                }
            },
        )
    }

    fun sync() =
        runBlocking {
            val jobs =
                remoteFiles.map { fileProp ->
                    launch {
                        val fileUrl = fileProp.file

                        val uriList = splitUriPath(fileUrl)
                        val localFilePath = localPath + "/" + uriList.subList(4, uriList.size).joinToString("/")
                        val filePath = File(localFilePath)
                        if (fileProp.lastModified != null) {
                            if (filePath.lastModified() > fileProp.lastModified.toInstant().toEpochMilli()) {
                                downloadRemoteFileAsync(fileUrl.toString(), localFilePath, fileProp.lastModified)
                            } else {
                                println("skip download of file $filePath")
                            }
                        }
                    }
                }
            jobs.forEach { it.join() }
        }

    suspend fun downloadRemoteFileAsync(
        fileUri: String,
        localFilePath: String,
        lastModifiedTime: ZonedDateTime?,
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
                        if (lastModifiedTime != null) {
                            localFile.setLastModified(lastModifiedTime.toInstant().toEpochMilli())
                        }
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
