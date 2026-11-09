package io.github.youndie.kore.observability

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.youndie.tracy.agent.RecordSink
import io.github.youndie.tracy.agent.TracyAppender

internal actual fun attachLogCapture(sink: RecordSink): (() -> Unit)? {
    if (KotlinLoggingConfiguration.loggerFactory != DirectLoggerFactory) return null
    val previous = KotlinLoggingConfiguration.direct.appender
    KotlinLoggingConfiguration.direct.appender = TracyAppender(sink, previous)
    return { KotlinLoggingConfiguration.direct.appender = previous }
}
