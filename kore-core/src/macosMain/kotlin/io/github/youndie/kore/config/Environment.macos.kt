package io.github.youndie.kore.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * The one target where a kore promise is unavailable, and it says so rather than pretending.
 *
 * `platform.posix` on `macos_arm64` exposes **neither `environ` nor `__environ`** — verified by
 * dumping the platform klib, and re-verified before this file was written. `_NSGetEnviron`, the
 * documented macOS route, is not in `platform.posix`, `platform.darwin` or `platform.Foundation`
 * either; reaching it would need a cinterop `.def` of kore's own.
 *
 * That is not worth it for a target nothing is deployed to. macOS native exists so the library
 * builds and its tests run on a laptop; the servers run on Linux. What **is** worth it is that the
 * absence is declared: an empty list here would be read as "no unknown variables found", and a check
 * that always passes is worse than one that is absent.
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun systemEnvironment(): Environment =
    object : Environment {
        override fun lookup(name: String): String? = getenv(name)?.toKString()

        override fun names(): EnvironmentNames =
            EnvironmentNames.Unavailable(
                "macOS native cannot list the environment: platform.posix exposes neither environ " +
                    "nor __environ, and _NSGetEnviron is not in the platform klibs either",
            )
    }
