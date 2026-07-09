package id.gauvin.goose

import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Events surfaced from the ACP connection to the UI layer. */
sealed interface AcpEvent {
    data class Status(val text: String) : AcpEvent
    data class AgentChunk(val text: String) : AcpEvent
    data class ToolCall(val title: String) : AcpEvent
    data class TurnDone(val stopReason: String) : AcpEvent
    data class Error(val text: String) : AcpEvent
}

/**
 * Thin ACP (Agent Client Protocol) client over a WebSocket.
 * goosed does all the work; this just relays prompts and streams updates.
 * NOTE: onEvent is invoked on OkHttp's WS thread — the caller must marshal to main.
 */
class AcpClient(
    private val url: String,          // ws://host:port/acp
    private val secretKey: String,
    private val onEvent: (AcpEvent) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = HashMap<Int, String>()      // request id -> method we sent
    private var sessionId: String? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun connect() {
        val req = Request.Builder().url(url).addHeader("X-Secret-Key", secretKey).build()
        ws = http.newWebSocket(req, listener)
    }

    fun close() { ws?.close(1000, "bye"); ws = null }

    fun sendPrompt(text: String) {
        val sid = sessionId
        if (sid == null) { onEvent(AcpEvent.Error("not ready — no session")); return }
        rpc("session/prompt", buildJsonObject {
            put("sessionId", sid)
            putJsonArray("prompt") {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            }
        })
    }

    private fun rpc(method: String, params: JsonObject): Int {
        val id = nextId.getAndIncrement()
        pending[id] = method
        ws?.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }.toString())
        return id
    }

    private fun respond(id: JsonElement, result: JsonObject) {
        ws?.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("result", result)
        }.toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onEvent(AcpEvent.Status("connected — initializing"))
            rpc("initialize", buildJsonObject {
                put("protocolVersion", 1)
                putJsonObject("clientCapabilities") {
                    putJsonObject("fs") { put("readTextFile", false); put("writeTextFile", false) }
                }
            })
        }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onEvent(AcpEvent.Error("connection failed: ${t.message}"))
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onEvent(AcpEvent.Status("disconnected"))
        }
    }

    private fun handle(text: String) {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            onEvent(AcpEvent.Error("bad json: ${e.message}")); return
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val id = obj["id"]
        when {
            method != null && id != null -> serverRequest(method, id, obj["params"] as? JsonObject)
            method != null -> notification(method, obj["params"] as? JsonObject)
            id != null -> response(id.jsonPrimitive.intOrNull, obj["result"] as? JsonObject, obj["error"])
        }
    }

    private fun response(id: Int?, result: JsonObject?, error: JsonElement?) {
        val method = pending.remove(id)
        if (error != null && error !is JsonNull) { onEvent(AcpEvent.Error("$method: $error")); return }
        when (method) {
            "initialize" -> rpc("session/new", buildJsonObject {
                put("cwd", "/home/colin")
                putJsonArray("mcpServers") {}
            })
            "session/new" -> {
                sessionId = result?.get("sessionId")?.jsonPrimitive?.contentOrNull
                onEvent(AcpEvent.Status(if (sessionId != null) "ready" else "session/new returned no sessionId"))
            }
            "session/prompt" ->
                onEvent(AcpEvent.TurnDone(result?.get("stopReason")?.jsonPrimitive?.contentOrNull ?: "end"))
        }
    }

    private fun notification(method: String, params: JsonObject?) {
        if (method != "session/update") return
        val update = params?.get("update") as? JsonObject ?: return
        when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
            "agent_message_chunk", "agent_thought_chunk" -> {
                val t = (update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                if (t != null) onEvent(AcpEvent.AgentChunk(t))
            }
            "tool_call", "tool_call_update" ->
                onEvent(AcpEvent.ToolCall(update["title"]?.jsonPrimitive?.contentOrNull ?: "tool call"))
        }
    }

    private fun serverRequest(method: String, id: JsonElement, params: JsonObject?) {
        if (method == "session/request_permission") {
            // v1: auto-allow — it's your own agent doing what you asked. TODO: approval UI.
            val options = params?.get("options") as? JsonArray
            val chosen = options?.firstOrNull {
                val k = (it as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull
                k == "allow_once" || k == "allow_always"
            } ?: options?.firstOrNull()
            val optionId = (chosen as? JsonObject)?.get("optionId")?.jsonPrimitive?.contentOrNull
            respond(id, buildJsonObject {
                putJsonObject("outcome") {
                    put("outcome", "selected")
                    if (optionId != null) put("optionId", optionId)
                }
            })
        } else {
            respond(id, buildJsonObject {})   // unknown request: empty result so the agent doesn't hang
        }
    }
}
