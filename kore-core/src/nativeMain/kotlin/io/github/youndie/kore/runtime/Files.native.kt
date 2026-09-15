package io.github.youndie.kore.runtime

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * A small text file, or null when it is not there.
 *
 * `fopen`/`fread` rather than anything higher: the files this reads live in `sysfs` and `procfs`,
 * where `stat` reports a size of zero and a length-based read returns nothing. Reading until EOF is
 * the only way to get the content, which is why this is not "the obvious file API with a wrapper".
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun readSmallFile(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    val text = StringBuilder()
    try {
        memScoped {
            val buffer = allocArray<ByteVar>(BUFFER)
            while (true) {
                val read = fread(buffer, 1u, BUFFER.toULong(), file).toInt()
                if (read <= 0) break
                for (i in 0 until read) text.append(buffer[i].toInt().toChar())
                // A guard rather than a limit: these files are tens of bytes, and an unbounded read
                // of a path that turned out to be a pipe would hang a process at startup.
                if (text.length > MAX) break
            }
        }
    } finally {
        fclose(file)
    }
    return text.toString()
}

private const val BUFFER = 4096
private const val MAX = 64 * 1024
