package io.github.youndie.kore.ktor

import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The three probes, answering three different questions — `docs/api/endpoint-kore-admin.md`.
 *
 * The thing being replaced is one route answering `200` because the process is alive, used for all
 * three, which is what the portfolio's most complete service does today (research §1.11). Its chart
 * comment correctly argues that a probe must not read the store, and then points readiness at the
 * same route.
 *
 * ## What each body is for
 *
 * **The kubelet reads the status code and nothing else.** The bodies are for a person with `curl`
 * during an incident, which is why a failing readiness names the check *and* how old its answer is:
 * the result is cached (B-16), so a stale healthy answer and a fresh one are different facts and a
 * probe that could not tell them apart would be the outage-reporting-health failure all over again.
 *
 * A consumer that parses a probe body is coupling to something kore reserves the right to improve.
 */
public fun Application.installKoreProbes(
    startup: StartupGate,
    readiness: ReadinessGate,
    liveness: LivenessGate = LivenessGate(),
) {
    routing {
        // A LATCH. Once `200`, never `503` again — not for a failed dependency, not during a
        // shutdown. See StartupGate.
        get(KoreRoutes.STARTUP) {
            if (startup.hasStarted) {
                call.respondText("started\n")
            } else {
                call.respondText(
                    "starting — waiting for: ${startup.pending.sorted().joinToString(", ")}\n",
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        }

        get(KoreRoutes.READY) {
            val verdict = readiness.verdict()
            if (verdict.ready) {
                call.respondText("ready\n")
            } else {
                call.respondText(
                    verdict.reasons.joinToString(separator = "\n", postfix = "\n"),
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        }

        get(KoreRoutes.LIVE) { call.respondLiveness(liveness) }

        // The alias every chart in the portfolio already names. It is LIVENESS, and a chart pointing
        // its readiness probe here gets a probe that cannot fail while the process is alive — which
        // is today's behaviour and exactly what this feature exists to stop. The migration is the
        // printed probe block, not a rename that breaks a running deployment.
        get(KoreRoutes.HEALTH) { call.respondLiveness(liveness) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondLiveness(liveness: LivenessGate) {
    if (liveness.isAlive) {
        respondText("alive\n")
    } else {
        respondText("wedged — ${liveness.wedgedReason}\n", status = HttpStatusCode.ServiceUnavailable)
    }
}
