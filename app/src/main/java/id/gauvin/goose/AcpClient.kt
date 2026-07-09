package id.gauvin.goose

import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A selectable value inside a [ConfigOption] (goose "select" config). */
data class Choice(val value: String, val label: String)

/** One goose session config knob — provider / model / mode / thinking_effort. */
data class ConfigOption(
    val id: String,
    val name: String,
    val currentValue: String,
    val choices: List<Choice>,
)

/** A resumable server-side goose session, from session/list. */
data class SessionInfo(
    val sessionId: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
    val model: String,
)

/** Events surfaced from the ACP connection to the UI layer. */
sealed interface AcpEvent {
    data class Status(val text: String) : AcpEvent
    data class AgentChunk(val text: String) : AcpEvent
    data class UserChunk(val text: String) : AcpEvent
    data class ToolCall(val title: String) : AcpEvent
    data class TurnDone(val stopReason: String) : AcpEvent
    data class Error(val text: String) : AcpEvent
    data class Config(val options: List<ConfigOption>) : AcpEvent
    data class Ready(val sessionId: String) : AcpEvent
    data class Sessions(val list: List<SessionInfo>) : AcpEvent
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

    /** Config values to re-apply once a session opens (persisted picks). id -> value. */
    var desiredOptions: Map<String, String> = emptyMap()
    // provider before model so the model list is valid when the model set lands.
    private val applyOrder = listOf("provider", "model", "mode", "thinking_effort")

    /** If set, resume this server-side session (session/load) instead of a fresh session/new. */
    var resumeSessionId: String? = null
    /** On a silent background reconnect the UI still holds the transcript — drop the replay. */
    var suppressReplay = false
    // True between sending session/load and its response (i.e. while history replays).
    private var replaying = false

    fun connect() {
        val req = Request.Builder().url(url).addHeader("X-Secret-Key", secretKey).build()
        ws = http.newWebSocket(req, listener)
    }

    fun close() { ws?.close(1000, "bye"); ws = null }

    /** Ask the agent for its resumable sessions; reply arrives as AcpEvent.Sessions. */
    fun listSessions() { rpc("session/list", buildJsonObject {}) }

    /** Change a session config knob; server replies with the refreshed configOptions. */
    fun setConfigOption(configId: String, value: String) {
        val sid = sessionId ?: return
        rpc("session/set_config_option", buildJsonObject {
            put("sessionId", sid); put("configId", configId); put("value", value)
        })
    }

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
        if (error != null && error !is JsonNull) {
            // A stale/expired session can't be resumed — fall back to a fresh one.
            if (method == "session/load") { replaying = false; startNewSession(); return }
            onEvent(AcpEvent.Error("$method: $error")); return
        }
        when (method) {
            "initialize" -> {
                val resume = resumeSessionId
                if (resume != null) {
                    replaying = true
                    rpc("session/load", buildJsonObject {
                        put("sessionId", resume)
                        put("cwd", "/state")
                        putJsonArray("mcpServers") {}
                    })
                } else startNewSession()
            }
            "session/new" -> {
                sessionId = result?.get("sessionId")?.jsonPrimitive?.contentOrNull
                onEvent(AcpEvent.Status(if (sessionId != null) "ready" else "session/new returned no sessionId"))
                sessionId?.let { onEvent(AcpEvent.Ready(it)) }
                val cfg = parseConfig(result)
                onEvent(AcpEvent.Config(cfg))
                applyDesired(cfg)
            }
            "session/load" -> {
                replaying = false
                sessionId = resumeSessionId
                onEvent(AcpEvent.Status("ready"))
                sessionId?.let { onEvent(AcpEvent.Ready(it)) }
                onEvent(AcpEvent.Config(parseConfig(result)))   // reflects this session's model
            }
            "session/list" -> onEvent(AcpEvent.Sessions(parseSessions(result)))
            "session/set_config_option" -> onEvent(AcpEvent.Config(parseConfig(result)))
            "session/set_mode" -> {}
            "session/prompt" ->
                onEvent(AcpEvent.TurnDone(result?.get("stopReason")?.jsonPrimitive?.contentOrNull ?: "end"))
        }
    }

    private fun startNewSession() = rpc("session/new", buildJsonObject {
        // cwd must exist INSIDE the goose_acp container (not the host).
        // /state is bind-mounted + writable + persistent.
        put("cwd", "/state")
        putJsonArray("mcpServers") {}
    })

    private fun parseConfig(result: JsonObject?): List<ConfigOption> {
        val arr = result?.get("configOptions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val choices = (o["options"] as? JsonArray).orEmpty().mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                val v = co["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Choice(v, co["name"]?.jsonPrimitive?.contentOrNull ?: v)
            }
            ConfigOption(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
                currentValue = o["currentValue"]?.jsonPrimitive?.contentOrNull ?: "",
                choices = choices,
            )
        }
    }

    private fun parseSessions(result: JsonObject?): List<SessionInfo> {
        val arr = result?.get("sessions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val sid = o["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val meta = o["_meta"] as? JsonObject
            SessionInfo(
                sessionId = sid,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: sid,
                updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull ?: "",
                messageCount = meta?.get("messageCount")?.jsonPrimitive?.intOrNull ?: 0,
                model = meta?.get("modelId")?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    /** Re-apply persisted picks after a session opens (provider first for the model cascade). */
    private fun applyDesired(cfg: List<ConfigOption>) {
        if (desiredOptions.isEmpty()) return
        val current = cfg.associate { it.id to it.currentValue }
        for (id in applyOrder) {
            val want = desiredOptions[id] ?: continue
            if (want.isNotBlank() && want != current[id]) setConfigOption(id, want)
        }
    }

    private fun notification(method: String, params: JsonObject?) {
        if (method != "session/update") return
        // Silent reconnect: the UI already holds the transcript, so drop the replay.
        if (replaying && suppressReplay) return
        val update = params?.get("update") as? JsonObject ?: return
        fun text() = (update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
            // user_message_chunk only appears during a session/load replay (live prompts aren't echoed).
            "user_message_chunk" -> text()?.let { onEvent(AcpEvent.UserChunk(it)) }
            "agent_message_chunk" -> text()?.let { onEvent(AcpEvent.AgentChunk(it)) }
            // Thoughts stream live but are noise in a rebuilt transcript.
            "agent_thought_chunk" -> if (!replaying) text()?.let { onEvent(AcpEvent.AgentChunk(it)) }
            "tool_call" -> onEvent(AcpEvent.ToolCall(update["title"]?.jsonPrimitive?.contentOrNull ?: "tool call"))
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
