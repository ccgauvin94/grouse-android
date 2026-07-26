package id.gauvin.grouse

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted app state. Non-secret config lives in plain SharedPreferences; the X-Secret-Key
 * (which can drive an RCE-capable agent) lives in Keystore-backed EncryptedSharedPreferences.
 */
class SecureStore(context: Context) {
    private val app = context.applicationContext
    private val cfg: SharedPreferences = app.getSharedPreferences("goose", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy { openSecure() }

    private fun openSecure(): SharedPreferences {
        fun create(): SharedPreferences {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                app, "goose_secure", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // The Keystore-wrapped keyset can't be decrypted (e.g. app data restored to a new
            // device, or the key was invalidated). Reset the store so the app starts and can
            // re-onboard, instead of hard-crashing on every launch. The secret must be re-entered.
            app.deleteSharedPreferences("goose_secure")
            create()
        }
    }

    init {
        // One-time migration: the POC stored the key in plaintext "goose" prefs.
        val legacy = cfg.getString("key", null)
        if (!legacy.isNullOrBlank() && secure.getString("key", null).isNullOrBlank()) {
            secure.edit().putString("key", legacy).apply()
            cfg.edit().remove("key").apply()
        }
        // Move off the ACP-only server (:3285) to the full agent server (:3284) which also
        // serves ACP and exposes the extension API.
        if (cfg.getString("port", null) == "3285") cfg.edit().putString("port", "3284").apply()
        // The per-provider known_models cache is GONE (2026-07-25). Models are fetched live
        // from the server on every connect and held in memory only -- see
        // ConnectionManager's AcpEvent.SupportedModels. Two one-time migrations used to live
        // here (flat -> per-provider split, then a cross-provider sanitize); both existed
        // purely to repair a cache that should never have been persistent, so they went with
        // it. Drop the dead keys so an upgraded install doesn't carry them forever.
        cfg.edit()
            .remove("known_models")
            .remove("known_models_openai")
            .remove("known_models_openrouter")
            .remove("known_models_sanitized")
            .apply()
        // Per-session-type extension profiles are GONE (2026-07-25) -- see the note in
        // ConnectionManager. Goose defines exactly two scopes (global config.yaml, and
        // session-scoped add/remove); a third layer keyed on session type just gave "what tools does
        // this chat have" a third owner. Drop its keys so they don't linger in prefs forever.
        cfg.edit().apply {
            for (k in listOf("assistant", "chat", "code")) {
                remove("profile_${k}_on")
                remove("profile_${k}_ext")
            }
        }.apply()
    }

    var host: String
        get() = cfg.getString("host", "192.168.1.5") ?: "192.168.1.5"
        set(v) = cfg.edit().putString("host", v).apply()

    var port: String
        get() = cfg.getString("port", "3284") ?: "3284"
        set(v) = cfg.edit().putString("port", v).apply()

    var dynamicColor: Boolean
        get() = cfg.getBoolean("dynamic_color", true)
        set(v) = cfg.edit().putBoolean("dynamic_color", v).apply()

    /** Gate app open / stored-key reconnect behind a biometric (or device-credential) prompt.
     *  Default OFF — the transport is tailnet-only and the lock is friction most users don't
     *  want; opt in from Settings › Security. Only takes effect when the device actually has an
     *  authenticator enrolled (Biometric.available). */
    var biometricLock: Boolean
        get() = cfg.getBoolean("biometric_lock", false)
        set(v) = cfg.edit().putBoolean("biometric_lock", v).apply()

    /** Keep a foreground connection alive even when idle (opt-in; costs battery). */
    var persistentConnection: Boolean
        get() = cfg.getBoolean("persistent_conn", false)
        set(v) = cfg.edit().putBoolean("persistent_conn", v).apply()

    /** Show goose's full provider catalog vs. just the configured ones. */
    var showAllProviders: Boolean
        get() = cfg.getBoolean("show_all_providers", false)
        set(v) = cfg.edit().putBoolean("show_all_providers", v).apply()

    /** Read agent replies aloud (TextToSpeech) when a turn finishes. */
    var speakReplies: Boolean
        get() = cfg.getBoolean("speak_replies", false)
        set(v) = cfg.edit().putBoolean("speak_replies", v).apply()

    // --- Voice assistant model override ---
    // Self-hosted latency is painful for voice, so a voice turn can run on a faster (cloud) model
    // just for that turn, then restore. Blank model = use the session's current model.
    var voiceProvider: String
        get() = cfg.getString("voice_provider", "") ?: ""
        set(v) = cfg.edit().putString("voice_provider", v).apply()
    var voiceModel: String
        get() = cfg.getString("voice_model", "") ?: ""
        set(v) = cfg.edit().putString("voice_model", v).apply()

    /** Last opened session, so a notification reply after process death can resume it. */
    var lastSessionId: String?
        get() = cfg.getString("last_session", null)
        set(v) = cfg.edit().putString("last_session", v).apply()

    /** Session id of a prompt THIS device sent and hasn't seen a local TurnDone for yet — used to
     *  filter the goose Stop-hook push, which fires for every client's turns (Desktop included) and
     *  can't tell them apart server-side. lastSessionId ("session I have open") isn't enough: the
     *  Assistant thread is one session shared by every client (title-matched), so Desktop typing in
     *  it makes lastSessionId match too. This tracks "a turn I'm actually waiting on" instead.
     *  Persisted (not in-memory) because the whole point is surviving process death while backgrounded. */
    var pendingPushSessionId: String?
        get() = cfg.getString("pending_push_session", null)
        set(v) = cfg.edit().putString("pending_push_session", v).apply()

    /** The cwd lastSessionId was opened with — a cold-start fallback for resolving a resume's cwd
     *  before any session/list round-trip has populated the in-memory cache (see
     *  ConnectionManager.open()). Wrong here just means a stale-cwd guess, never a crash. */
    var lastSessionCwd: String
        get() = cfg.getString("last_session_cwd", "/state") ?: "/state"
        set(v) = cfg.edit().putString("last_session_cwd", v).apply()

    /** Recently used /workspace project names for the "New Code session" dialog, most-recent-first,
     *  capped at 10. A delimited string (not a StringSet) because order matters here — unlike
     *  knownModels, which doesn't care about recency. */
    fun recentWorkspaceProjects(): List<String> =
        (cfg.getString("recent_workspace_projects", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addRecentWorkspaceProject(name: String) {
        val cur = recentWorkspaceProjects().filterNot { it == name }
        val next = (listOf(name) + cur).take(10)
        cfg.edit().putString("recent_workspace_projects", next.joinToString("\n")).apply()
    }

    /** When the last proactive briefing push arrived (epoch millis) — shown on the Assistant status. */
    var lastBriefingAt: Long
        get() = cfg.getLong("last_briefing_at", 0L)
        set(v) = cfg.edit().putLong("last_briefing_at", v).apply()

    /** The text of the last proactive briefing push — the day's headline, shown on the status card. */
    var lastBriefingText: String
        get() = cfg.getString("last_briefing_text", "") ?: ""
        set(v) = cfg.edit().putString("last_briefing_text", v).apply()

    /** Whether the one-time "this is your assistant" hint has been dismissed. */
    var assistantHintSeen: Boolean
        get() = cfg.getBoolean("assistant_hint_seen", false)
        set(v) = cfg.edit().putBoolean("assistant_hint_seen", v).apply()

    /** Cached id of the goose-assistant thread so the app can land on it directly at startup. */
    var assistantSessionId: String?
        get() = cfg.getString("assistant_session", null)
        set(v) = cfg.edit().putString("assistant_session", v).apply()

    /** How the privileged Assistant thread handles tool actions: confirm | auto | readonly. */
    var assistantActions: String
        get() = cfg.getString("assistant_actions", "confirm") ?: "confirm"
        set(v) = cfg.edit().putString("assistant_actions", v).apply()

    // --- UnifiedPush ---
    var pushEnabled: Boolean
        get() = cfg.getBoolean("push_on", false)
        set(v) = cfg.edit().putBoolean("push_on", v).apply()

    /** The UnifiedPush endpoint URL the distributor gave us; phaethon POSTs here to reach us. */
    var pushEndpoint: String
        get() = cfg.getString("push_endpoint", "") ?: ""
        set(v) = cfg.edit().putString("push_endpoint", v).apply()

    /** Optional phaethon URL the app POSTs its endpoint to, so the server knows where to push. */
    var pushRegistryUrl: String
        get() = cfg.getString("push_registry", "") ?: ""
        set(v) = cfg.edit().putString("push_registry", v).apply()

    /** Real model slugs we've seen active, scoped PER PROVIDER (goose hides non-featured models
     *  like z-ai/glm-5.2). Provider-scoping stops LocalAI models leaking into the OpenRouter list
     *  and vice-versa. */

    fun savedOptions(ids: List<String>): Map<String, String> =
        ids.mapNotNull { id -> cfg.getString("opt_$id", null)?.let { id to it } }.toMap()

    fun saveOption(id: String, value: String) = cfg.edit().putString("opt_$id", value).apply()

    var secretKey: String
        get() = secure.getString("key", "") ?: ""
        set(v) = secure.edit().putString("key", v).apply()

    fun hasKey(): Boolean = secretKey.isNotBlank()
}
