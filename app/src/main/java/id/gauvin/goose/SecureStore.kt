package id.gauvin.goose

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

    // --- Proactive assistant ---
    var proactiveEnabled: Boolean
        get() = cfg.getBoolean("proactive_on", false)
        set(v) = cfg.edit().putBoolean("proactive_on", v).apply()

    var proactiveTime: String   // HH:mm, 24h
        get() = cfg.getString("proactive_time", "08:00") ?: "08:00"
        set(v) = cfg.edit().putString("proactive_time", v).apply()

    var proactivePrompt: String
        get() = cfg.getString("proactive_prompt", null) ?: DEFAULT_PROACTIVE_PROMPT
        set(v) = cfg.edit().putString("proactive_prompt", v).apply()

    /** Real model slugs we've seen active (goose hides non-featured models like z-ai/glm-5.2). */
    var knownModels: Set<String>
        get() = cfg.getStringSet("known_models", emptySet()) ?: emptySet()
        set(v) = cfg.edit().putStringSet("known_models", HashSet(v)).apply()

    fun savedOptions(ids: List<String>): Map<String, String> =
        ids.mapNotNull { id -> cfg.getString("opt_$id", null)?.let { id to it } }.toMap()

    fun saveOption(id: String, value: String) = cfg.edit().putString("opt_$id", value).apply()

    var secretKey: String
        get() = secure.getString("key", "") ?: ""
        set(v) = secure.edit().putString("key", v).apply()

    fun hasKey(): Boolean = secretKey.isNotBlank()

    companion object {
        const val DEFAULT_PROACTIVE_PROMPT =
            "Check my calendar, email, and tasks for the next several hours. Tell me anything " +
            "urgent or that needs my attention — briefly, as a few bullet points. Do NOT take any " +
            "actions or change anything; only read and report. If nothing needs my attention, " +
            "reply with exactly: All clear."
    }
}
