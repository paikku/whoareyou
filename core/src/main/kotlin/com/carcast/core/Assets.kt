package com.carcast.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Read-only view of the bundled files (web client, test clips): assets/web/index.html is "web/index.html". */
interface Assets {
    /** Returns null when the entry does not exist. */
    fun open(path: String): InputStream?
    fun exists(path: String): Boolean = open(path)?.also { it.close() } != null
}

/**
 * Reads assets straight out of the APK (a zip) — what the shell-uid server uses, since it has no
 * Context. The shell user can read /data/app/.../base.apk, the same way scrcpy's server is loaded.
 */
class ZipAssets(private val zip: ZipFile) : Assets {
    constructor(path: String) : this(ZipFile(File(path)))

    override fun open(path: String): InputStream? {
        val entry = zip.getEntry("assets/$path") ?: return null
        return zip.getInputStream(entry)
    }

    /** Any directory of files on disk (e.g. app/src/main/assets during development). */
    companion object {
        fun ofDirectory(dir: File): Assets = object : Assets {
            override fun open(path: String): InputStream? {
                val f = File(dir, path)
                if (!f.isFile || !f.canonicalPath.startsWith(dir.canonicalPath)) return null
                return try { f.inputStream() } catch (_: IOException) { null }
            }
        }
    }
}
