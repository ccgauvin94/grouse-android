package id.gauvin.grouse

import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
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

/** An image to attach to a prompt (base64 payload + mime). */
data class ImageBlock(val mimeType: String, val dataB64: String)

/** One choice in a tool-approval request (allow_once / allow_always / reject_*). */
data class PermOption(val optionId: String, val label: String, val kind: String)

/** A resumable server-side goose session, from session/list. */
data class SessionInfo(
    val sessionId: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
    val model: String,
)

/** One goose extension from the ACP `config/extensions/list` method (goose ≥1.42). */
data class ExtInfo(
    val name: String,
    val enabled: Boolean,
    val type: String,
    val description: String,
    val configKey: String,   // key in config.yaml; used by config/extensions/set-enabled
)

/** Events surfaced from the ACP connection to the UI layer. */
sealed interface AcpEvent {
    data class Status(val text: String) : AcpEvent
    data class AgentChunk(val text: String) : AcpEvent
    data class ThoughtChunk(val text: String) : AcpEvent
    data class UserChunk(val text: String) : AcpEvent
    data class ToolCall(val title: String) : AcpEvent
    data class TurnDone(val stopReason: String) : AcpEvent
    data class Error(val text: String) : AcpEvent
    data class Config(val options: List<ConfigOption>) : AcpEvent
    data class Ready(val sessionId: String) : AcpEvent
    data class Sessions(val list: List<SessionInfo>) : AcpEvent
    data class Extensions(val list: List<ExtInfo>) : AcpEvent
    data class Commands(val names: List<String>) : AcpEvent
    data class Usage(val used: Int, val size: Int, val cost: Double, val currency: String) : AcpEvent
    data class Chart(val spec: String) : AcpEvent   // Chart.js-shaped JSON from autovisualiser
    /** Reply to a config read: the requested key and its string value (empty if unset). */
    data class ServerConfig(val key: String, val value: String) : AcpEvent
    data class Permission(
        val toolCallId: String, val title: String, val detail: String, val options: List<PermOption>,
    ) : AcpEvent
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
    private val http = Net.builder()      // trust-all TLS for goosed's self-signed cert (wss)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    // Touched from both the main thread (outbound rpc) and the OkHttp WS thread (responses).
    private val pending = ConcurrentHashMap<Int, String>()      // request id -> method we sent
    private val pendingConfigKeys = ConcurrentHashMap<Int, String>()  // config/read id -> key
    private var sessionId: String? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Outstanding tool-approval requests: toolCallId -> the JSON-RPC id we must answer.
    // Inserted on the WS thread, removed on the main thread — must be concurrent.
    private val pendingPermissions = ConcurrentHashMap<String, JsonElement>()

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

    /** Ask the agent for its resumable sessions; reply arrives as AcpEvent.Sessions.
     *  `_meta.types` (goose ≥1.42) filters out scheduled sessions server-side; on older builds
     *  it's ignored and parseSessions() drops them client-side by title. */
    fun listSessions() = rpc("session/list", buildJsonObject {
        putJsonObject("_meta") { putJsonArray("types") { add("user"); add("acp") } }
    })

    /** List configured extensions (agent-global). Reply arrives as AcpEvent.Extensions.
     *  Replaces goosed's old GET /config/extensions — that REST endpoint is gone from
     *  `goose serve`; extension config is now an ACP method over this same socket. */
    fun listExtensions() = rpc("_goose/unstable/config/extensions/list", buildJsonObject {})

    /** Enable/disable a configured extension (affects new sessions); refreshes the list on reply. */
    fun setExtensionEnabled(configKey: String, enabled: Boolean) =
        rpc("_goose/unstable/config/extensions/set-enabled", buildJsonObject {
            put("configKey", configKey); put("enabled", enabled)
        })

    /** Read a global goose config value (e.g. GOOSE_FAST_MODEL). Reply arrives as
     *  AcpEvent.ServerConfig. These live in goose's config.yaml, NOT per-session — so the
     *  value only takes effect for NEW sessions/tasks, and an env var of the same name in the
     *  container would override it (we moved GOOSE_FAST_MODEL out of .env.goose for this). */
    fun readConfig(key: String): Int {
        val id = rpc("_goose/unstable/config/read", buildJsonObject { put("key", key) })
        pendingConfigKeys[id] = key   // the read reply doesn't echo the key; recover it here
        return id
    }

