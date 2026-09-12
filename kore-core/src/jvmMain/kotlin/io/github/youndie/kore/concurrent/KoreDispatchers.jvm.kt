package io.github.youndie.kore.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Both lanes are `Dispatchers.IO`, and that is not a shortcut.
 *
 * `Dispatchers.IO` is elastic: a coroutine that blocks its thread does not make the next one wait,
 * it makes the pool grow. The separation the two lanes exist for is already provided by the
 * dispatcher, so allocating threads of kore's own would cost a service something and buy it nothing.
 */
internal actual val koreLifecycleDispatcher: CoroutineDispatcher get() = Dispatchers.IO

internal actual val koreChecksDispatcher: CoroutineDispatcher get() = Dispatchers.IO
