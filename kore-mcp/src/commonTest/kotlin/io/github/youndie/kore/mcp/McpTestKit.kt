package io.github.youndie.kore.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

internal const val TOKEN = "kore-mcp-test-token"

internal const val MCP = "/mcp"

/** What a streamable-HTTP client sends; the transport answers `406` to anything narrower. */
internal const val ACCEPT = "application/json, text/event-stream"

internal fun initializeBody(version: String): String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version",""" +
        """"capabilities":{},"clientInfo":{"name":"kore-mcp-test","version":"1"}}}"""

internal fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

internal suspend fun HttpClient.initialize(
    token: String?,
    version: String = "2025-06-18",
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    post(MCP) {
        token?.let { bearer(it) }
        header(HttpHeaders.Accept, ACCEPT)
        contentType(ContentType.Application.Json)
        setBody(initializeBody(version))
        block()
    }
