package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.cipher.CipherDiskCache
import java.io.File

/**
 * [CipherDiskCache] in the app's cache folder (`java.io.tmpdir` is the app's own cache directory on
 * Android). The system may clear it under storage pressure; then the next song downloads again.
 */
internal class FileCipherDiskCache(
    private val directory: File = File(System.getProperty("java.io.tmpdir") ?: ".", "player-js"),
) : CipherDiskCache {
    override fun read(name: String): String? = runCatching { File(directory, name).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(
        name: String,
        text: String,
    ) {
        runCatching {
            directory.mkdirs()
            // Written beside the target and renamed, so a process killed mid-write leaves no torn file.
            val temp = File(directory, "$name.tmp")
            temp.writeText(text)
            if (!temp.renameTo(File(directory, name))) temp.delete()
        }
    }

    override fun delete(name: String) {
        runCatching { File(directory, name).delete() }
    }

    override fun names(): List<String> = runCatching { directory.list()?.toList() }.getOrNull().orEmpty()
}
