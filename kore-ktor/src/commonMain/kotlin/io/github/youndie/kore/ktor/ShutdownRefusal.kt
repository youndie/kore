package io.github.youndie.kore.ktor

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText

/** The body of a refusal. Named so a test and a document can agree on it. */
public const val SHUTTING_DOWN_BODY: String = "shutting down\n"

/**
 * Answers `503` with `Connection: close` to anything that arrives once the shutdown has begun.
 *
 * **This exists because Ktor does not do it.** Measured, not assumed: with a grace period long enough
 * to observe, CIO served 48 further requests on already-open connections after `SIGTERM` and refused
 * none of them (research §1.13). Cancelling the accept job stops new **connections**; a client that
 * already holds a keep-alive connection goes on being served normally for the whole grace period.
 * Without this plugin there is no refusal anywhere, and the oracle's A3 has no subject.
 *
 * `Connection: close` is a promise to the **client** that the connection is finished, and deliberately
 * not a claim that the server hangs up: on CIO the keep-alive decision is read from the *request's*
 * `Connection` header (research §1.4), so kore emits the header and does not pretend to more.
 *
 * @param isShuttingDown asked per call. A predicate rather than a flag kore owns, so this item does
 *   not decide where the shutdown state lives — that is the announce stage's business (B-09).
 * @param served the paths that keep working. Refusing `/health/live` would fail the liveness probe
 *   and restart the pod **during the shutdown it is reporting**, which is the opposite of the point.
 */
public fun Application.installShutdownRefusal(
    isShuttingDown: () -> Boolean,
    served: Set<String> = KoreRoutes.servedWhileShuttingDown,
) {
    intercept(ApplicationCallPipeline.Setup) {
        if (!isShuttingDown() || call.isServedWhileShuttingDown(served)) return@intercept

        call.response.header(HttpHeaders.Connection, "close")
        call.respondText(SHUTTING_DOWN_BODY, status = HttpStatusCode.ServiceUnavailable)
        // Stops the REST OF THE PIPELINE, which is not the route — routing does not run a handler
        // for a call whose response has already been sent, and a mutation proved it. What carries on
        // without this line is every plugin at a later phase: logging, metrics, a tracing span,
        // anything a service installed. All of them would run for a request refused before it began,
        // against exactly the resources the shutdown is closing.
        finish()
    }
}

private fun io.ktor.server.application.ApplicationCall.isServedWhileShuttingDown(served: Set<String>): Boolean =
    request.path() in served
