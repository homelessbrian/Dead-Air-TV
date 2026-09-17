package com.example.data.webqueue

import android.content.Context
import android.util.Log
import com.example.data.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One queued item as reported by the web queue, with the server's own start estimate. */
data class WebQueueItem(
    val media: MediaItem,
    val estimatedStartMs: Long,
    val coverArtUrl: String = ""
)

sealed class WebQueueState {
    data object Disconnected : WebQueueState()
    data object Linking : WebQueueState()
    data class Connected(val username: String, val deviceName: String) : WebQueueState()
    data class Failed(val message: String) : WebQueueState()
}

/**
 * Client for a kryten-webqueue instance's public device API
 * (https://github.com/grobertson/kryten-webqueue, docs/PUBLIC_API.md).
 *
 * Linking: the user opens the queue site's "Link a Device" page, gets a 5-character code,
 * and types it into the app. The app exchanges it once for a long-lived API key, which is
 * then sent as a Bearer token. While linked, the queue is polled and exposed as [items].
 */
class WebQueueRepository(context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("webqueue", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<WebQueueState>(
        if (apiKey().isNotBlank())
            WebQueueState.Connected(prefs.getString("username", "").orEmpty(), prefs.getString("device", "").orEmpty())
        else WebQueueState.Disconnected
    )
    val state: StateFlow<WebQueueState> = _state.asStateFlow()

    private val _items = MutableStateFlow<List<WebQueueItem>>(emptyList())
    val items: StateFlow<List<WebQueueItem>> = _items.asStateFlow()

    private var pollJob: Job? = null

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        set(value) { prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply() }

    private fun apiKey(): String = prefs.getString("api_key", "").orEmpty()

    init {
        if (_state.value is WebQueueState.Connected) startPolling()
    }

    // ---------------- Linking ----------------

    /** Exchange the 5-character code from the site's "Link a Device" page for an API key. */
    suspend fun link(code: String) {
        val normalized = code.trim().uppercase().replace(" ", "").replace("-", "")
        if (normalized.length != 5) { _state.value = WebQueueState.Failed("Code must be 5 characters"); return }
        _state.value = WebQueueState.Linking
        val result = runCatching { post("/api/public/v1/link", JSONObject().put("code", normalized)) }
        result.onSuccess { (status, body) ->
            val json = runCatching { JSONObject(body) }.getOrNull()
            val key = json?.optString("api_key").orEmpty()
            if (status in 200..299 && key.isNotBlank()) {
                val user = json?.optString("username").orEmpty()
                val device = json?.optString("device_name").orEmpty()
                prefs.edit().putString("api_key", key).putString("username", user).putString("device", device).apply()
                _state.value = WebQueueState.Connected(user, device)
                startPolling()
            } else {
                _state.value = WebQueueState.Failed(errorFrom(status, body))
            }
        }.onFailure {
            Log.w(TAG, "link failed", it)
            _state.value = WebQueueState.Failed(it.message ?: "Network error")
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        prefs.edit().remove("api_key").remove("username").remove("device").apply()
        _items.value = emptyList()
        _state.value = WebQueueState.Disconnected
    }

    // ---------------- Queue polling ----------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val ok = refreshOnce()
                delay(if (ok) POLL_INTERVAL_MS else POLL_RETRY_MS)
            }
        }
    }

    private suspend fun refreshOnce(): Boolean {
        val key = apiKey()
        if (key.isBlank()) return false
        val (status, body) = runCatching { get("/api/public/v1/queue", key) }.getOrElse {
            Log.w(TAG, "queue poll failed", it); return false
        }
        if (status == 401) {
            Log.w(TAG, "web queue key rejected (revoked?)")
            prefs.edit().remove("api_key").apply()
            _items.value = emptyList()
            _state.value = WebQueueState.Failed("Device link revoked — link again")
            pollJob?.cancel()
            return false
        }
        if (status !in 200..299) return false
        _items.value = parseQueue(body)
        return true
    }

    private fun parseQueue(body: String): List<WebQueueItem> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<WebQueueItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            // The guide already has the on-screen item from CyTube itself.
            if (it.optBoolean("is_now_playing", false)) continue
            val title = it.optString("title").ifBlank { "Untitled" }
            val duration = it.optDouble("duration_sec", 0.0).takeIf { d -> !d.isNaN() } ?: 0.0
            val startIn = it.optDouble("estimated_start_in_sec", -1.0)
            val startMs = if (startIn >= 0) System.currentTimeMillis() + (startIn * 1000).toLong() else 0L
            val poster = it.optString("cover_art_url").takeIf { s -> s != "null" }.orEmpty()
            out += WebQueueItem(
                media = MediaItem(
                    id = it.optString("friendly_token").ifBlank { it.opt("uid")?.toString().orEmpty() },
                    title = title,
                    durationSeconds = duration,
                    type = "cm",
                    posterUrl = poster
                ),
                estimatedStartMs = startMs,
                coverArtUrl = poster
            )
        }
        return out.sortedBy { w -> w.estimatedStartMs.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }
    }

    // ---------------- HTTP ----------------

    private suspend fun post(path: String, json: JSONObject): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(json.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp -> resp.code to (resp.body?.string().orEmpty()) }
    }

    private suspend fun get(path: String, key: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $key")
            .build()
        client.newCall(req).execute().use { resp -> resp.code to (resp.body?.string().orEmpty()) }
    }

    private fun errorFrom(status: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
        return if (detail.isNotBlank()) detail else "HTTP $status"
    }

    companion object {
        private const val TAG = "WebQueueRepository"
        const val DEFAULT_BASE_URL = "https://queue.dropsugar.co"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val POLL_RETRY_MS = 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
