package io.github.youndie.kore.koin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.KOIN_SCOPE_ATTRIBUTE_KEY
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B-65: what `installKoreKoin` promises, each against a control that shows the check can fail.
 *
 * The scope check has its control in the second test — koin-ktor's own plugin through the same route
 * DOES show a scope — so a green first test cannot mean "this route could not see one anyway".
 */
class KoreKoinTest {
    private val probe = module { single { "wired" } }

    private fun Application.probeRoute() {
        routing {
            val value by inject<String>()
            get("/probe") {
                val scoped = call.attributes.contains(KOIN_SCOPE_ATTRIBUTE_KEY)
                call.respondText("value=$value scope=$scoped")
            }
        }
    }

    @Test
    fun `routes resolve from the container and a call opens no scope`() =
        testApplication {
            application {
                installKoreKoin { modules(probe) }
                probeRoute()
            }
            repeat(3) {
                assertEquals("value=wired scope=false", client.get("/probe").bodyAsText())
            }
        }

    @Test
    fun `control - koin-ktor's plugin opens a scope on every call`() =
        testApplication {
            application {
                install(Koin) { modules(probe) }
                probeRoute()
            }
            assertEquals("value=wired scope=true", client.get("/probe").bodyAsText())
        }

    @Test
    fun `installed next to koin-ktor's plugin it refuses rather than half-fixing`() {
        var refusal: Throwable? = null
        testApplication {
            application {
                install(Koin) { modules(probe) }
                refusal = assertFailsWith<IllegalStateException> { installKoreKoin { modules(probe) } }
            }
            startApplication()
        }
        assertTrue(refusal?.message.orEmpty().contains("B-65"), "refusal names the item: ${refusal?.message}")
    }

    @Test
    fun `the container is closed when the application stops`() {
        var closed = false
        val closing =
            module {
                single { Any() } withOptions { onClose { closed = true } }
            }
        testApplication {
            application {
                val koin = installKoreKoin { modules(closing) }
                koin.get<Any>()
            }
            startApplication()
            assertEquals(false, closed, "not before the stop")
        }
        assertTrue(closed, "onClose ran when the application stopped")
    }
}
