package io.github.youndie.kore.observability

import ch.qos.logback.classic.LoggerContext
import io.github.youndie.tracy.agent.RecordSink
import io.github.youndie.tracy.agent.TracyLogbackAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal actual fun attachLogCapture(sink: RecordSink): (() -> Unit)? {
    val context = logbackContext() ?: return null
    val appender =
        TracyLogbackAppender(sink).apply {
            name = "tracy"
            this.context = context
            start()
        }
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.addAppender(appender)
    // Detached on stop because the logger context outlives an application: in a test JVM every
    // application started with tracy would otherwise leave one more appender feeding a stopped agent.
    return {
        root.detachAppender(appender)
        appender.stop()
    }
}

/**
 * logback's context, or `null` when logback is not the SLF4J backend — or not on the classpath at
 * all, which is a `NoClassDefFoundError` the moment `LoggerContext` is touched: the agent declares
 * logback `compileOnly`, and so does kore.
 */
@Suppress(
    "ktlint:kapkan:swallowed-failure",
    "a service without logback is a deployment, not a defect: the capture reports itself absent",
)
private fun logbackContext(): LoggerContext? = runCatching { LoggerFactory.getILoggerFactory() as? LoggerContext }.getOrNull()
