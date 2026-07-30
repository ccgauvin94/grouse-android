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
    // The session's server-side working directory (goose containers: /state by default). A Code
    // session is one scoped under /workspace instead — see ConnectionManager.sessionKind().
    val cwd: String = "",
)

/** One goose extension from the ACP `config/extensions/list` method (goose ≥1.42). */
data class ExtInfo(
    val name: String,
    val enabled: Boolean,
    val type: String,
    val description: String,
    val configKey: String,   // key in config.yaml; used by config/extensions/set-enabled
    // Core/required -- never offered for removal by a per-session extension profile (safety guard;
    // goose's own tagged-union extension config shape isn't hand-reconstructed anywhere else, so
    // this is parsed from the same server-sent object it guards).
    val bundled: Boolean = false,
    // The verbatim "extension" object from config/extensions/list, forwarded as-is to
    // session/extensions/add -- both deserialize to the same server-side type, so there's no need
    // to hand-reconstruct goose's Builtin/Platform/Mcp extension-config union client-side.
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Events surfaced from the ACP connection to the UI layer. */
sealed interface AcpEvent {
    data class Status(val text: String) : AcpEvent
    /** messageId is set on REPLAYED chunks (goose stamps _meta.goose.messageId per source
     *  message) and null on live streaming deltas — it is the message-boundary signal that
     *  keeps consecutive same-role history messages from merging into one bubble. */
    data class AgentChunk(val text: String, val messageId: String? = null) : AcpEvent
    data class ThoughtChunk(val text: String) : AcpEvent
    data class UserChunk(val text: String, val messageId: String? = null) : AcpEvent
    /** `detail` is the tool's rawInput (command/args), same extraction the permission sheet already
     *  does — Desktop shows this; Grouse was discarding it and only showing `title`.
     *  `toolCallId` correlates later ToolCallUpdate events (status + output) to this call. */
    data class ToolCall(val title: String, val detail: String = "", val toolCallId: String = "") : AcpEvent
    /** Progress for an in-flight tool call: status is in_progress/completed/failed; `output`
     *  carries the tool's result text when the update includes content (usually on completion). */
    data class ToolCallUpdate(val toolCallId: String, val status: String, val output: String) : AcpEvent
    /** A session's title/updatedAt changed server-side (auto-naming, a rename from any client). */
    data class SessionInfoChanged(val sessionId: String, val title: String?, val updatedAt: String?) : AcpEvent
    /** The session's approval mode changed (e.g. from another client). */
    data class ModeChanged(val modeId: String) : AcpEvent
    /** Per-message generation stats (goose-custom `_goose/unstable/session/update`, sessionUpdate
     *  "message_usage") — tok/s derived client-side from outputTokens/elapsedMs. Separate from the
     *  aggregate [Usage] (context window used/size), which comes from the STANDARD ACP usage_update. */
    data class MessageUsage(
        val outputTokens: Int, val elapsedMs: Long, val ttftMs: Long, val cost: Double?,
    ) : AcpEvent
    data class TurnDone(val stopReason: String) : AcpEvent
    /** A session/load history replay is about to stream. The server transcript is ground truth --
     *  it may hold turns other clients (Desktop, deliver.sh) added while this app wasn't looking --
     *  so the UI drops its local copy and rebuilds from the replayed chunks. */
    object ReplayStart : AcpEvent
    data class Error(val text: String) : AcpEvent
    data class Config(val options: List<ConfigOption>) : AcpEvent
    data class Ready(val sessionId: String) : AcpEvent
    data class Sessions(val list: List<SessionInfo>) : AcpEvent
    data class Extensions(val list: List<ExtInfo>) : AcpEvent
    /** Names of a SPECIFIC session's currently-enabled extensions (session-scoped, not the global
     *  catalog) -- reply to listSessionExtensions, used to diff-and-apply an extension profile. */
    data class SessionExtensions(val sessionId: String, val names: List<String>) : AcpEvent
    /** Tools active in a session, as `extension__tool` names straight from goose.
     *  sessionId is null for the client's own session (legacy), set for targeted queries. */
    data class Tools(val names: List<String>, val sessionId: String? = null) : AcpEvent
    data class Commands(val names: List<String>) : AcpEvent
    data class Usage(val used: Int, val size: Int, val cost: Double, val currency: String) : AcpEvent
    data class Chart(val spec: String) : AcpEvent   // Chart.js-shaped JSON from autovisualiser
    /** A goose-custom status line (currently: compaction progress/notice text). Substring-matched
     *  by the consumer — see ConnectionManager.onEvent. */
    data class CompactionStatus(val message: String) : AcpEvent
    /** Reply to a config read: the requested key and its string value (empty if unset). */
    data class ServerConfig(val key: String, val value: String) : AcpEvent
    /** Live model list for one provider (reply to listSupportedModels) -- for an OpenAI-compatible
     *  backend like LocalAI this hits its /v1/models endpoint server-side, so it reflects whatever
     *  models are actually loadable right now, not just the goose-bundled "featured" set. */
    data class SupportedModels(val providerId: String, val models: List<String>) : AcpEvent
    data class Permission(
        val toolCallId: String, val title: String, val detail: String, val options: List<PermOption>,
    ) : AcpEvent
    /** Reply to a DIRECT tool invocation (_goose/unstable/tools/call — no model turn involved).
     *  `text` is the concatenated text content blocks. */
    data class DirectToolResult(val text: String, val isError: Boolean) : AcpEvent
    /** One field of a form elicitation. `type` is string/number/integer/boolean; a non-empty
     *  `options` list means single-select (rendered as choices instead of free text). */
    data class ElicitField(
        val name: String, val type: String, val title: String, val description: String,
        val options: List<Choice>, val required: Boolean,
    )
    /** A tool/extension is requesting structured input (MCP elicitation, ACP form mode).
     *  Answer with respondElicitation(requestKey, ...): accept with values, decline, or cancel.
     *  Previously these got the generic empty-result reply, silently no-op'ing any tool that
     *  asked — now they render as a real form. */
    data class Elicitation(
        val requestKey: String, val message: String, val title: String,
        val fields: List<ElicitField>,
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
    // Explicit-target session/extensions/list + tools/list requests (replies don't echo the session).
    private val pendingExtListSids = ConcurrentHashMap<Int, String>()
    private val pendingToolListSids = ConcurrentHashMap<Int, String>()
    private var sessionId: String? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Outstanding tool-approval requests: toolCallId -> the JSON-RPC id we must answer.
    // Inserted on the WS thread, removed on the main thread — must be concurrent.
    private val pendingPermissions = ConcurrentHashMap<String, JsonElement>()
    // Outstanding elicitation requests: requestKey -> the JSON-RPC id to answer.
    private val pendingElicitations = ConcurrentHashMap<String, JsonElement>()
    private val elicitSeq = AtomicInteger(1)

    /** Config values to re-apply once a session opens (persisted picks). id -> value. */
    var desiredOptions: Map<String, String> = emptyMap()
    // provider before model so the model list is valid when the model set lands.
    private val applyOrder = listOf("provider", "model", "mode", "thinking_effort")

    /** If set, resume this server-side session (session/load) instead of a fresh session/new. */
    var resumeSessionId: String? = null
    /** The cwd to resume `resumeSessionId` with (session/load). Must match the session's actual
     *  working_dir -- session/load's cwd param silently REWRITES working_dir if it differs, so
     *  passing the wrong value here would un-scope a Code session back to whatever's passed. */
    var resumeCwd: String = "/state"
    /** False when the caller could NOT determine the session's real cwd: the client then asks
     *  the server (_goose/unstable/session/info) before session/load, instead of guessing --
     *  a wrong guess is a silent working_dir rewrite (this re-homed the assistant thread once). */
    var resumeCwdKnown: Boolean = true
    private var loadAwaitingInfo = false
    /** The cwd for a brand-new session (session/new) when resumeSessionId is null. "/state" for
     *  Chat/Assistant; "/workspace/<project>" for a Code session. */
    var desiredCwd: String = "/state"
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

    // --- Session-scoped extensions (a DIFFERENT, session-local API from config/extensions/* above:
    // that one writes config.yaml and affects every session; this one mutates only ONE session's own
    // extension_data, so a per-chat override can't leak into other sessions). ---

    /** List the CURRENT session's enabled extensions. Reply arrives as AcpEvent.SessionExtensions. */
    fun listSessionExtensions() {
        val sid = sessionId ?: return
        rpc("_goose/unstable/session/extensions/list", buildJsonObject { put("sessionId", sid) })
    }

    /** Enable one extension for just the current session. `extension` is the verbatim "extension"
     *  object from an ExtInfo.raw (config/extensions/list) -- forwarded as-is, not reconstructed. */
    fun addSessionExtension(extension: JsonObject) {
        val sid = sessionId ?: return
        rpc("_goose/unstable/session/extensions/add", buildJsonObject {
            put("sessionId", sid); put("extension", extension)
        })
    }

    fun removeSessionExtension(name: String) {
        val sid = sessionId ?: return
        rpc("_goose/unstable/session/extensions/remove", buildJsonObject {
            put("sessionId", sid); put("name", name)
        })
    }

    // --- Explicit-target variants: operate on ANY session (the Assistant thread's tool
    // profile is edited from Settings without that session being the one on screen). ---
    fun listSessionExtensionsFor(target: String) {
        val id = rpc("_goose/unstable/session/extensions/list",
            buildJsonObject { put("sessionId", target) })
        pendingExtListSids[id] = target
    }
    fun listToolsFor(target: String) {
        val id = rpc("_goose/unstable/tools/list", buildJsonObject { put("sessionId", target) })
        pendingToolListSids[id] = target
    }
    fun addSessionExtensionFor(target: String, extension: JsonObject) =
        rpc("_goose/unstable/session/extensions/add", buildJsonObject {
            put("sessionId", target); put("extension", extension)
        })
    fun removeSessionExtensionFor(target: String, name: String) =
        rpc("_goose/unstable/session/extensions/remove", buildJsonObject {
            put("sessionId", target); put("name", name)
        })

    /** Tools currently active in this session. Names are `extension__tool`; goose only returns
     *  ALLOWED tools, so this reflects `available_tools` filtering rather than the full catalogue
     *  (see ConnectionManager.discoverTools for how the full set is obtained). */
    fun listTools() {
        val sid = sessionId ?: return
        rpc("_goose/unstable/tools/list", buildJsonObject { put("sessionId", sid) })
    }

    /** Upsert an extension in config.yaml (the GLOBAL default for new sessions). Sending the
     *  extension object back with a modified `available_tools` is how a tool allowlist is saved.
     *  NOTE this rewrites the whole config.yaml and drops its comments -- goose re-serialises from
     *  its parsed model. Same is true of setExtensionEnabled. */
    fun addExtensionConfig(extension: JsonObject, enabled: Boolean) =
        rpc("_goose/unstable/config/extensions/add", buildJsonObject {
            put("extension", extension); put("enabled", enabled)
        })

    /** Read a global goose config value (e.g. GOOSE_FAST_MODEL). Reply arrives as
     *  AcpEvent.ServerConfig. These live in goose's config.yaml, NOT per-session — so the
     *  value only takes effect for NEW sessions/tasks, and an env var of the same name in the
     *  container would override it (we moved GOOSE_FAST_MODEL out of .env.goose for this). */
    fun readConfig(key: String): Int {
        // Record the key -> id mapping BEFORE the frame goes out: the reply doesn't echo the key,
        // and on a LAN/localhost goosed the response can land before a put-after-send would run,
        // dropping the reply. Mirror rpc()'s id/pending bookkeeping so ordering is guaranteed.
        val id = nextId.getAndIncrement()
        pending[id] = "_goose/unstable/config/read"
        pendingConfigKeys[id] = key
        ws?.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", "_goose/unstable/config/read")
            putJsonObject("params") { put("key", key) }
        }.toString())
        return id
    }

    /** Upsert a global goose config value; server replies empty, so we re-read to confirm. */
    fun upsertConfig(key: String, value: String) =
        rpc("_goose/unstable/config/upsert", buildJsonObject {
            put("key", key); put("value", value)
        })

    /** Ask a provider for its LIVE model list (reply arrives as AcpEvent.SupportedModels). For the
     *  "openai" provider goose calls fetch_supported_models(), which hits the configured backend's
     *  /v1/models -- e.g. LocalAI -- so this surfaces every model actually loadable right now, not
     *  just goose's bundled "featured" names or what the app happens to remember from past typing. */
    fun listSupportedModels(providerId: String) =
        rpc("_goose/unstable/providers/supported-models/list", buildJsonObject {
            put("providerId", providerId)
        })

    /** Rename a session (sets its title). Used by the assistant-thread reset: the old thread is
     *  renamed aside and a fresh one is renamed to "goose-assistant". Server replies empty. */
    fun renameSession(targetSessionId: String, title: String) =
        rpc("_goose/unstable/session/rename", buildJsonObject {
            put("sessionId", targetSessionId); put("title", title)
        })

    /** Archive a session: leaves session/list, history stays on disk (reversible via
     *  unarchiveSession). The soft option next to deleteSession. */
    fun archiveSession(targetSessionId: String) =
        rpc("_goose/unstable/session/archive", buildJsonObject { put("sessionId", targetSessionId) })

    /** Delete a session outright (goose ≥1.44 has real session/delete; the old "-32601 Method
     *  not found" note predates it). Archive remains the soft option. Reply re-lists. */
    fun deleteSession(targetSessionId: String) =
        rpc("session/delete", buildJsonObject { put("sessionId", targetSessionId) })

    /** Bring an archived session back into session/list. No UI browses archived sessions yet,
     *  but the capability is wired for parity with delete. */
    fun unarchiveSession(targetSessionId: String) =
        rpc("_goose/unstable/session/unarchive", buildJsonObject { put("sessionId", targetSessionId) })

    /** Rewrite a session's working_dir server-side -- the sanctioned form of the rewrite that
     *  session/load does silently. Used to move a chat into/out of a project and to repair
     *  sessions stranded by a renamed project directory. */
    fun updateWorkingDir(targetSessionId: String, workingDir: String) =
        rpc("_goose/unstable/session/working-dir/update", buildJsonObject {
            put("sessionId", targetSessionId); put("workingDir", workingDir)
        })

    /** Invoke a tool DIRECTLY -- no model turn, no prompt, deterministic. Name is the
     *  `extension__tool` form (e.g. "developer__shell"). Reply arrives as DirectToolResult.
     *  A session that only ever does this has zero messages, so session/list (which filters
     *  only_sessions_with_messages) never shows it -- the invisible-utility-session property
     *  the bootstrap flows rely on. */
    fun callTool(targetSessionId: String, name: String, arguments: JsonObject) =
        rpc("_goose/unstable/tools/call", buildJsonObject {
            put("sessionId", targetSessionId); put("name", name); put("arguments", arguments)
        })

    /** Change a session config knob; server replies with the refreshed configOptions. */
    fun setConfigOption(configId: String, value: String) {
        val sid = sessionId ?: return
        rpc("session/set_config_option", buildJsonObject {
            put("sessionId", sid); put("configId", configId); put("value", value)
        })
    }

    /** @param expect the session the UI believes it is in. sendPrompt used to trust only this
     *  client's own `sessionId`, which is set from whatever session/new or session/load last
     *  returned -- nothing tied it to the conversation on screen. When the two diverged the prompt
     *  went silently to the wrong chat. A mismatch is now refused and surfaced instead. */
    fun sendPrompt(text: String, images: List<ImageBlock> = emptyList(), expect: String? = null) {
        val sid = sessionId
        if (sid == null) { onEvent(AcpEvent.Error("not ready — no session")); return }
        if (expect != null && expect != sid) {
            onEvent(AcpEvent.Error("not sent — this chat isn't loaded yet (showing $expect, socket on $sid). Try again."))
            return
        }
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

    /** Answer a pending elicitation. accept=true sends `values`; accept=false declines;
     *  values ignored when declining. Cancel (sheet dismissed) is decline=false+cancel. */
    fun respondElicitation(requestKey: String, values: Map<String, JsonPrimitive>?, cancelled: Boolean = false) {
        val id = pendingElicitations.remove(requestKey) ?: return
        respond(id, buildJsonObject {
            when {
                cancelled -> put("action", "cancel")
                values == null -> put("action", "decline")
                else -> {
                    put("action", "accept")
                    putJsonObject("content") { values.forEach { (k, v) -> put(k, v) } }
                }
            }
        })
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
                    // Form elicitation: tools can request structured input and we render a real
                    // form (see AcpEvent.Elicitation). Without this goose cancels elicitations
                    // server-side ("client does not support form elicitation").
                    putJsonObject("elicitation") { putJsonObject("form") {} }
                    // Opt into goose's custom notifications (currently: compaction status lines).
                    // Purely additive — the standard usage_update still always fires regardless,
                    // so this can't regress anything already working.
                    putJsonObject("_meta") {
                        putJsonObject("goose") { put("customNotifications", true) }
                    }
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
            id?.let { pendingConfigKeys.remove(it) }   // an errored config/read never reaches its dispatch — clean its key map so it can't leak
            // A stale/expired session can't be resumed — fall back to a fresh one.
            if (method == "session/load") { replaying = false; startNewSession(); return }
            // Info probe failed (very old goose?): resume with /state rather than hanging.
            if (method == "_goose/unstable/session/info" && loadAwaitingInfo) {
                loadAwaitingInfo = false
                val resume = resumeSessionId
                if (resume != null) {
                    replaying = true
                    onEvent(AcpEvent.ReplayStart)
                    rpc("session/load", buildJsonObject {
                        put("sessionId", resume); put("cwd", resumeCwd)
                        putJsonArray("mcpServers") {}
                    })
                }
                return
            }
            onEvent(AcpEvent.Error("$method: $error")); return
        }
        when (method) {
            "initialize" -> {
                val resume = resumeSessionId
                if (resume != null && !resumeCwdKnown) {
                    // Ask the server for the session's real cwd rather than guessing.
                    loadAwaitingInfo = true
                    rpc("_goose/unstable/session/info", buildJsonObject { put("sessionId", resume) })
                } else if (resume != null) {
                    replaying = true
                    onEvent(AcpEvent.ReplayStart)
                    rpc("session/load", buildJsonObject {
                        put("sessionId", resume)
                        // session/load's cwd SILENTLY REWRITES the session's working_dir if it
                        // differs from what's stored server-side -- must be the session's real cwd
                        // (resumeCwd, set by the caller from the cached SessionInfo) or a Code
                        // session would get un-scoped back to /state on every reconnect.
                        put("cwd", resumeCwd)
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
            // Session-scoped (a session's OWN extension_data, not the global catalog above). The
            // array elements ARE the extension objects (goose's tagged union carries `name` at the
            // top level), unlike config/extensions/list's {extension:{...}, enabled, configKey} wrap.
            "_goose/unstable/session/extensions/list" -> {
                val names = (result?.get("extensions") as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }
                val target = id?.let { pendingExtListSids.remove(it) } ?: sessionId
                target?.let { onEvent(AcpEvent.SessionExtensions(it, names)) }
            }
            // add/remove reply empty -- ConnectionManager's diff-and-apply already knows the target
            // state, so there's nothing to re-fetch (unlike the global toggle above).
            "_goose/unstable/tools/list" -> {
                val names = (result?.get("tools") as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }
                onEvent(AcpEvent.Tools(names, id?.let { pendingToolListSids.remove(it) }))
            }
            "_goose/unstable/config/extensions/add" -> listExtensions()
            "_goose/unstable/session/extensions/add" -> listTools()
            "_goose/unstable/session/extensions/remove" -> {}   // the paired add re-lists
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
            "_goose/unstable/providers/supported-models/list" -> {
                val providerId = result?.get("providerId")?.jsonPrimitive?.contentOrNull ?: return
                val models = (result["models"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                onEvent(AcpEvent.SupportedModels(providerId, models))
            }
            // Rename returns empty; re-list so every consumer sees the new title.
            "_goose/unstable/session/rename" -> listSessions()
            "_goose/unstable/tools/call" -> {
                val texts = (result?.get("content") as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }
                onEvent(AcpEvent.DirectToolResult(
                    texts.joinToString("\n"),
                    result?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false))
            }
            "_goose/unstable/session/archive" -> listSessions()
            "session/delete" -> listSessions()
            "_goose/unstable/session/unarchive" -> listSessions()
            "_goose/unstable/session/working-dir/update" -> {}   // caller updates optimistically
            "_goose/unstable/session/info" -> {
                val cwd = (result?.get("session") as? JsonObject)
                    ?.get("cwd")?.jsonPrimitive?.contentOrNull
                if (loadAwaitingInfo) {
                    loadAwaitingInfo = false
                    resumeCwd = cwd?.takeIf { it.isNotBlank() } ?: "/state"
                    resumeCwdKnown = true
                    val resume = resumeSessionId
                    if (resume != null) {
                        replaying = true
                        onEvent(AcpEvent.ReplayStart)
                        rpc("session/load", buildJsonObject {
                            put("sessionId", resume)
                            put("cwd", resumeCwd)
                            putJsonArray("mcpServers") {}
                        })
                    }
                }
            }
            "session/set_config_option" -> onEvent(AcpEvent.Config(parseConfig(result)))
            "session/set_mode" -> {}
            "session/prompt" ->
                onEvent(AcpEvent.TurnDone(result?.get("stopReason")?.jsonPrimitive?.contentOrNull ?: "end"))
        }
    }

    private fun startNewSession() = rpc("session/new", buildJsonObject {
        // cwd must exist INSIDE the goose container (not the host). Conversational chats live
        // under /home/colin/Projects/<Name> (Inbox for unfiled) -- that path is also what Goose
        // Desktop groups on to build its project list, so the cwd IS the project.
        put("cwd", desiredCwd)
        putJsonArray("mcpServers") {}
        // WITHOUT THIS, EVERY CHAT STARTED HERE IS INVISIBLE IN GOOSE DESKTOP.
        // goose types a new session from this one field (acp/server/new_session.rs):
        //     _meta.client present -> SessionType::User ; absent -> SessionType::Acp
        // and Desktop asks session/list for types ['user','scheduled'] only. Omitting it meant
        // Grouse chats were 'acp' and structurally unlistable there -- measured 3 sessions
        // visible against 50 actually present. The goose CLI's `session list` filters the same
        // way, which is what let deliver.sh create a duplicate Assistant it could not see
        // (the 2026-07-26 fork). The value is not interpreted; only its presence matters.
        putJsonObject("_meta") { put("client", "grouse") }
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
            // goose's archive only stamps archivedAt — session/list has NO archived filter
            // (verified in source: list_sessions_paged never checks it), so every client must
            // filter for itself or archived sessions pop right back on the next refresh.
            if (meta?.get("archivedAt") != null) return@mapNotNull null
            SessionInfo(
                sessionId = sid,
                title = title,
                updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull ?: "",
                messageCount = meta?.get("messageCount")?.jsonPrimitive?.intOrNull ?: 0,
                model = meta?.get("modelId")?.jsonPrimitive?.contentOrNull ?: "",
                cwd = o["cwd"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    /** Parse the config/extensions/list reply: {extensions:[{extension:{name,type,description}, enabled, configKey}]}. */
    private fun parseExtensions(result: JsonObject?): List<ExtInfo> {
        val arr = result?.get("extensions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ext = o["extension"] as? JsonObject ?: return@mapNotNull null
            // type=mcp extensions (nextcloud, kagi, fastmail, Fetch -- anything backed by an actual
            // MCP server) carry their name NESTED at extension.server.name, not the top-level
            // extension.name that builtin/platform types use. Missing this silently dropped every
            // MCP extension from the list (confirmed live: nextcloud/kagi/fastmail/Fetch all lack a
            // top-level name). Check both.
            val name = ext["name"]?.jsonPrimitive?.contentOrNull
                ?: (ext["server"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            ExtInfo(
                name = name,
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                type = ext["type"]?.jsonPrimitive?.contentOrNull ?: "",
                description = ext["description"]?.jsonPrimitive?.contentOrNull ?: "",
                configKey = o["configKey"]?.jsonPrimitive?.contentOrNull ?: name,
                bundled = ext["bundled"]?.jsonPrimitive?.booleanOrNull ?: false,
                raw = ext,
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
        when (method) {
            "session/update" -> standardUpdate(params)
            "_goose/unstable/session/update" -> gooseUpdate(params)
        }
    }

    private fun gooseUpdate(params: JsonObject?) {
        val update = params?.get("update") as? JsonObject ?: return
        when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
            "status_message" -> {
                val status = update["status"] as? JsonObject ?: return
                val msg = status["message"]?.jsonPrimitive?.contentOrNull ?: return
                onEvent(AcpEvent.CompactionStatus(msg))
            }
            // Per-message tok/s + cost (goose-sdk-types MessageUsageData: outputTokens, elapsedMs,
            // timeToFirstTokenMs, cost — camelCase on the wire). Distinct from the standard ACP
            // usage_update (context-window used/size) already handled in standardUpdate().
            "message_usage" -> {
                val usage = update["usage"] as? JsonObject ?: return
                val outTok = usage["outputTokens"]?.jsonPrimitive?.intOrNull ?: return
                val elapsed = usage["elapsedMs"]?.jsonPrimitive?.longOrNull ?: return
                if (elapsed <= 0) return   // can't derive a tok/s rate from a zero/missing duration
                val ttft = usage["timeToFirstTokenMs"]?.jsonPrimitive?.longOrNull ?: 0L
                val cost = usage["cost"]?.jsonPrimitive?.doubleOrNull
                onEvent(AcpEvent.MessageUsage(outTok, elapsed, ttft, cost))
            }
        }
    }

    private fun standardUpdate(params: JsonObject?) {
        val update = params?.get("update") as? JsonObject ?: return
        val tag = update["sessionUpdate"]?.jsonPrimitive?.contentOrNull
        // Replays are never suppressed: every reconnect rebuilds the transcript from the server's
        // history (see AcpEvent.ReplayStart). Suppression used to guard a socket blip against
        // duplicate bubbles, but it couldn't tell "what I already show" from "turns another client
        // added while I was away", so those turns were silently dropped.
        fun text() = (update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        fun msgId() = ((update["_meta"] as? JsonObject)?.get("goose") as? JsonObject)
            ?.get("messageId")?.jsonPrimitive?.contentOrNull
        when (tag) {
            // user_message_chunk only appears during a session/load replay (live prompts aren't echoed).
            "user_message_chunk" -> text()?.let { onEvent(AcpEvent.UserChunk(it, msgId())) }
            "agent_message_chunk" -> text()?.let { onEvent(AcpEvent.AgentChunk(it, msgId())) }
            // Thoughts stream live (own collapsible bubble); skipped in a rebuilt transcript.
            "agent_thought_chunk" -> if (!replaying) text()?.let { onEvent(AcpEvent.ThoughtChunk(it)) }
            "tool_call" -> {
                val toolName = (((update["_meta"] as? JsonObject)?.get("goose") as? JsonObject)
                    ?.get("toolCall") as? JsonObject)?.get("toolName")?.jsonPrimitive?.contentOrNull
                val rawInput = update["rawInput"] as? JsonObject
                // `as? JsonPrimitive` (not .jsonPrimitive) so a tool whose `data` arg is an
                // object/array is simply treated as a normal tool call, not a crash.
                val chartData = (rawInput?.get("data") as? JsonPrimitive)?.contentOrNull
                if (toolName == "autovisualiser__show_chart" && chartData != null) {
                    onEvent(AcpEvent.Chart(chartData))
                } else {
                    // Same rawInput.command-first extraction the permission sheet already does —
                    // Desktop shows this detail, Grouse was dropping it and only keeping the title.
                    val detail = rawInput?.let { ri ->
                        ri["command"]?.jsonPrimitive?.contentOrNull
                            ?: ri.toString().takeIf { it != "{}" } ?: ""
                    } ?: ""
                    onEvent(AcpEvent.ToolCall(
                        update["title"]?.jsonPrimitive?.contentOrNull ?: "tool call", detail,
                        update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: ""))
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
            "tool_call_update" -> {
                val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
                val status = update["status"]?.jsonPrimitive?.contentOrNull ?: ""
                // content: [{type:"content", content:{type:"text", text:...}}, ...]
                val output = (update["content"] as? JsonArray).orEmpty().mapNotNull { el ->
                    ((el as? JsonObject)?.get("content") as? JsonObject)
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("\n")
                onEvent(AcpEvent.ToolCallUpdate(id, status, output))
            }
            "session_info_update" -> {
                val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                onEvent(AcpEvent.SessionInfoChanged(
                    sid,
                    update["title"]?.jsonPrimitive?.contentOrNull,
                    update["updatedAt"]?.jsonPrimitive?.contentOrNull))
            }
            "config_option_update" -> {
                val opts = parseConfig(update)
                if (opts.isNotEmpty()) onEvent(AcpEvent.Config(opts))
            }
            "current_mode_update" -> {
                update["currentModeId"]?.jsonPrimitive?.contentOrNull
                    ?.let { onEvent(AcpEvent.ModeChanged(it)) }
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
        } else if (method == "elicitation/create") {
            val mode = params?.get("mode")?.jsonPrimitive?.contentOrNull
            val schema = params?.get("requestedSchema") as? JsonObject
            if (mode != "form" || schema == null) {
                respond(id, buildJsonObject { put("action", "cancel") }); return
            }
            val required = (schema["required"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
            val fields = (schema["properties"] as? JsonObject).orEmpty().entries.map { (name, raw) ->
                val o = raw as? JsonObject ?: JsonObject(emptyMap())
                // Single-select comes as either untagged `enum` values or titled `oneOf`
                // [{const, title}] options; both collapse to Choice(value, label).
                val options =
                    (o["oneOf"] as? JsonArray).orEmpty().mapNotNull { el ->
                        val eo = el as? JsonObject ?: return@mapNotNull null
                        val v = eo["const"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        Choice(v, eo["title"]?.jsonPrimitive?.contentOrNull ?: v)
                    }.ifEmpty {
                        (o["enum"] as? JsonArray).orEmpty().mapNotNull { el ->
                            el.jsonPrimitive.contentOrNull?.let { Choice(it, it) }
                        }
                    }
                AcpEvent.ElicitField(
                    name = name,
                    type = o["type"]?.jsonPrimitive?.contentOrNull ?: "string",
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: name,
                    description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = options,
                    required = name in required,
                )
            }
            val key = "elicit-" + elicitSeq.getAndIncrement()
            pendingElicitations[key] = id
            onEvent(AcpEvent.Elicitation(
                key,
                params["message"]?.jsonPrimitive?.contentOrNull ?: "Input requested",
                schema["title"]?.jsonPrimitive?.contentOrNull ?: "",
                fields))
        } else {
            respond(id, buildJsonObject {})   // unknown request: empty result so the agent doesn't hang
        }
    }
}
