package io.github.youndie.kore.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Both lanes are `Dispatchers.IO`, the same as on the JVM, and kore owns no threads.
 *
 * **This file used to create two with `newSingleThreadContext`**, on the grounds that
 * `Dispatchers.IO` is `internal` on Kotlin/Native. It is not. It is an extension property in package
 * `kotlinx.coroutines`, so it needs `import kotlinx.coroutines.IO` — the import above — and without
 * it the compiler resolves the internal member of the same name and reports "it is internal", which
 * is the error that was taken for a platform limitation (research §1.14, B-42).
 *
 * It is also **elastic here**, which is the part that had to be measured rather than assumed: with
 * 128 threads deliberately blocked for three seconds, a trivial task on `Dispatchers.IO` was
 * scheduled in 107 µs (`linuxX64`, coroutines 1.11.0). So the separation the two lanes exist for is
 * provided by the dispatcher on this platform too, and two threads per process would buy a named
 * entry in a thread dump at the price of a `close` contract kore could never honour — the lanes
 * outlive every shutdown that might close them.
 */
internal actual val koreLifecycleDispatcher: CoroutineDispatcher get() = Dispatchers.IO

internal actual val koreChecksDispatcher: CoroutineDispatcher get() = Dispatchers.IO
