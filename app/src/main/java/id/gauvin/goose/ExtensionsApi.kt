package id.gauvin.goose

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One goose extension from GET /config/extensions (keeps the raw entry for round-tripping). */
data class ExtInfo(
    val name: String,
    val enabled: Boolean,
    val type: String,
    val description: String,
    val raw: JsonObject,
)

/**
 * Client for goosed's extension config API (the full `goosed agent` server, HTTPS).
 * Enabling/disabling here controls which extensions new conversations load — the context-saver.
 * Calls are blocking; run them off the main thread.
 */
class ExtensionsApi(private val baseUrl: String, private val key: String) {
    private val client = Net.builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON = "application/json".toMediaType()

    fun list(): List<ExtInfo> {
        val req = Request.Builder().url("$baseUrl/config/extensions")
            .addHeader("X-Secret-Key", key).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return emptyList()
            val arr = json.parseToJsonElement(body).jsonObject["extensions"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ExtInfo(
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    raw = o,
                )
            }
        }
    }

    /** Enable/disable an extension. Body shape verified: {name, config:<entry>, enabled}. */
    fun set(e: ExtInfo, enabled: Boolean): Boolean {
        val payload = buildJsonObject {
            put("name", e.name)
            put("config", e.raw)
            put("enabled", enabled)
        }.toString()
        val req = Request.Builder().url("$baseUrl/config/extensions")
            .addHeader("X-Secret-Key", key)
            .post(payload.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { return it.isSuccessful }
    }
}