    /** Upsert a global goose config value; server replies empty, so we re-read to confirm. */
    fun upsertConfig(key: String, value: String) =
        rpc("_goose/unstable/config/upsert", buildJsonObject {
            put("key", key); put("value", value)
        })

    /** Rename a session (sets its title). Used by the assistant-thread reset: the old thread is
     *  renamed aside and a fresh one is renamed to "goose-assistant". Server replies empty. */
    fun renameSession(targetSessionId: String, title: String) =
        rpc("_goose/unstable/session/rename", buildJsonObject {
            put("sessionId", targetSessionId); put("title", title)
        })

    /** Change a session config knob; server replies with the refreshed configOptions. */
    fun setConfigOption(configId: String, value: String) {
        val sid = sessionId ?: return
        rpc("session/set_config_option", buildJsonObject {
            put("sessionId", sid); put("configId", configId); put("value", value)
        })
    }

    fun sendPrompt(text: String, images: List<ImageBlock> = emptyList()) {
        val sid = sessionId
        if (sid == null) { onEvent(AcpEvent.Error("not ready — no session")); return }
        rpc("session/prompt", buildJsonObject {
            put("sessionId", sid)
            putJsonArray("prompt") {
                if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "image"); put("mimeType", img.mimeType); put("data", img.dataB64)
                    })
                }
            }
        })
    }

    /** Interrupt the running turn (ACP notification — no response expected). */
    fun cancel() {
        val sid = sessionId ?: return
        ws?.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/cancel")
            putJsonObject("params") { put("sessionId", sid) }
        }.toString())
    }

    /** Answer a pending tool-approval request; null optionId = cancelled/deny. */
    fun respondPermission(toolCallId: String, optionId: String?) {
        val id = pendingPermissions.remove(toolCallId) ?: return
        respond(id, buildJsonObject {
            putJsonObject("outcome") {
                if (optionId != null) { put("outcome", "selected"); put("optionId", optionId) }
                else put("outcome", "cancelled")
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
            // Transport drop (usually just backgrounding) — surface on the status line, not as a
            // chat error bubble. ConnectionManager reconnects on resume.
            onEvent(AcpEvent.Status("disconnected"))
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onEvent(AcpEvent.Status("disconnected"))
        }
    }

    private fun handle(text: String) {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            onEvent(AcpEvent.Error("bad json: ${e.message}")); return
        }
        // Dispatch runs on the OkHttp WS thread; a field of an unexpected JSON kind (e.g. a
        // structured value where we read .jsonPrimitive) must surface as an error, not throw out
        // of onMessage and tear the socket down mid-turn.
        try {
            val method = obj["method"]?.jsonPrimitive?.contentOrNull
            val id = obj["id"]
            when {
                method != null && id != null -> serverRequest(method, id, obj["params"] as? JsonObject)
                method != null -> notification(method, obj["params"] as? JsonObject)
                id != null -> response(id.jsonPrimitive.intOrNull, obj["result"] as? JsonObject, obj["error"])
            }
        } catch (e: Exception) {
            onEvent(AcpEvent.Error("bad message: ${e.message}"))
        }
    }

    private fun response(id: Int?, result: JsonObject?, error: JsonElement?) {
        val method = id?.let { pending.remove(it) }   // ConcurrentHashMap rejects a null key
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
            "_goose/unstable/config/extensions/list" -> onEvent(AcpEvent.Extensions(parseExtensions(result)))
            // After a toggle, re-list so the UI reflects the new enabled state.
            "_goose/unstable/config/extensions/set-enabled" -> listExtensions()
            "_goose/unstable/config/read" -> {
                // The reply doesn't echo the key, so recover it from the request we cached.
                val key = pendingConfigKeys.remove(id) ?: return
                val v = result?.get("value")
                val s = when (v) {
                    is JsonPrimitive -> v.contentOrNull ?: ""
                    null -> ""
                    else -> v.toString()
                }
                onEvent(AcpEvent.ServerConfig(key, s))
            }
            // Upsert returns empty; nothing to reflect (the caller re-reads if it wants confirmation).
            "_goose/unstable/config/upsert" -> {}
            // Rename returns empty; the caller re-lists sessions to see the new title.
            "_goose/unstable/session/rename" -> {}
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
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: sid
            // Drop scheduler-created sessions (goose names them "Scheduled job: <id>") so they don't
            // clutter the list. (Belt-and-suspenders: the proactive job now runs --no-session anyway.)
            if (title.startsWith("Scheduled job:")) return@mapNotNull null
            val meta = o["_meta"] as? JsonObject
            SessionInfo(
                sessionId = sid,
                title = title,
                updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull ?: "",
                messageCount = meta?.get("messageCount")?.jsonPrimitive?.intOrNull ?: 0,
                model = meta?.get("modelId")?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    /** Parse the config/extensions/list reply: {extensions:[{extension:{name,type,description}, enabled, configKey}]}. */
    private fun parseExtensions(result: JsonObject?): List<ExtInfo> {
        val arr = result?.get("extensions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ext = o["extension"] as? JsonObject ?: return@mapNotNull null
            val name = ext["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ExtInfo(
                name = name,
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                type = ext["type"]?.jsonPrimitive?.contentOrNull ?: "",
                description = ext["description"]?.jsonPrimitive?.contentOrNull ?: "",
                configKey = o["configKey"]?.jsonPrimitive?.contentOrNull ?: name,
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
            // Thoughts stream live (own collapsible bubble); skipped in a rebuilt transcript.
            "agent_thought_chunk" -> if (!replaying) text()?.let { onEvent(AcpEvent.ThoughtChunk(it)) }
            "tool_call" -> {
                val toolName = (((update["_meta"] as? JsonObject)?.get("goose") as? JsonObject)
                    ?.get("toolCall") as? JsonObject)?.get("toolName")?.jsonPrimitive?.contentOrNull
                // `as? JsonPrimitive` (not .jsonPrimitive) so a tool whose `data` arg is an
                // object/array is simply treated as a normal tool call, not a crash.
                val chartData = ((update["rawInput"] as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull
                if (toolName == "autovisualiser__show_chart" && chartData != null) {
                    onEvent(AcpEvent.Chart(chartData))
                } else {
                    onEvent(AcpEvent.ToolCall(update["title"]?.jsonPrimitive?.contentOrNull ?: "tool call"))
                }
            }
            "usage_update" -> {
                val used = update["used"]?.jsonPrimitive?.intOrNull ?: 0
                val size = update["size"]?.jsonPrimitive?.intOrNull ?: 0
                val cost = update["cost"] as? JsonObject
                onEvent(AcpEvent.Usage(used, size,
                    cost?.get("amount")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    cost?.get("currency")?.jsonPrimitive?.contentOrNull ?: ""))
            }
            "available_commands_update" -> {
                val names = (update["availableCommands"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }
                if (names.isNotEmpty()) onEvent(AcpEvent.Commands(names))
            }
        }
    }

    private fun serverRequest(method: String, id: JsonElement, params: JsonObject?) {
        if (method == "session/request_permission") {
            // Surface it to the user — goose's mode (smart_approve by default) already decides
            // which tools reach here; we just present the decision.
            val tc = params?.get("toolCall") as? JsonObject
            val toolCallId = tc?.get("toolCallId")?.jsonPrimitive?.contentOrNull
            if (toolCallId == null) { respond(id, buildJsonObject {}); return }
            val title = tc["title"]?.jsonPrimitive?.contentOrNull ?: "tool"
            val detail = (tc["rawInput"] as? JsonObject)?.let { ri ->
                ri["command"]?.jsonPrimitive?.contentOrNull ?: ri.toString()
            } ?: ""
            val opts = (params["options"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val oid = o["optionId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                PermOption(oid, o["name"]?.jsonPrimitive?.contentOrNull ?: oid,
                    o["kind"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            pendingPermissions[toolCallId] = id
            onEvent(AcpEvent.Permission(toolCallId, title, detail, opts))
        } else {
            respond(id, buildJsonObject {})   // unknown request: empty result so the agent doesn't hang
        }
    }
}
