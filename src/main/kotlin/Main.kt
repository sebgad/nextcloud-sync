package org.example

import org.example.nextcloud.NextcloudDAV
import java.io.FileInputStream
import java.util.Properties

data class Credentials(
    val baseUrl: String,
    val username: String,
    val password: String,
    val localPath: String,
)

object CredentialProvider {
    fun getCredentials(): Credentials {
        val props = Properties()
        println("Working dir: " + System.getProperty("user.dir"))
        FileInputStream("src/main/resources/credentials.properties").use { props.load(it) }

        return Credentials(
            baseUrl = props.getProperty("baseUrl") ?: error("Missing baseUrl"),
            username = props.getProperty("username") ?: error("Missing username"),
            password = props.getProperty("password") ?: error("Missing password"),
            localPath = props.getProperty("localPath") ?: error("Missing localPath"),
        )
    }
}

fun main() {
    val cred = CredentialProvider.getCredentials()
    val nextCloudClient =
        NextcloudDAV(
            baseUrl = cred.baseUrl,
            username = cred.username,
            password = cred.password,
            localPath = cred.localPath,
        )
    nextCloudClient.initialize()
    nextCloudClient.getRemoteFileList()
    while (nextCloudClient.requestOnGoing) {
        Thread.sleep(500)
    }
    nextCloudClient.upload()
    nextCloudClient.download()
    nextCloudClient.deleteOnLocal()
    nextCloudClient.deleteOnRemote()
    nextCloudClient.getRemoteFileList()
    println("finished sync")
}
