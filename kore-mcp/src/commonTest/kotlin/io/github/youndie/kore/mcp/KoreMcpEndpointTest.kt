package io.github.youndie.kore.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B-68: what `installKoreMcp` promises a service, through the path a service writes.
 *
 * Not the MCP protocol — the SDK answers for that — but who the endpoint lets in, what a refusal
 * looks like, and that a refused call never reaches the transport. Every refusal here has a sibling
 * that is let through, so a green refusal cannot mean "nothing would have answered anyway".
 */
class KoreMcpEndpointTest {
    private val info = Implementation(name = "kore-mcp-test", version = "0")

    /** How many times the transport asked for a `Server`: once per request it actually serves. */
    private var built = 0

    private fun Application.endpoint(
        token: String? = TOKEN,
        hosts: List<String> = emptyList(),
    ) = installKoreMcp(KoreMcpConfig(token, hosts), info) { built++; echoTool() }

    @Test
    fun `no token means no endpoint and nothing installed`() =
        testApplication {
            var negotiation: Any? = "unset"
            application {
                endpoint(token = null)
                negotiation = pluginOrNull(ContentNegotiation)
            }

            val response = client.initialize(token = TOKEN)

            // Not a 401: there is no route at all, so a misconfigured proxy has nothing to reach.
            assertEquals(HttpStatusCode.NotFound, response.status)
            // And the application is untouched — the SDK installs ContentNegotiation app-wide when it
            // runs, so its absence is how "nothing ran" is visible.
            assertNull(negotiation)
            assertEquals(0, built)
        }

    @Test
    fun `a blank token is the same as none`() =
        testApplication {
            application { endpoint(token = "   ") }

            assertEquals(HttpStatusCode.NotFound, client.initialize(token = "   ").status)
        }

    @Test
    fun `a missing token gets a JSON 401 and not a login page`() =
        testApplication {
            application { endpoint() }

            val response = client.initialize(token = null)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("unauthorized", errorOf(response))
            assertTrue(response.contentType()?.match(ContentType.Application.Json) == true, "${response.contentType()}")
            assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            assertNull(response.headers[HttpHeaders.Location])
            assertEquals(0, built, "a refused call reached the transport")
        }

    @Test
    fun `a wrong token and a bare token and the browser contour's header are all refused`() =
        testApplication {
            application { endpoint() }

            assertEquals(HttpStatusCode.Unauthorized, client.initialize(token = "wrong").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.initialize(token = null) { header(HttpHeaders.Authorization, TOKEN) }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.initialize(token = null) { header("X-Auth-Request-User", "anyone@example.com") }.status,
            )
            assertEquals(0, built, "a refused call reached the transport")
        }

    @Test
    fun `the right token is let through to the transport`() =
        testApplication {
            application { endpoint() }

            val response = client.initialize(token = TOKEN)

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(1, built)
        }

    @Test
    fun `the guard covers every method the transport answers`() =
        testApplication {
            application { endpoint() }

            // The stateless transport answers GET and DELETE with 405 — after the guard, not instead.
            assertEquals(HttpStatusCode.Unauthorized, client.get(MCP).status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete(MCP).status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.get(MCP) { bearer(TOKEN) }.status)
        }

    @Test
    fun `a foreign host is a JSON 400 when hosts are configured`() =
        testApplication {
            application { endpoint(hosts = listOf("mcp.example.com")) }

            val foreign = client.initialize(token = TOKEN) { header(HttpHeaders.Host, "evil.example") }
            assertEquals(HttpStatusCode.BadRequest, foreign.status)
            assertEquals("invalid host", errorOf(foreign))
            assertEquals(0, built, "a refused call reached the transport")

            val own = client.initialize(token = TOKEN) { header(HttpHeaders.Host, "mcp.example.com:443") }
            assertEquals(HttpStatusCode.OK, own.status, own.bodyAsText())
        }

    @Test
    fun `the host is not checked when none are configured`() =
        testApplication {
            application { endpoint() }

            // The SDK's own check would refuse this: its default is localhost only.
            val response = client.initialize(token = TOKEN) { header(HttpHeaders.Host, "mcp.example.com") }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }

    @Test
    fun `tools registered in the block are listed and called`() =
        testApplication {
            application { endpoint() }

            val listed = client.rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            assertTrue("\"echo\"" in listed, listed)

            val called =
                client.rpc(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"ping"}}}""",
                )
            assertTrue("pong:ping" in called, called)
            assertFalse("\"isError\":true" in called, called)
        }

    private fun Server.echoTool() {
        addTool(
            name = "echo",
            description = "answers with what it was given",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("text") { put("type", "string") }
                        },
                ),
        ) { request ->
            val text = request.arguments?.get("text")?.jsonPrimitive?.content
            CallToolResult(content = listOf(TextContent("pong:$text")))
        }
    }

    private suspend fun HttpClient.rpc(body: String): String =
        post(MCP) {
            bearer(TOKEN)
            header(HttpHeaders.Accept, ACCEPT)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()

    private suspend fun errorOf(response: HttpResponse): String? =
        Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content
}
