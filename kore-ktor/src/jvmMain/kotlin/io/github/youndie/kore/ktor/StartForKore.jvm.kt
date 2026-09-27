package io.github.youndie.kore.ktor

/**
 * Read out of Ktor rather than out of the system property, because the property is only what the
 * value *would* be: Ktor copies it into `SHUTDOWN_HOOK_ENABLED` once, when `ShutdownHookJvmKt` is
 * first loaded, and a property set after that changes nothing
 * (`ktor-server-core-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/server/engine/ShutdownHookJvm.kt`).
 *
 * The getter is `internal` in Kotlin and public in the bytecode. Reading it loads the class if nothing
 * has yet, which fixes the value from the property as it stands — so [switchOffKtorShutdownHook] sets
 * the property first. Re-check on every Ktor bump: a Ktor that renames it makes this answer `null`,
 * and the guards then say "cannot tell" instead of passing.
 */
internal actual fun ktorShutdownHookArmed(): Boolean? =
    runCatching {
        Class.forName("io.ktor.server.engine.ShutdownHookJvmKt")
            .getMethod("getSHUTDOWN_HOOK_ENABLED")
            .invoke(null) as Boolean
    }.getOrNull()

internal actual fun switchOffKtorShutdownHook() {
    System.setProperty(KTOR_SHUTDOWN_HOOK_PROPERTY, "false")
    when (ktorShutdownHookArmed()) {
        false -> Unit
        true -> error(ktorShutdownHookMessage())
        // A capability that is absent says so. The property is set, which is what Ktor 3.6 reads; this
        // version could not be asked whether it did.
        null -> System.err.println("kore: could not confirm that Ktor's JVM shutdown hook is off. ${ktorShutdownHookMessage()}")
    }
}

/** Nothing on the JVM: the watch is a shutdown hook, and a second hook would be a second one to release. */
internal actual fun takeTheSignalForKore() = Unit
