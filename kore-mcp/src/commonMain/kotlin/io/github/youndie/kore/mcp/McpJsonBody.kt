package io.github.youndie.kore.mcp

import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

private val messageSerializer = serializer<JSONRPCMessage>()
private val batchSerializer = ListSerializer(messageSerializer)

/**
 * [subject] as the body the SDK means to send, encoded with `McpJson`, or null when it is not a
 * JSON-RPC message or a batch of them and should go on through the pipeline untouched.
 *
 * The SDK answers one request with one message and a batch with a list (`StreamableHttpServerTransport`,
 * 0.15.0, JSON-response mode), so those are the two shapes; everything else on the route — the guard's
 * own refusals, the SDK's `reject` bodies, a bare status — is already text or not a message.
 */
internal fun mcpJsonBody(subject: Any): TextContent? {
    val text =
        when {
            subject is JSONRPCMessage -> {
                McpJson.encodeToString(messageSerializer, subject)
            }

            subject is List<*> && subject.isNotEmpty() && subject.all { it is JSONRPCMessage } -> {
                @Suppress("UNCHECKED_CAST")
                McpJson.encodeToString(batchSerializer, subject as List<JSONRPCMessage>)
            }

            else -> {
                return null
            }
        }
    return TextContent(text, ContentType.Application.Json)
}
