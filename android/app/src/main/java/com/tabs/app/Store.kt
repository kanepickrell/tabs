package com.tabs.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

// --- wire shapes, as the Chrome extension writes them -----------------------

@Serializable
private data class RemoteTab(
    val url: String,
    val title: String = "",
    val favIconUrl: String? = null,
    val firstSeen: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
private data class RemoteDevice(
    val name: String = "device",
    val lastSync: Long = 0L,
    // Firebase omits empty objects entirely, so this has to tolerate absence.
    val tabs: Map<String, RemoteTab> = emptyMap(),
)

@Serializable
private data class CloseCommand(val type: String = "close", val url: String)

@Serializable
private data class ArchivedTab(
    val url: String,
    val title: String,
    val firstSeen: Long,
    val closedAt: Long,
)

// --- what the UI works with -------------------------------------------------

data class Tab(
    val hash: String,
    val deviceId: String,
    val deviceName: String,
    val url: String,
    val title: String,
    val firstSeen: Long,
) {
    val host: String
        get() = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    val ageDays: Long
        get() = (System.currentTimeMillis() - firstSeen) / 86_400_000L
}

/**
 * Firebase Realtime Database as a dumb JSON store over HTTPS — no Firebase SDK,
 * no google-services.json. The space id is the only credential (v0).
 */
class Store(dbUrl: String, private val spaceId: String) {

    private val base = dbUrl.trimEnd('/')
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()

    val configured: Boolean = base.isNotBlank() && spaceId.isNotBlank()

    private fun endpoint(vararg parts: String) =
        "$base/spaces/$spaceId/${parts.joinToString("/")}.json"

    private fun body(value: String) = value.toRequestBody(mediaType)

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $text")
            return text
        }
    }

    suspend fun tabs(): List<Tab> = withContext(Dispatchers.IO) {
        val text = execute(Request.Builder().url(endpoint("devices")).get().build())
        if (text.isBlank() || text == "null") return@withContext emptyList()

        json.decodeFromString<Map<String, RemoteDevice>>(text)
            .flatMap { (deviceId, device) ->
                device.tabs.map { (hash, tab) ->
                    Tab(
                        hash = hash,
                        deviceId = deviceId,
                        deviceName = device.name,
                        url = tab.url,
                        title = tab.title.ifBlank { tab.url },
                        firstSeen = tab.firstSeen,
                    )
                }
            }
            .sortedBy { it.firstSeen } // oldest first: that is the tab debt
    }

    /**
     * Archive first, then queue the close. The order matters: a swipe left must
     * never be able to lose a URL, because that fear is exactly what stops
     * anyone from closing a tab in the first place.
     */
    suspend fun close(tab: Tab) = withContext(Dispatchers.IO) {
        val archived = ArchivedTab(
            url = tab.url,
            title = tab.title,
            firstSeen = tab.firstSeen,
            closedAt = System.currentTimeMillis(),
        )
        execute(
            Request.Builder()
                .url(endpoint("archive", tab.hash))
                .put(body(json.encodeToString(archived)))
                .build()
        )
        execute(
            Request.Builder()
                .url(endpoint("commands", tab.deviceId))
                .post(body(json.encodeToString(CloseCommand(url = tab.url))))
                .build()
        )
    }
}
