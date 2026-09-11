package io.github.youndie.kore.ktor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These run on the JVM **and** on the native targets: `ktor-server-test-host` publishes
 * `linuxx64`, `linuxarm64` and `macosarm64`, checked in Central before this was depended on from a
 * common test source set.
 */
class ShutdownRefusalTest {
    @Test
    fun `nothing is refused while the process is not shutting down`() = testApplication {
        application {
            installShutdownRefusal(isShuttingDown = { false })
            routing { get("/work") { call.respondText("worked\n") } }
        }

        val response = client.get("/work")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("worked\n", response.bodyAsText())
    }

    @Test
    fun `a request during the shutdown is refused with 503 and Connection close`() = testApplication {
        application {
            installShutdownRefusal(isShuttingDown = { true })
            routing { get("/work") { call.respondText("worked\n") } }
        }

        val response = client.get("/work")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("close", response.headers[HttpHeaders.Connection])
        assertEquals(SHUTTING_DOWN_BODY, response.bodyAsText())
    }

    @Test
    fun `the route does not also run`() = testApplication {
        var routeRan = false
        application {
            installShutdownRefusal(isShuttingDown = { true })
            routing { get("/work") { routeRan = true; call.respondText("worked\n") } }
        }

        client.get("/work")

        assertEquals(false, routeRan, "the handler ran for a call that had already been refused")
    }

    /**
     * What `finish()` is actually for, and this test exists because a mutation proved the previous
     * one did not cover it.
     *
     * Removing `finish()` left every test green: routing itself does not run a handler for a call
     * whose response has already been sent, so the route was never the thing at risk. **Everything
     * else in the pipeline is.** A plugin intercepting a later phase — logging, metrics, a tracing
     * span, anything a service installs — goes on running for a request that was refused before it
     * began, and does its work against exactly the resources the shutdown is closing.
     *
     * So the mutant survived because the assertion was aimed at the wrong thing, not because the
     * line was unnecessary.
     */
    @Test
    fun `nothing later in the pipeline runs for a refused call`() = testApplication {
        var laterPluginRan = false
        application {
            installShutdownRefusal(isShuttingDown = { true })
            intercept(ApplicationCallPipeline.Call) { laterPluginRan = true }
            routing { get("/work") { call.respondText("worked\n") } }
        }

        client.get("/work")

        assertEquals(false, laterPluginRan, "a later pipeline phase ran for a call already refused")
    }

    /**
     * The one that matters most, and the reason `served` exists at all.
     *
     * A `503` from `/health/live` is a **failed liveness probe**. Enough of those and the kubelet
     * restarts the pod in the middle of the shutdown it is reporting — turning an orderly stop into
     * the abrupt one this library exists to prevent.
     */
    @Test
    fun `liveness keeps answering during the shutdown`() = testApplication {
        application {
            installShutdownRefusal(isShuttingDown = { true })
            routing {
                get(KoreRoutes.LIVE) { call.respondText("alive\n") }
                get(KoreRoutes.HEALTH) { call.respondText("alive\n") }
                get(KoreRoutes.VERSION) { call.respondText("version\n") }
            }
        }

        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.LIVE).status)
        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.HEALTH).status)
        assertEquals(HttpStatusCode.OK, client.get(KoreRoutes.VERSION).status)
    }

    /**
     * Readiness is exempt for a different reason from liveness: it is *supposed* to fail during a
     * shutdown, and it does so by answering its own `503` with an explanation. Refusing it here would
     * reach the same status code by a route that says nothing about which check failed.
     */
    @Test
    fun `readiness answers for itself rather than being refused`() = testApplication {
        application {
            installShutdownRefusal(isShuttingDown = { true })
            routing {
                get(KoreRoutes.READY) {
                    call.respondText("draining\n", status = HttpStatusCode.ServiceUnavailable)
                }
            }
        }

        val response = client.get(KoreRoutes.READY)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("draining\n", response.bodyAsText(), "the refusal answered instead of the probe")
    }

    @Test
    fun `an unknown path during the shutdown is refused rather than answered 404`() = testApplication {
        application {
            installShutdownRefusal(isShuttingDown = { true })
            routing { get("/work") { call.respondText("worked\n") } }
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/nothing-here").status)
    }
}
