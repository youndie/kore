package io.github.youndie.kore.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * One thread each, named, created on first use.
 *
 * `by lazy` rather than eager initialisers so the two are independent: a binary that never starts a
 * health loop never creates the checks thread.
 *
 * Neither is ever closed. They are process-lifetime threads by design — closing them during a
 * shutdown would remove the lane the shutdown itself is running in, which is the sort of ordering
 * bug this library is written to make impossible.
 *
 * The names are for `top -H` and a thread dump during an incident: a thread called `kore-checks`
 * parked in a socket read names its own problem.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val lifecycleThread: CoroutineDispatcher by lazy { newSingleThreadContext("kore-lifecycle") }

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val checksThread: CoroutineDispatcher by lazy { newSingleThreadContext("kore-checks") }

internal actual val koreLifecycleDispatcher: CoroutineDispatcher get() = lifecycleThread

internal actual val koreChecksDispatcher: CoroutineDispatcher get() = checksThread
