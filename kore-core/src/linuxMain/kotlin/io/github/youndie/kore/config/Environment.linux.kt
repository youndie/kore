package io.github.youndie.kore.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import platform.posix.__environ
import platform.posix.getenv

/**
 * Listing goes through glibc's **`__environ`** — two leading underscores.
 *
 * The POSIX-documented spelling `environ` is not what `platform.posix` exposes on these targets;
 * writing it gives an error naming a missing symbol rather than a missing platform. Verified by
 * dumping `klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix` and again for
 * `linux_arm64` before this file was written, not recalled.
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun systemEnvironment(): Environment =
    object : Environment {
        override fun lookup(name: String): String? = getenv(name)?.toKString()

        override fun names(): EnvironmentNames {
            val environ = __environ ?: return EnvironmentNames.Listed(emptySet())
            val names = LinkedHashSet<String>()
            var index = 0
            while (true) {
                val entry = environ[index] ?: break
                val text = entry.toKString()
                // A entry is `NAME=value`, and a value may contain `=`. Splitting on the FIRST one
                // is the difference between a name and a name plus half a connection string.
                val separator = text.indexOf('=')
                names += if (separator >= 0) text.substring(0, separator) else text
                index++
            }
            return EnvironmentNames.Listed(names)
        }
    }
