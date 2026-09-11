package io.github.youndie.kore.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

public actual fun installShutdownSignalWatch(
    pollInterval: Duration,
    releaseTimeout: Duration,
): ShutdownSignalWatch = JvmShutdownSignalWatch(releaseTimeout)

/**
 * The JVM has no signal API in its public surface, so the runtime is the messenger: a shutdown hook
 * runs on `SIGTERM`, on `SIGINT`, and on `System.exit`.
 *
 * **The hook thread must not return until the sequence is done.** Returning from it is what lets the
 * JVM exit, so a hook that merely sets a flag hands the sequence a process that is already leaving.
 * That is the whole difference from the native implementation, where the handler returns and `main`
 * carries on.
 */
internal class JvmShutdownSignalWatch(private val releaseTimeout: Duration) : ShutdownSignalWatch {
    private val signalled = CountDownLatch(1)
    private val released = CountDownLatch(1)

    private val hook = Thread(::onRuntimeShutdown, "kore-shutdown")

    init {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    /**
     * The hook's body, and a named function rather than a lambda so a test drives **this** and not a
     * double of it. What a test cannot cover is whether the JVM calls it — that is the runtime's
     * contract, and the end-to-end oracle is what exercises it.
     */
    internal fun onRuntimeShutdown() {
        signalled.countDown()
        // Bounded. Forgetting `releaseProcess` then costs latency rather than a process that never
        // exits, which is the right way round for a call somebody will forget.
        released.await(releaseTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    override suspend fun awaitSignal(): ShutdownSignal {
        // A blocking latch, moved off whatever dispatcher the caller was on. `Dispatchers.IO` exists
        // here; on Kotlin/Native it does not, which is B-38.
        withContext(Dispatchers.IO) { signalled.await() }
        return ShutdownSignal.JVM_SHUTDOWN
    }

    override fun releaseProcess() {
        released.countDown()
    }

    override fun close() {
        released.countDown()
        // Throws once shutdown is already under way, which is exactly when close() is least useful
        // and most likely to be called.
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}
