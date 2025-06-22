package org.example

import kotlinx.coroutines.*
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
import java.io.IOException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class FileProp(
    val file: URI,
    val displayName: String,
    val lastModified: ZonedDateTime?,
)

class NextcloudDAV(
    private val baseUrl: String,
    private val password: String,
    private val username: String,
    private val localUrl: String,
) {
    var requestOnGoing = false

    private val client = OkHttpClient()
    private val remoteFiles: MutableList<FileProp> = mutableListOf()

    fun getRemoteFileList() {
        requestOnGoing = true
        val propFindRequestBody =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:">
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
                .url(baseUrl)
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
                                println("Executing ${it.propstat.prop.displayname}")
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
                    async {
                        val fileUrl = fileProp.file
                        val localPath = fileUrl.path
                    }
                }
        }

    suspend fun downloadRemoteFiles(remoteFiles: List<FileProp>): Boolean =
        withContext(Dispatchers.IO) {
        }

    fun printRemoteFileList() {
        remoteFiles.forEach {
            println("DisplayName: ${it.displayName}")
            println("File: ${it.file}")
            println("Modified: ${it.lastModified}")
        }
    }
}
