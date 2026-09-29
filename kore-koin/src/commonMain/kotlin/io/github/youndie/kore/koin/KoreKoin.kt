package io.github.youndie.kore.koin

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.pluginOrNull
import org.koin.core.Koin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.koinApplication
import org.koin.ktor.plugin.Koin
import org.koin.ktor.plugin.setKoin

/**
 * Koin for a Ktor application, WITHOUT the scope that `install(Koin)` opens on every call (B-65).
 *
 * koin-ktor's plugin creates a `RequestScope` in `CallSetup` and closes it after the response. On
 * Kotlin/Native every Koin scope owns a stately `Lock`, and on Linux that lock is a
 * `pthread_mutex_t` in a cinterop `Arena` that only `Lock.close()` frees. `Scope.close()` does not
 * call it and there is no cleaner, so every request — a 404 included — leaves 16 + 48 bytes of
 * malloc behind. On tracy that was 154 MB of a 176 MB resident set after four days, against 0.6 MB
 * held by SQLite. Apple targets build the lock on `NSRecursiveLock` and do not leak, and neither
 * does the JVM, which is why the leak lives only where the servers do.
 *
 * What this gives up is `call.scope` — per-request definitions. `get` and `inject` on `Application`
 * and `Route` keep working unchanged: they read the application attribute that [setKoin] writes,
 * the same one the plugin writes. Migrating is `install(Koin) { … }` → `installKoreKoin { … }`.
 *
 * The container is a `koinApplication`, not `startKoin`: nothing global is started, so two
 * applications in one test process no longer share — and stop — each other's container.
 *
 * It is closed on [ApplicationStopped], after the engine has stopped, rather than on
 * `ApplicationStopping` where the plugin closes it: on Kotlin/Native `ApplicationStopping` arrives
 * before the requests in flight have drained (research §1.1), and a definition's `onClose` that
 * releases a pool would release it under them.
 *
 * Refuses when koin-ktor's plugin is already installed: the two together still open the leaking
 * scope, and a consumer who migrated half-way would read this call as the fix.
 */
public fun Application.installKoreKoin(declaration: KoinAppDeclaration): Koin {
    check(pluginOrNull(Koin) == null) {
        "koin-ktor's `install(Koin)` is installed: it opens a Koin scope per call, which leaks on " +
            "Kotlin/Native (kore B-65). Remove it; installKoreKoin replaces it."
    }
    val koin = koinApplication(declaration).koin
    setKoin(koin)
    monitor.subscribe(ApplicationStopped) { koin.close() }
    return koin
}
