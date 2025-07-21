package org.file

data class NextcloudFile(
    val remoteUrl: String,
    val remoteLastModified: Long,
    val localPath: String,
    val localLastModified: Long,
    val captured: Long,
)
