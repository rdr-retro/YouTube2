package com.rdr.youtube2

import android.util.Xml
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class FeedVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val description: String,
    val duration: String,
    val viewCount: String,
    val section: String,
    val publishedAt: String
)

data class ChannelSummary(
    val channelId: String,
    val channelName: String,
    val channelIconUrl: String,
    val subscriberLabel: String = ""
)

object PublicYouTubeFeedClient {

    private data class FeedSource(val channelId: String, val section: String)
    private data class ChannelLoadResult(
        val videos: List<FeedVideo>,
        val exhausted: Boolean
    )
    private data class ChannelCacheEntry(
        val videos: List<FeedVideo>,
        val exhausted: Boolean
    )

    private val sources = listOf(
        FeedSource("UCBR8-60-B28hp2BmDPdntcQ", "YouTube Official"),
        FeedSource("UC_x5XG1OV2P6uZZ5FSM9Ttw", "Google Developers"),
        FeedSource("UC-lHJZR3Gqxm24_Vd_AJ5Yw", "Featured"),
        FeedSource("UCX6OQ3DkcsbYNE6H8uQQuVA", "Most Popular")
    )
    private val extraSources = listOf(
        FeedSource("UCVHFbqXqoYvEWM1Ddxl0QDg", "Trending Music"),
        FeedSource("UCEgdi0XIYZ4jaM2BKGcrFOw", "Trending Gaming"),
        FeedSource("UCYfdidRxbB8Qhf0Nx7ioOYw", "Tech Today"),
        FeedSource("UCi8e0iOVk1fEOogdfu4YgfA", "Entertainment"),
        FeedSource("UC9-y-6csu5WGm29I7JiwpnA", "Comedy"),
        FeedSource("UCF0pVplsI8R5kcAqgtoRqoA", "Popular Now"),
        FeedSource("UCWOA1ZGiwLbDQJk2xcd7XKA", "Spotlight"),
        FeedSource("UC29ju8bIPH5as8OGnQzwJyA", "Trending"),
        FeedSource("UCsT0YIqwnpJCM-mx7-gSA4Q", "World"),
        FeedSource("UCNye-wNBqNL5ZzHSJj3l8Bg", "Arts"),
        FeedSource("UClFSU9_bUb4Rc6OYfTt5SPw", "Science"),
        FeedSource("UCMtFAi84ehTSYSE9XoHefig", "Culture")
    )
    private val searchRendererKeys = listOf(
        "videoRenderer",
        "videoWithContextRenderer",
        "gridVideoRenderer"
    )
    private val channelCacheLock = Any()
    private val recentChannelCache = HashMap<String, ChannelCacheEntry>()
    private val oldestChannelCache = HashMap<String, ChannelCacheEntry>()

    private val httpClient: OkHttpClient by lazy(LazyThreadSafetyMode.NONE) {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun loadRecentVideos(limit: Int = 24): List<FeedVideo> = withContext(Dispatchers.IO) {
        // Pick 4 random sources from the combined pool for true variety
        val allSources = sources + extraSources
        val randomSources = allSources.shuffled().take(4)
        
        val all = coroutineScope {
            randomSources.map { source ->
                async {
                    val url = "https://www.youtube.com/feeds/videos.xml?channel_id=${source.channelId}"
                    val xml = download(url) ?: return@async emptyList<FeedVideo>()
                    parseFeed(xml, source.section)
                }
            }.awaitAll().flatten()
        }
        all
            .distinctBy { it.videoId }
            .sortedByDescending { it.publishedAt }
            .take(limit)
    }

    /**
     * Load more videos for infinite scroll.
     * Page 0 = original sources, page 1+ = slices of extraSources.
     * Excludes videos whose IDs are in [excludeIds].
     */
    suspend fun loadMoreRecentVideos(
        page: Int,
        limit: Int = 24,
        excludeIds: Set<String> = emptySet()
    ): List<FeedVideo> = withContext(Dispatchers.IO) {
        val pageSources = if (page <= 0) {
            // Pick random sources even for page 0 fallback
            val allSources = sources + extraSources
            allSources.shuffled().take(4)
        } else {
            // Pick random sources for every page to ensure "100% random" feel
            val allSources = sources + extraSources
            allSources.shuffled().take(4)
        }

        val all = coroutineScope {
            pageSources.map { source ->
                async {
                    val url = "https://www.youtube.com/feeds/videos.xml?channel_id=${source.channelId}"
                    val xml = download(url) ?: return@async emptyList<FeedVideo>()
                    parseFeed(xml, source.section)
                }
            }.awaitAll().flatten()
        }
        all
            .distinctBy { it.videoId }
            .filterNot { it.videoId in excludeIds }
            .sortedByDescending { it.publishedAt }
            .take(limit)
    }

    // ── Trending discovery queries for infinite feed ─────────────────
    private val discoveryQueries = listOf(
        "trending videos today", "most viewed this week", "viral videos",
        "new music videos", "best music 2024", "top hits",
        "gaming highlights", "best gaming moments", "esports",
        "technology news", "tech review", "gadgets 2024",
        "funny videos", "comedy sketches", "fails compilation",
        "cooking recipes", "street food", "food review",
        "travel vlog", "explore the world", "adventure travel",
        "fitness workout", "gym motivation", "yoga",
        "movie trailers", "film review", "documentaries",
        "science explained", "space exploration", "nature documentary",
        "sports highlights", "football goals", "basketball",
        "DIY projects", "life hacks", "how to",
        "art tutorial", "drawing", "creative timelapse",
        "motivation speech", "self improvement", "productivity tips",
        "car reviews", "supercar", "auto racing"
    )
    private var discoveryIndex = 0

    /**
     * Discover videos via search using rotating trending queries.
     * This provides truly infinite content beyond the finite RSS feeds.
     * Each call uses a different query so content is always fresh.
     */
    suspend fun discoverVideos(
        limit: Int = 20,
        excludeIds: Set<String> = emptySet()
    ): List<FeedVideo> = withContext(Dispatchers.IO) {
        // Try up to 3 queries in case one returns nothing
        repeat(3) {
            // Use random query instead of sequential rotation
            val query = discoveryQueries.random()
            val results = searchVideos(query, limit + 10)
                .filterNot { it.videoId in excludeIds }
                .take(limit)
            if (results.isNotEmpty()) return@withContext results
        }
        emptyList()
    }

    suspend fun searchVideos(query: String, limit: Int = 24): List<FeedVideo> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext emptyList()
        val safeLimit = limit.coerceIn(1, 50)

        val fromYouTubei = searchFromYouTubei(normalizedQuery, safeLimit)
        if (fromYouTubei.isNotEmpty()) {
            return@withContext fromYouTubei
        }

        val encodedQuery = URLEncoder.encode(normalizedQuery, "UTF-8")
        val html = download("https://www.youtube.com/results?search_query=$encodedQuery&hl=en")
            ?: return@withContext emptyList()

        parseSearchResults(html, normalizedQuery, safeLimit)
    }

