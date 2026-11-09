package io.github.youndie.kore.observability

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.youndie.tracy.wire.Level

internal actual fun hostLog(
    level: Level,
    logger: String,
    message: String,
) {
    val log = KotlinLogging.logger(logger)
    when (level) {
        Level.TRACE -> log.trace { message }
        Level.DEBUG -> log.debug { message }
        Level.INFO -> log.info { message }
        Level.WARN -> log.warn { message }
        Level.ERROR -> log.error { message }
    }
}

/** Linux: the direct factory. Apple targets: os_log's factory, which the capture cannot reach. */
internal actual val captureAvailable: Boolean = KotlinLoggingConfiguration.loggerFactory == DirectLoggerFactory
