package io.github.youndie.kore.runtime

import java.io.File

internal actual fun readSmallFile(path: String): String? =
    runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
