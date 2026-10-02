package io.github.youndie.kore.mcp

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

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
 * Ktor's `json()` with no argument keeps this field — its `DefaultJson` encodes defaults — and breaks
 * the answer another way, with an explicit `null` for every unset optional field.
 *
 * These tests say what a client gets from kore. They are not the tripwire: a field is one of the things
 * an application `Json` changes, and the SDK fixing this one would not make the hook unnecessary.
 * `McpWireFormatTest` compares whole answers under both `Json`s, and a control there on the bare SDK
 * says when the hook can go.
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

    private suspend fun ApplicationTestBuilder.negotiated(version: String): String? =
        result(version)["protocolVersion"]?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.result(version: String): JsonObject {
        val response = client.initialize(TOKEN, version)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        return checkNotNull(Json.parseToJsonElement(body).jsonObject["result"]) { body }.jsonObject
    }
}
