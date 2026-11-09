package io.github.youndie.kore.observability

import io.github.youndie.tracy.agent.RecordSink
import io.github.youndie.tracy.wire.Level

/**
 * Attaches tracy to the logging the service already does, and returns how to take it off again —
 * or `null` where this platform's logging cannot be reached, because an absent capability says so
 * rather than looking attached (B-71).
 *
 * **Why kore does this at all.** `install(Tracy)` writes the span of each request and nothing else.
 * Every line the service logs through its own logging reaches tracy only through a capture: tracy's
 * SLF4J appender on the JVM, its kotlin-logging appender on native. kore wired the agent, the plugin
 * and the delivery and attached neither, so a consumer that did everything kore asked of it sent
 * spans and not one record — konekt-server did for six weeks, and tracy's summary called the gap
 * expected (youndie/tracy M-150, M-151).
 *
 * - **JVM:** tracy's `TracyLogbackAppender` on logback's root logger, next to the appenders
 *   `logback.xml` declares. `null` when logback is not the SLF4J backend.
 * - **Native:** tracy's `TracyAppender` in kotlin-logging's `direct.appender`, wrapping the one there.
 *   `null` when kotlin-logging is not using its direct factory — on Apple targets it writes to os_log
 *   through a factory of its own, and a logger already handed out by that factory cannot be reached.
 *   Ktor's own logger on native is not kotlin-logging and is not captured on any target.
 */
internal expect fun attachLogCapture(sink: RecordSink): (() -> Unit)?

/**
 * The agent, refusing levels below [from].
 *
 * A captured line carries no trace — SLF4J and kotlin-logging have none to give — so tracy cannot
 * tail-sample it and sends it whole. From `INFO` that is a line per request at the request rate; a
 * `WARN` is what tracy keeps from every request anyway. Applied as a sink rather than as a logback
 * filter so that both platforms are bounded by the same rule in the same place.
 */
internal class FromLevel(
    private val sink: RecordSink,
    private val from: Level,
) : RecordSink by sink {
    override fun isEnabled(level: Level): Boolean = level.atLeast(from) && sink.isEnabled(level)
}
