package io.github.youndie.kore.health

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A check that proves the dependency **answered**.
 *
 * kore takes no driver dependency — a pool is the consumer's choice — so what it can offer is the
 * right *shape*, and the shape is the whole content of the rule: run a trivial statement and wait for
 * a result, rather than asking the pool for a handle.
 *
 * ## Why `acquire()` is not a check
 *
 * A pool can hand back a connection from its idle set while the server on the far end is gone: the
 * object exists, the call succeeds, and the check reports healthy through an outage. sqlx4k's
 * `ConnectionPool` offers `poolSize()`, `poolIdleSize()`, `acquire()` and `close()` and **no `ping`**
 * (research §1.9), so there is nothing cheaper to ask that means anything.
 *
 * `PooledStoreCheckTest` demonstrates the difference against one pool: the acquire-shaped check
 * passes and the statement-shaped one fails.
 *
 * @param statement runs the trivial query. It must actually reach the store — `SELECT 1`, not a
 *   property of the pool object.
 */
public fun storeCheck(
    name: String,
    timeout: Duration = 1.seconds,
    statement: suspend () -> Unit,
): HealthCheck =
    object : HealthCheck {
        override val name: String = name
        override val timeout: Duration = timeout

        override suspend fun check() {
            statement()
        }
    }
