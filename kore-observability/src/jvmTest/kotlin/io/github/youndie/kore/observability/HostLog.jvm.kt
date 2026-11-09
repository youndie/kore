package io.github.youndie.kore.observability

import io.github.youndie.tracy.wire.Level
import org.slf4j.LoggerFactory

internal actual fun hostLog(
    level: Level,
    logger: String,
    message: String,
) {
    val log = LoggerFactory.getLogger(logger)
    when (level) {
        Level.TRACE -> log.trace(message)
        Level.DEBUG -> log.debug(message)
        Level.INFO -> log.info(message)
        Level.WARN -> log.warn(message)
        Level.ERROR -> log.error(message)
    }
}

/** logback is on the test classpath, as it is on every JVM consumer's in the portfolio. */
internal actual val captureAvailable: Boolean = true