    suspend fun loadVideoDetails(videoId: String): FeedVideo? = withContext(Dispatchers.IO) {
        val safeVideoId = sanitizeVideoId(videoId)
        if (safeVideoId.isBlank()) return@withContext null

        val fromFeed = loadVideoDetailsFromFeed(safeVideoId)
        if (fromFeed?.description?.isNotBlank() == true) {
            return@withContext fromFeed
        }

        val watchDescription = loadDescriptionFromWatchPage(safeVideoId)
        if (fromFeed != null && watchDescription.isNotBlank()) {
            return@withContext fromFeed.copy(description = watchDescription)
        }

        if (fromFeed != null) {
            return@withContext fromFeed
        }

        if (watchDescription.isBlank()) {
            return@withContext null
        }

        FeedVideo(
            videoId = safeVideoId,
            title = "",
            channel = "",
            thumbnailUrl = "https://i.ytimg.com/vi/$safeVideoId/hqdefault.jpg",
            description = watchDescription,
            duration = "",
            viewCount = "",
            section = "Video",
            publishedAt = ""
        )
    }

    suspend fun loadVideoDuration(videoId: String): String = withContext(Dispatchers.IO) {
        val safeVideoId = sanitizeVideoId(videoId)
        if (safeVideoId.isBlank()) return@withContext ""
        val html = download("https://www.youtube.com/watch?v=$safeVideoId&hl=en").orEmpty()
        if (html.isBlank()) return@withContext ""

        val lengthSeconds = Regex("\"lengthSeconds\":\"(\\d+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

        if (lengthSeconds != null && lengthSeconds > 0L) {
            return@withContext formatDurationFromSeconds(lengthSeconds.toString())
        }

        val durationMs = Regex("\"approxDurationMs\":\"(\\d+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

        if (durationMs != null && durationMs > 0L) {
            val seconds = durationMs / 1000L
            return@withContext formatDurationFromSeconds(seconds.toString())
        }

        ""
    }

    suspend fun resolveChannelSummary(channelId: String, channelName: String): ChannelSummary? = withContext(Dispatchers.IO) {
        val normalizedName = channelName.trim()
        val safeChannelId = sanitizeChannelId(channelId)

        val fromId = if (safeChannelId.isNotBlank()) {
            resolveChannelSummaryById(safeChannelId, normalizedName)
        } else {
            null
        }
        val fromName = if (normalizedName.isNotBlank()) {
            resolveChannelSummaryByNameInternal(normalizedName)
        } else {
            null
        }

        val resolvedId = safeChannelId.ifBlank { fromName?.channelId.orEmpty() }
        val resolvedName = fromId?.channelName
            ?.takeIf { it.isNotBlank() }
            ?: fromName?.channelName?.takeIf { it.isNotBlank() }
            ?: normalizedName
        val resolvedIcon = fromId?.channelIconUrl
            ?.takeIf { it.isNotBlank() }
            ?: fromName?.channelIconUrl?.takeIf { it.isNotBlank() }
            .orEmpty()
        val resolvedSubscribers = fromId?.subscriberLabel
            ?.takeIf { it.isNotBlank() }
            ?: fromName?.subscriberLabel?.takeIf { it.isNotBlank() }
            .orEmpty()

        if (resolvedId.isBlank() && resolvedName.isBlank() && resolvedIcon.isBlank()) {
            return@withContext null
        }

        ChannelSummary(
            channelId = resolvedId,
            channelName = resolvedName,
            channelIconUrl = resolvedIcon,
            subscriberLabel = resolvedSubscribers
        )
    }

    suspend fun resolveChannelSummaryByName(channelName: String): ChannelSummary? = withContext(Dispatchers.IO) {
        resolveChannelSummaryByNameInternal(channelName.trim())
    }

    suspend fun loadChannelVideos(channelId: String, channelName: String, limit: Int = 24): List<FeedVideo> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val safeChannelId = sanitizeChannelId(channelId)
        val normalizedName = channelName.trim()
        val cacheKey = buildChannelCacheKey(safeChannelId, normalizedName)
        val cached = readChannelCache(isOldest = false, key = cacheKey)
        if (cached != null && (cached.exhausted || cached.videos.size >= safeLimit)) {
            return@withContext cached.videos.take(safeLimit)
        }

        if (safeChannelId.isNotBlank()) {
            val fromHtml = loadChannelVideosFromHtml(
                channelId = safeChannelId,
                channelName = normalizedName,
                oldestFirst = false,
                limit = safeLimit
            )
            if (fromHtml.videos.isNotEmpty()) {
                writeChannelCache(
                    isOldest = false,
                    key = cacheKey,
                    entry = ChannelCacheEntry(
                        videos = fromHtml.videos,
                        exhausted = fromHtml.exhausted
                    )
                )
                return@withContext fromHtml.videos.take(safeLimit)
            }

            val xml = download("https://www.youtube.com/feeds/videos.xml?channel_id=$safeChannelId").orEmpty()
            if (xml.isNotBlank()) {
                val uploads = parseFeed(xml, "Uploads")
                    .sortedByDescending { it.publishedAt }
                    .take(safeLimit)
                if (uploads.isNotEmpty()) {
                    writeChannelCache(
                        isOldest = false,
                        key = cacheKey,
                        entry = ChannelCacheEntry(
                            videos = uploads,
                            exhausted = uploads.size < safeLimit
                        )
                    )
                    return@withContext uploads
                }
            }
        }

        if (normalizedName.isBlank()) {
            return@withContext cached?.videos?.take(safeLimit).orEmpty()
        }
        val normalizedNeedle = normalizeChannelName(normalizedName)
        val fallbackSearchLimit = safeMultiplyByTwo(safeLimit)
        val fallback = searchVideos(normalizedName, limit = fallbackSearchLimit)
            .filter { normalizeChannelName(it.channel) == normalizedNeedle }
            .ifEmpty {
                searchVideos(normalizedName, limit = fallbackSearchLimit)
                    .filter { normalizeChannelName(it.channel).contains(normalizedNeedle) }
            }
            .distinctBy { it.videoId }
            .take(safeLimit)
        if (fallback.isNotEmpty()) {
            writeChannelCache(
                isOldest = false,
                key = cacheKey,
                entry = ChannelCacheEntry(
                    videos = fallback,
                    exhausted = fallback.size < safeLimit
                )
            )
            return@withContext fallback
        }

        cached?.videos?.take(safeLimit).orEmpty()
    }

    suspend fun loadChannelVideosOldest(channelId: String, channelName: String, limit: Int = 24): List<FeedVideo> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val safeChannelId = sanitizeChannelId(channelId)
        val normalizedName = channelName.trim()
        val cacheKey = buildChannelCacheKey(safeChannelId, normalizedName)
        val cached = readChannelCache(isOldest = true, key = cacheKey)
        if (cached != null && (cached.exhausted || cached.videos.size >= safeLimit)) {
            return@withContext cached.videos.take(safeLimit)
        }

        // Try direct HTML scraping with sorting first
        if (safeChannelId.isNotBlank()) {
            val fromHtml = loadChannelVideosFromHtml(
                channelId = safeChannelId,
                channelName = normalizedName,
                oldestFirst = true,
                limit = safeLimit
            )
            if (fromHtml.videos.isNotEmpty()) {
                writeChannelCache(isOldest = true, key = cacheKey, entry = ChannelCacheEntry(videos = fromHtml.videos, exhausted = fromHtml.exhausted))
                return@withContext fromHtml.videos.take(safeLimit)
            }
        }

        // Deep fallback: Use YouTube search with "Oldest First" filter (sp=CAISAhAB)
        // This is much more reliable for finding the absolute oldest videos.
        val searchResults = if (normalizedName.isNotBlank()) {
            val filter = "sp=CAISAhAB" // Upload date (oldest first)
            val query = if (safeChannelId.isNotBlank()) "channel:$safeChannelId" else normalizedName
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val html = download("https://www.youtube.com/results?search_query=$encodedQuery&$filter&hl=en")
            if (html != null) {
                parseSearchResults(html, query, safeLimit)
            } else emptyList()
        } else emptyList()

        if (searchResults.isNotEmpty()) {
            writeChannelCache(isOldest = true, key = cacheKey, entry = ChannelCacheEntry(videos = searchResults, exhausted = searchResults.size < safeLimit))
            return@withContext searchResults
        }

        // Final fallback: local sort of whatever recent ones we have
        val fallback = loadChannelVideos(channelId, channelName, limit = safeLimit)
            .sortedBy { parseIsoToMillisForSort(it.publishedAt) ?: Long.MAX_VALUE }
            .take(safeLimit)
        if (fallback.isNotEmpty()) {
            writeChannelCache(isOldest = true, key = cacheKey, entry = ChannelCacheEntry(videos = fallback, exhausted = fallback.size < safeLimit))
            return@withContext fallback
        }

        cached?.videos?.take(safeLimit).orEmpty()
    }

