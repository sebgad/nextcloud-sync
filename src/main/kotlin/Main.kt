package org.example

fun main() {
    val nextCloudClient =
        NextcloudDAV(
            baseUrl = "https://nextcloud.sebastianyue.ddns.net/remote.php/dav/files/testuser",
            username = "testuser",
            password = "vDrSKMC7cxjO61OJ",
            localUrl = "/home/sebastian/test",
        )

    nextCloudClient.getRemoteFileList()
    while (nextCloudClient.requestOnGoing) {
        Thread.sleep(500)
    }
    nextCloudClient.printRemoteFileList()
}
