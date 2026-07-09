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

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app, "goose_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        // One-time migration: the POC stored the key in plaintext "goose" prefs.
        val legacy = cfg.getString("key", null)
        if (!legacy.isNullOrBlank() && secure.getString("key", null).isNullOrBlank()) {
            secure.edit().putString("key", legacy).apply()
            cfg.edit().remove("key").apply()
        }
    }

    var host: String
        get() = cfg.getString("host", "192.168.1.5") ?: "192.168.1.5"
        set(v) = cfg.edit().putString("host", v).apply()

    var port: String
        get() = cfg.getString("port", "3285") ?: "3285"
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

    /** Last opened session, so a notification reply after process death can resume it. */
    var lastSessionId: String?
        get() = cfg.getString("last_session", null)
        set(v) = cfg.edit().putString("last_session", v).apply()

    fun savedOptions(ids: List<String>): Map<String, String> =
        ids.mapNotNull { id -> cfg.getString("opt_$id", null)?.let { id to it } }.toMap()

    fun saveOption(id: String, value: String) = cfg.edit().putString("opt_$id", value).apply()

    var secretKey: String
        get() = secure.getString("key", "") ?: ""
        set(v) = secure.edit().putString("key", v).apply()

    fun hasKey(): Boolean = secretKey.isNotBlank()
}
