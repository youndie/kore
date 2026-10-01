package io.github.youndie.kore.mcp

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B-68: `protocolVersion` in the initialize result, which SDK 0.15.0 loses under an application's own
 * ContentNegotiation.
 *
 * The two reports this settles disagreed, and both were measured. One service saw the field vanish for
 * the SDK's latest version and anything newer; another saw it come back for every version and removed
 * its workaround. The difference was not the SDK or the version but the application around it: the
 * first had installed ContentNegotiation with a `Json` that omits defaults, the second had none, so the
 * SDK installed its own. `InitializeResult.protocolVersion` defaults to the latest version, so it is
 * exactly the value that goes missing — and an unknown version falls back to it.
 *
 * The application `Json` below is that first service's shape: kotlinx's own `encodeDefaults = false`.
 * Ktor's `json()` with no argument would hide the bug — its `DefaultJson` encodes defaults.
 */
class ProtocolVersionTest {
    private val info = Implementation(name = "kore-mcp-test", version = "0")

    private fun Application.ownNegotiation() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `the latest version is answered under an application Json that omits defaults`() =
        testApplication {
            application {
                ownNegotiation()
                installKoreMcp(KoreMcpConfig(TOKEN), info) {}
            }

            assertEquals(LATEST_PROTOCOL_VERSION, negotiated(LATEST_PROTOCOL_VERSION))
        }

    @Test
    fun `a version the SDK does not know is answered with the one it falls back to`() =
        testApplication {
            application {
                ownNegotiation()
                installKoreMcp(KoreMcpConfig(TOKEN), info) {}
            }

            // What a client newer than the SDK sends: the SDK negotiates down to its latest, which is
            // the default value, which is the one an omitting Json drops.
            assertEquals(LATEST_PROTOCOL_VERSION, negotiated("2099-01-01"))
        }

    @Test
    fun `an older version is echoed and so is every version without an application Json`() =
        testApplication {
            application { installKoreMcp(KoreMcpConfig(TOKEN), info) {} }

            for (version in listOf("2025-06-18", "2025-03-26", LATEST_PROTOCOL_VERSION, "2099-01-01")) {
                val expected = if (version == "2099-01-01") LATEST_PROTOCOL_VERSION else version
                assertEquals(expected, negotiated(version), "asked for $version")
            }
        }

    /**
     * THE TRIPWIRE. The bare SDK under the same application drops the field — the bug kore-mcp's
     * re-encoding exists for, asserted rather than described.
     *
     * **When this fails, the SDK no longer loses `protocolVersion`.** Delete `McpMessagesInMcpJson` and
     * `mcpJsonBody` from kore-mcp, keep the three tests above, and delete this one. Confirmed failing
     * the other way — that is, the bug present — on `io.modelcontextprotocol:kotlin-sdk-server` 0.15.0.
     */
    @Test
    fun `tripwire - the bare SDK 0_15_0 drops protocolVersion under an application Json`() =
        testApplication {
            application {
                ownNegotiation()
                mcpStatelessStreamableHttp(path = MCP, enableDnsRebindingProtection = false) {
                    Server(info, ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
                }
            }

            val dropped = result(LATEST_PROTOCOL_VERSION, token = null)
            assertNull(dropped["protocolVersion"], "the SDK serialises protocolVersion now - see the KDoc")
            // The control inside the tripwire: the same path keeps a version that is not the default,
            // so the missing field above is the default being omitted and not a broken answer.
            assertEquals("2025-06-18", result("2025-06-18", token = null)["protocolVersion"]?.jsonPrimitive?.content)
        }

    private suspend fun ApplicationTestBuilder.negotiated(version: String): String? =
        result(version, TOKEN)["protocolVersion"]?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.result(
        version: String,
        token: String?,
    ): JsonObject {
        val response = client.initialize(token, version)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return checkNotNull(Json.parseToJsonElement(body).jsonObject["result"]) { body }.jsonObject
    }
}