    private fun buildChannelCacheKey(channelId: String, channelName: String): String {
        val safeId = sanitizeChannelId(channelId)
        if (safeId.isNotBlank()) {
            return "id:$safeId"
        }
        return "name:${normalizeChannelName(channelName.trim())}"
    }

    private fun readChannelCache(isOldest: Boolean, key: String): ChannelCacheEntry? {
        synchronized(channelCacheLock) {
            return if (isOldest) oldestChannelCache[key] else recentChannelCache[key]
        }
    }

    private fun writeChannelCache(isOldest: Boolean, key: String, entry: ChannelCacheEntry) {
        synchronized(channelCacheLock) {
            if (isOldest) {
                oldestChannelCache[key] = entry
            } else {
                recentChannelCache[key] = entry
            }
        }
    }

    private fun download(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 4.1; Mobile)")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body()?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun postJson(url: String, bodyText: String): String? {
        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, bodyText)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 4.1.2; Mobile)")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body()?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun searchFromYouTubei(query: String, limit: Int): List<FeedVideo> {
        val payload = buildYouTubeiSearchPayload(query)
        val url = "https://www.youtube.com/youtubei/v1/search?key=$YOUTUBEI_WEB_API_KEY&prettyPrint=false"
        val json = postJson(url, payload) ?: return emptyList()
        return parseSearchJson(json, query, limit)
    }

    private fun buildYouTubeiSearchPayload(query: String): String {
        val escapedQuery = escapeJsonString(query)
        return """
            {
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20241126.01.00",
                  "hl": "en",
                  "gl": "US"
                }
              },
              "query": "$escapedQuery"
            }
        """.trimIndent()
    }

    private fun parseSearchResults(html: String, query: String, limit: Int): List<FeedVideo> {
        val initialDataJson = extractInitialDataJson(html)
        if (initialDataJson.isBlank()) return emptyList()
        return parseSearchJson(initialDataJson, query, limit)
    }

    private fun parseSearchJson(
        jsonText: String,
        query: String,
        limit: Int,
        fallbackChannelName: String = "",
        sectionLabel: String = ""
    ): List<FeedVideo> {
        val maxAllowed = if (sectionLabel.isBlank()) 50 else Int.MAX_VALUE
        val safeLimit = limit.coerceIn(1, maxAllowed)

        val root = try {
            JsonParser.parseString(jsonText)
        } catch (_: Throwable) {
            return emptyList()
        }

        val renderers = mutableListOf<JsonObject>()
        collectVideoRenderers(root, renderers, safeLimit * 4)
        if (renderers.isEmpty()) return emptyList()

        return renderers
            .asSequence()
            .mapNotNull {
                mapVideoRenderer(
                    renderer = it,
                    query = query,
                    fallbackChannelName = fallbackChannelName,
                    sectionLabel = sectionLabel
                )
            }
            .distinctBy { it.videoId }
            .take(safeLimit)
            .toList()
    }

    private fun extractInitialDataJson(html: String): String {
        val markers = listOf(
            "var ytInitialData =",
            "window['ytInitialData'] =",
            "window[\"ytInitialData\"] =",
            "ytInitialData ="
        )

        for (marker in markers) {
            val markerIndex = html.indexOf(marker)
            if (markerIndex < 0) continue

            val valueStart = firstNonWhitespaceIndex(html, markerIndex + marker.length)
            if (valueStart < 0) continue

            val valueFirstChar = html[valueStart]
            if (valueFirstChar == '{') {
                val json = extractJsonObject(html, valueStart)
                if (json.isNotBlank()) {
                    return json
                }
            }

            if (valueFirstChar == '\'' || valueFirstChar == '"') {
                val rawLiteral = extractQuotedJsString(html, valueStart)
                if (rawLiteral.isBlank()) continue
                val decoded = decodeJsString(rawLiteral)
                if (decoded.trimStart().startsWith("{")) {
                    return decoded
                }
            }
        }
        return ""
    }

    private fun firstNonWhitespaceIndex(text: String, fromIndex: Int): Int {
        var index = fromIndex.coerceAtLeast(0)
        while (index < text.length) {
            if (!text[index].isWhitespace()) return index
            index += 1
        }
        return -1
    }

    private fun extractQuotedJsString(text: String, quoteIndex: Int): String {
        if (quoteIndex < 0 || quoteIndex >= text.length) return ""
        val quote = text[quoteIndex]
        if (quote != '\'' && quote != '"') return ""

        var escaping = false
        var index = quoteIndex + 1
        while (index < text.length) {
            val c = text[index]
            if (escaping) {
                escaping = false
                index += 1
                continue
            }
            if (c == '\\') {
                escaping = true
                index += 1
                continue
            }
            if (c == quote) {
                return text.substring(quoteIndex + 1, index)
            }
            index += 1
        }
        return ""
    }

    private fun decodeJsString(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c != '\\') {
                out.append(c)
                index += 1
                continue
            }
            if (index + 1 >= raw.length) {
                out.append('\\')
                break
            }

            when (val next = raw[index + 1]) {
                '\\' -> {
                    out.append('\\')
                    index += 2
                }
                '\'' -> {
                    out.append('\'')
                    index += 2
                }
                '"' -> {
                    out.append('"')
                    index += 2
                }
                '/' -> {
                    out.append('/')
                    index += 2
                }
                'b' -> {
                    out.append('\b')
                    index += 2
                }
                'f' -> {
                    out.append('\u000C')
                    index += 2
                }
                'n' -> {
                    out.append('\n')
                    index += 2
                }
                'r' -> {
                    out.append('\r')
                    index += 2
                }
                't' -> {
                    out.append('\t')
                    index += 2
                }
                'x' -> {
                    if (index + 3 < raw.length) {
                        val hex = raw.substring(index + 2, index + 4)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            index += 4
                            continue
                        }
                    }
                    out.append(next)
                    index += 2
                }
                'u' -> {
                    if (index + 5 < raw.length) {
                        val hex = raw.substring(index + 2, index + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            index += 6
                            continue
                        }
                    }
                    out.append(next)
                    index += 2
                }
                else -> {
                    out.append(next)
                    index += 2
                }
            }
        }
        return out.toString()
    }

    private fun extractJsonObject(text: String, startIndex: Int): String {
        if (startIndex < 0 || startIndex >= text.length || text[startIndex] != '{') {
            return ""
        }

        var depth = 0
        var inString = false
        var escaping = false

        for (index in startIndex until text.length) {
            val c = text[index]

            if (inString) {
                if (escaping) {
                    escaping = false
                } else if (c == '\\') {
                    escaping = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return ""
    }

    private fun collectVideoRenderers(
        element: JsonElement?,
        out: MutableList<JsonObject>,
        maxItems: Int
    ) {
        if (element == null || element.isJsonNull || out.size >= maxItems) return

        if (element.isJsonObject) {
            val obj = element.asJsonObject
            for (rendererKey in searchRendererKeys) {
                val renderer = obj.get(rendererKey)
                if (renderer != null && renderer.isJsonObject) {
                    out.add(renderer.asJsonObject)
                    if (out.size >= maxItems) return
                }
            }
            for ((_, child) in obj.entrySet()) {
                collectVideoRenderers(child, out, maxItems)
                if (out.size >= maxItems) return
            }
            return
        }

        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                collectVideoRenderers(child, out, maxItems)
                if (out.size >= maxItems) return
            }
        }
    }

    private fun mapVideoRenderer(
        renderer: JsonObject,
        query: String,
        fallbackChannelName: String = "",
        sectionLabel: String = ""
    ): FeedVideo? {
        val videoId = sanitizeVideoId(
            renderer.getString("videoId")
                .ifBlank {
                    renderer.getObject("navigationEndpoint")
                        ?.getObject("watchEndpoint")
                        ?.getString("videoId")
                        .orEmpty()
                }
        )
        if (videoId.isBlank()) return null

        val title = extractText(renderer.get("title"))
            .ifBlank { extractText(renderer.get("headline")) }
            .trim()
        if (title.isBlank()) return null

        val channel = extractText(renderer.get("ownerText"))
            .ifBlank { extractText(renderer.get("longBylineText")) }
            .ifBlank { extractText(renderer.get("shortBylineText")) }
            .ifBlank { fallbackChannelName }
            .ifBlank { "Unknown channel" }
            .trim()

        val thumbnail = extractThumbnailUrl(renderer)
            .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

        val duration = extractText(renderer.get("lengthText")).trim()

        val views = extractText(renderer.get("viewCountText"))
            .ifBlank { extractText(renderer.get("shortViewCountText")) }
            .trim()

        val description = extractDescription(renderer).trim()
        val publishedText = extractText(renderer.get("publishedTimeText")).trim()
        val publishedAt = estimatePublishedAtFromRelativeText(publishedText)

        return FeedVideo(
            videoId = videoId,
            title = title,
            channel = channel,
            thumbnailUrl = thumbnail,
            description = description,
            duration = duration,
            viewCount = views,
            section = if (sectionLabel.isBlank()) "Search: $query" else sectionLabel,
            publishedAt = publishedAt
        )
    }

    private fun loadChannelVideosFromHtml(
        channelId: String,
        channelName: String,
        oldestFirst: Boolean,
        limit: Int
    ): ChannelLoadResult {
        val safeLimit = limit.coerceAtLeast(1)
        val urls = if (oldestFirst) {
            listOf(
                "https://www.youtube.com/channel/$channelId/videos?view=0&sort=da&flow=grid&hl=en",
                "https://www.youtube.com/channel/$channelId/videos?sort=da&view=0&flow=grid&hl=en",
                "https://www.youtube.com/channel/$channelId/videos?sort=da&hl=en",
                "https://www.youtube.com/channel/$channelId/videos?hl=en"
            )
        } else {
            listOf(
                "https://www.youtube.com/channel/$channelId/videos?view=0&sort=dd&flow=grid&hl=en",
                "https://www.youtube.com/channel/$channelId/videos?sort=dd&view=0&flow=grid&hl=en",
                "https://www.youtube.com/channel/$channelId/videos?sort=dd&hl=en",
                "https://www.youtube.com/channel/$channelId/videos?hl=en"
            )
        }

        for (url in urls) {
            val html = download(url).orEmpty()
            if (html.isBlank()) continue

            // If we hit a redirect or empty results, try handle-based URL if channelId starts with @
            var finalHtml = html
            if (html.contains("window.location.replace") && channelId.startsWith("@")) {
                val handleUrl = "https://www.youtube.com/$channelId/videos?sort=${if (oldestFirst) "da" else "dd"}&hl=en"
                finalHtml = download(handleUrl).orEmpty()
            }
            if (finalHtml.isBlank()) continue

            val initialDataJson = extractInitialDataJson(finalHtml)
            if (initialDataJson.isBlank()) continue

            val parsed = parseChannelVideosFromHtml(
                html = finalHtml,
                channelName = channelName,
                limit = safeLimit
            )
            if (parsed.isEmpty()) continue

            val collected = mutableListOf<FeedVideo>()
            val seenVideoIds = HashSet<String>()
            for (video in parsed) {
                if (seenVideoIds.add(video.videoId)) {
                    collected.add(video)
                }
            }

            val apiKey = extractConfigValue(html, "INNERTUBE_API_KEY")
                .ifBlank { YOUTUBEI_WEB_API_KEY }
            val clientVersion = extractConfigValue(html, "INNERTUBE_CLIENT_VERSION")
                .ifBlank { DEFAULT_WEB_CLIENT_VERSION }
            val visitorData = extractConfigValue(html, "VISITOR_DATA")
            var continuationToken = extractNextContinuationToken(initialDataJson, currentToken = "")
            val seenTokens = HashSet<String>()
            while (collected.size < safeLimit &&
                continuationToken.isNotBlank()
            ) {
                if (!seenTokens.add(continuationToken)) {
                    break
                }
                val continuationJson = loadChannelContinuationJson(
                    apiKey = apiKey,
                    clientVersion = clientVersion,
                    visitorData = visitorData,
                    continuationToken = continuationToken
                ).orEmpty()
                if (continuationJson.isBlank()) {
                    break
                }

                val remaining = (safeLimit - collected.size).coerceAtLeast(1)
                val pageLimit = remaining
                    .coerceAtLeast(CHANNEL_CONTINUATION_PAGE_SIZE)
                    .coerceAtMost(safeLimit)
                val continuationVideos = parseSearchJson(
                    jsonText = continuationJson,
                    query = channelName,
                    limit = pageLimit,
                    fallbackChannelName = channelName,
                    sectionLabel = "Uploads"
                )
                if (continuationVideos.isEmpty()) {
                    break
                }
                for (video in continuationVideos) {
                    if (seenVideoIds.add(video.videoId)) {
                        collected.add(video)
                        if (collected.size >= safeLimit) {
                            break
                        }
                    }
                }

                val nextToken = extractNextContinuationToken(
                    jsonText = continuationJson,
                    currentToken = continuationToken
                )
                continuationToken = if (nextToken.isBlank() || seenTokens.contains(nextToken)) {
                    ""
                } else {
                    nextToken
                }
            }

            val sorted = if (oldestFirst) {
                collected.sortedBy { parseIsoToMillisForSort(it.publishedAt) ?: Long.MAX_VALUE }
            } else {
                collected.sortedByDescending { parseIsoToMillisForSort(it.publishedAt) ?: Long.MIN_VALUE }
            }
            return ChannelLoadResult(
                videos = sorted.take(safeLimit),
                exhausted = continuationToken.isBlank()
            )
        }
        return ChannelLoadResult(emptyList(), exhausted = true)
    }

    private fun parseChannelVideosFromHtml(
        html: String,
        channelName: String,
        limit: Int
    ): List<FeedVideo> {
        val initialDataJson = extractInitialDataJson(html)
        if (initialDataJson.isBlank()) return emptyList()
        return parseSearchJson(
            jsonText = initialDataJson,
            query = channelName,
            limit = safeMultiplyByTwo(limit),
            fallbackChannelName = channelName,
            sectionLabel = "Uploads"
        )
            .take(limit)
    }

    private fun safeMultiplyByTwo(value: Int): Int {
        if (value <= 0) return 1
        if (value > Int.MAX_VALUE / 2) return Int.MAX_VALUE
        return value * 2
    }

    private fun extractConfigValue(html: String, key: String): String {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun loadChannelContinuationJson(
        apiKey: String,
        clientVersion: String,
        visitorData: String,
        continuationToken: String
    ): String? {
        val safeApiKey = apiKey.ifBlank { YOUTUBEI_WEB_API_KEY }
        val payload = buildBrowseContinuationPayload(
            clientVersion = clientVersion,
            visitorData = visitorData,
            continuationToken = continuationToken
        )
        val url = "https://www.youtube.com/youtubei/v1/browse?key=$safeApiKey&prettyPrint=false"
        return postJson(url, payload)
    }

    private fun buildBrowseContinuationPayload(
        clientVersion: String,
        visitorData: String,
        continuationToken: String
    ): String {
        val safeVersion = clientVersion.ifBlank { DEFAULT_WEB_CLIENT_VERSION }
        val escapedVersion = escapeJsonString(safeVersion)
        val escapedVisitorData = escapeJsonString(visitorData)
        val escapedToken = escapeJsonString(continuationToken)

        val clientData = StringBuilder()
        clientData.append("\"clientName\":\"WEB\",")
        clientData.append("\"clientVersion\":\"").append(escapedVersion).append("\",")
        clientData.append("\"hl\":\"en\",")
        clientData.append("\"gl\":\"US\"")
        if (escapedVisitorData.isNotBlank()) {
            clientData.append(",\"visitorData\":\"").append(escapedVisitorData).append("\"")
        }

        return "{" +
            "\"context\":{" +
            "\"client\":{" + clientData.toString() + "}" +
            "}," +
            "\"continuation\":\"$escapedToken\"" +
            "}"
    }

    private fun extractNextContinuationToken(jsonText: String, currentToken: String): String {
        val root = try {
            JsonParser.parseString(jsonText)
        } catch (_: Throwable) {
            return ""
        }
        val tokens = mutableListOf<String>()
        collectContinuationTokens(root, tokens, maxTokens = 12)
        for (token in tokens) {
            if (token.isNotBlank() && token != currentToken) {
                return token
            }
        }
        return ""
    }

    private fun collectContinuationTokens(
        element: JsonElement?,
        out: MutableList<String>,
        maxTokens: Int
    ) {
        if (element == null || element.isJsonNull || out.size >= maxTokens) return
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val commandToken = obj.getObject("continuationCommand")
                ?.getString("token")
                .orEmpty()
            if (commandToken.isNotBlank()) {
                out.add(commandToken)
                if (out.size >= maxTokens) return
            }
            val nextDataToken = obj.getObject("nextContinuationData")
                ?.getString("continuation")
                .orEmpty()
            if (nextDataToken.isNotBlank()) {
                out.add(nextDataToken)
                if (out.size >= maxTokens) return
            }
            val reloadDataToken = obj.getObject("reloadContinuationData")
                ?.getString("continuation")
                .orEmpty()
            if (reloadDataToken.isNotBlank()) {
                out.add(reloadDataToken)
                if (out.size >= maxTokens) return
            }
            val endpointToken = obj.getObject("continuationEndpoint")
                ?.getObject("continuationCommand")
                ?.getString("token")
                .orEmpty()
            if (endpointToken.isNotBlank()) {
                out.add(endpointToken)
                if (out.size >= maxTokens) return
            }
            for ((_, child) in obj.entrySet()) {
                collectContinuationTokens(child, out, maxTokens)
                if (out.size >= maxTokens) return
            }
            return
        }

        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                collectContinuationTokens(child, out, maxTokens)
                if (out.size >= maxTokens) return
            }
        }
    }

    private fun extractThumbnailUrl(renderer: JsonObject): String {
        val thumbnails = renderer.getObject("thumbnail")
            ?.get("thumbnails")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return ""

        for (index in thumbnails.size() - 1 downTo 0) {
            val url = thumbnails[index]
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getString("url")
                .orEmpty()
            if (url.isNotBlank()) {
                return url
            }
        }
        return ""
    }

    private fun extractDescription(renderer: JsonObject): String {
        val metadataSnippets = renderer.get("detailedMetadataSnippets")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray

        if (metadataSnippets != null) {
            for (snippet in metadataSnippets) {
                val text = extractText(
                    snippet
                        .takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("snippetText")
                )
                if (text.isNotBlank()) {
                    return text
                }
            }
        }

        return extractText(renderer.get("descriptionSnippet"))
    }

    private fun findFirstChannelRenderer(element: JsonElement?): JsonObject? {
        if (element == null || element.isJsonNull) return null

        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val direct = obj.get("channelRenderer")
            if (direct != null && direct.isJsonObject) {
                return direct.asJsonObject
            }
            for ((_, child) in obj.entrySet()) {
                val nested = findFirstChannelRenderer(child)
                if (nested != null) return nested
            }
            return null
        }

        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                val nested = findFirstChannelRenderer(child)
                if (nested != null) return nested
            }
        }

        return null
    }

    private fun extractChannelThumbnailUrl(channelRenderer: JsonObject): String {
        val thumbnails = channelRenderer.getObject("thumbnail")
            ?.get("thumbnails")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return ""
        for (index in thumbnails.size() - 1 downTo 0) {
            val url = thumbnails[index]
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getString("url")
                .orEmpty()
            if (url.isNotBlank()) return normalizeImageUrl(url)
        }
        return ""
    }

    private fun resolveChannelSummaryByNameInternal(channelName: String): ChannelSummary? {
        val normalizedName = channelName.trim()
        if (normalizedName.isBlank()) return null

        val payload = buildYouTubeiSearchPayload(normalizedName)
        val url = "https://www.youtube.com/youtubei/v1/search?key=$YOUTUBEI_WEB_API_KEY&prettyPrint=false"
        val json = postJson(url, payload) ?: return null
        val root = try {
            JsonParser.parseString(json)
        } catch (_: Throwable) {
            return null
        }

        val channelRenderer = findFirstChannelRenderer(root)
            ?: findChannelRendererFromHtmlSearch(normalizedName)
            ?: return null
        val resolvedName = extractText(channelRenderer.get("title")).trim().ifBlank { normalizedName }
        val resolvedId = sanitizeChannelId(
            channelRenderer.getString("channelId")
                .ifBlank {
                    channelRenderer.getObject("navigationEndpoint")
                        ?.getObject("browseEndpoint")
                        ?.getString("browseId")
                        .orEmpty()
                }
        )
        val iconUrl = extractChannelThumbnailUrl(channelRenderer)
        val subscriberLabel = extractText(channelRenderer.get("subscriberCountText")).trim()

        return ChannelSummary(
            channelId = resolvedId,
            channelName = resolvedName,
            channelIconUrl = iconUrl,
            subscriberLabel = subscriberLabel
        )
    }

    private fun findChannelRendererFromHtmlSearch(query: String): JsonObject? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = download(
            "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAg%253D%253D&hl=en"
        ) ?: return null
        val initialData = extractInitialDataJson(html)
        if (initialData.isBlank()) return null
        val root = try {
            JsonParser.parseString(initialData)
        } catch (_: Throwable) {
            return null
        }
        return findFirstChannelRenderer(root)
    }

    private fun resolveChannelSummaryById(channelId: String, fallbackName: String): ChannelSummary? {
        val safeChannelId = sanitizeChannelId(channelId)
        if (safeChannelId.isBlank()) return null

        val channelUrl = "https://www.youtube.com/channel/$safeChannelId?hl=en"
        val html = download(channelUrl) ?: return null

        val resolvedName = decodeHtmlEntities(
            extractMetaTagContent(html, "property", "og:title")
                .ifBlank { extractMetaTagContent(html, "name", "title") }
                .ifBlank { fallbackName.trim() }
        ).trim().ifBlank { fallbackName.trim() }

        val resolvedIcon = normalizeImageUrl(
            extractMetaTagContent(html, "property", "og:image")
                .ifBlank { extractMetaTagContent(html, "itemprop", "image") }
        )
        val subscriberLabel = decodeHtmlEntities(extractSubscriberLabelFromHtml(html)).trim()

        if (resolvedName.isBlank() && resolvedIcon.isBlank()) return null

        return ChannelSummary(
            channelId = safeChannelId,
            channelName = resolvedName,
            channelIconUrl = resolvedIcon,
            subscriberLabel = subscriberLabel
        )
    }

    private fun extractMetaTagContent(html: String, attributeName: String, attributeValue: String): String {
        val attr = Regex.escape(attributeName)
        val value = Regex.escape(attributeValue)
        val patterns = listOf(
            Regex(
                "<meta[^>]*$attr\\s*=\\s*[\"']$value[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "<meta[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*$attr\\s*=\\s*[\"']$value[\"'][^>]*>",
                RegexOption.IGNORE_CASE
            )
        )
        for (pattern in patterns) {
            val content = pattern.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (content.isNotBlank()) return content
        }
        return ""
    }

    private fun extractSubscriberLabelFromHtml(html: String): String {
        val patterns = listOf(
            Regex(
                "\"subscriberCountText\"\\s*:\\s*\\{\\s*\"simpleText\"\\s*:\\s*\"([^\"]+)\"",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "\"subscriberCountText\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"",
                RegexOption.IGNORE_CASE
            )
        )
        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun estimatePublishedAtFromRelativeText(relative: String): String {
        val raw = relative.trim()
        if (raw.isBlank()) return ""

        val lower = raw.lowercase(Locale.US)
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        val amountMatch = Regex("(\\d+)\\s+(year|years|month|months|week|weeks|day|days|hour|hours|minute|minutes)\\s+ago")
            .find(lower)

        if (amountMatch != null) {
            val amount = amountMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: return ""
            val unit = amountMatch.groupValues.getOrNull(2).orEmpty()
            when (unit) {
                "year", "years" -> calendar.add(Calendar.YEAR, -amount)
                "month", "months" -> calendar.add(Calendar.MONTH, -amount)
                "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
                "hour", "hours" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
                "minute", "minutes" -> calendar.add(Calendar.MINUTE, -amount)
                else -> return ""
            }
        } else {
            when {
                lower.contains("yesterday") -> calendar.add(Calendar.DAY_OF_YEAR, -1)
                lower.contains("today") || lower.contains("just now") -> Unit
                else -> return ""
            }
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(calendar.time)
    }

    private fun parseIsoToMillisForSort(iso: String): Long? {
        val normalized = normalizeIsoOffsetForSort(iso)
        if (normalized.isBlank()) return null
        val candidates = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in candidates) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US)
                parser.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = parser.parse(normalized)
                if (parsed != null) return parsed.time
            } catch (_: Throwable) {
                // Try next format.
            }
        }
        return null
    }

    private fun normalizeIsoOffsetForSort(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return text
        if (text.endsWith("Z", ignoreCase = true)) {
            text = text.dropLast(1) + "+0000"
        }
        val tzMatch = Regex("([+-]\\d{2}):(\\d{2})$").find(text)
        if (tzMatch != null) {
            val compact = tzMatch.groupValues[1] + tzMatch.groupValues[2]
            text = text.removeRange(tzMatch.range) + compact
        }
        return text
    }

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun extractText(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) {
            return element.asString.orEmpty()
        }
        if (!element.isJsonObject) return ""

        val obj = element.asJsonObject
        val simpleText = obj.getString("simpleText").trim()
        if (simpleText.isNotBlank()) return simpleText

        val runs = obj.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val parts = mutableListOf<String>()
        for (run in runs) {
            val text = run
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getString("text")
                .orEmpty()
            if (text.isNotBlank()) {
                parts.add(text)
            }
        }
        return parts.joinToString("").trim()
    }

    private fun JsonObject.getString(name: String): String {
        val element = get(name) ?: return ""
        if (!element.isJsonPrimitive) return ""
        return try {
            element.asString.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun JsonObject.getObject(name: String): JsonObject? {
        val element = get(name) ?: return null
        if (!element.isJsonObject) return null
        return element.asJsonObject
    }

    private const val YOUTUBEI_WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val DEFAULT_WEB_CLIENT_VERSION = "2.20260213.01.00"
    private const val CHANNEL_CONTINUATION_PAGE_SIZE = 30

    private fun parseFeed(xml: String, section: String): List<FeedVideo> {
        val result = mutableListOf<FeedVideo>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml))
        }

        var inEntry = false
        var inAuthor = false
        var videoId = ""
        var title = ""
        var channel = ""
        var thumbnailUrl = ""
        var description = ""
        var duration = ""
        var viewCount = ""
        var publishedAt = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            inAuthor = false
                            videoId = ""
                            title = ""
                            channel = ""
                            thumbnailUrl = ""
                            description = ""
                            duration = ""
                            viewCount = ""
                            publishedAt = ""
                        }
                        "author" -> if (inEntry) inAuthor = true
                        "videoId" -> if (inEntry) videoId = parser.nextTextSafely()
                        "title" -> if (inEntry && !inAuthor) title = parser.nextTextSafely()
                        "description" -> if (inEntry) {
                            description = parser.nextTextSafely()
                        }
                        "name" -> if (inEntry && inAuthor) channel = parser.nextTextSafely()
                        "published" -> if (inEntry) publishedAt = parser.nextTextSafely()
                        "thumbnail" -> if (inEntry) {
                            thumbnailUrl = parser.findAttributeValue("url")
                        }
                        "duration" -> if (inEntry) {
                            duration = formatDurationFromSeconds(parser.findAttributeValue("seconds"))
                        }
                        "statistics" -> if (inEntry) {
                            viewCount = formatViewCount(parser.findAttributeValue("views"))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "author" -> inAuthor = false
                        "entry" -> {
                            inEntry = false
                            if (videoId.isNotBlank() && title.isNotBlank()) {
                                if (thumbnailUrl.isBlank()) {
                                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                }
                                result.add(
                                    FeedVideo(
                                        videoId = videoId,
                                        title = title,
                                        channel = channel.ifBlank { "Unknown channel" },
                                        thumbnailUrl = thumbnailUrl,
                                        description = description,
                                        duration = duration,
                                        viewCount = viewCount,
                                        section = section,
                                        publishedAt = publishedAt
                                    )
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        return result
    }

    private fun XmlPullParser.nextTextSafely(): String {
        return try {
            nextText()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun XmlPullParser.findAttributeValue(attributeName: String): String {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == attributeName) {
                return getAttributeValue(i) ?: ""
            }
        }
        return ""
    }

    private fun sanitizeVideoId(raw: String): String {
        return raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun sanitizeChannelId(raw: String): String {
        return raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun normalizeChannelName(raw: String): String {
        return raw.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
    }

    private fun loadVideoDetailsFromFeed(videoId: String): FeedVideo? {
        val url = "https://www.youtube.com/feeds/videos.xml?video_id=$videoId"
        val xml = download(url) ?: return null
        val items = parseFeed(xml, "Video")
        return items.firstOrNull { it.videoId == videoId } ?: items.firstOrNull()
    }

    private fun loadDescriptionFromWatchPage(videoId: String): String {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val html = download(watchUrl) ?: return ""

        val shortDescription = extractShortDescriptionFromPlayerResponse(html)
        if (shortDescription.isNotBlank()) {
            return shortDescription
        }

        return extractMetaDescription(html)
    }

    private fun extractShortDescriptionFromPlayerResponse(html: String): String {
        return try {
            val regex = Regex(
                "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val jsonText = regex.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (jsonText.isBlank()) return ""

            val root = JsonParser.parseString(jsonText).asJsonObject
            val videoDetailsElement = root.get("videoDetails")
            if (videoDetailsElement == null || !videoDetailsElement.isJsonObject) return ""

            videoDetailsElement.asJsonObject
                .get("shortDescription")
                ?.asString
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractMetaDescription(html: String): String {
        val patterns = listOf(
            Regex("<meta[^>]*name=\"description\"[^>]*content=\"([^\"]*)\""),
            Regex("<meta[^>]*property=\"og:description\"[^>]*content=\"([^\"]*)\""),
            Regex("<meta[^>]*itemprop=\"description\"[^>]*content=\"([^\"]*)\"")
        )

        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (value.isNotBlank()) {
                return decodeHtmlEntities(value).trim()
            }
        }

        return ""
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&#10;", "\n")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun normalizeImageUrl(rawUrl: String): String {
        val decoded = decodeHtmlEntities(rawUrl).trim()
        if (decoded.isBlank()) return ""
        return if (decoded.startsWith("//")) "https:$decoded" else decoded
    }

    private fun formatDurationFromSeconds(secondsText: String): String {
        val total = secondsText.toLongOrNull() ?: return ""
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun formatViewCount(rawCount: String): String {
        val n = rawCount.toLongOrNull() ?: return ""
        val withCommas = NumberFormat.getIntegerInstance(Locale.US).format(n)
        return "$withCommas views"
    }
}
