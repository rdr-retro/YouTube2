package com.rdr.youtube2

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Request
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.URLDecoder
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.abs

object YouTubeStreamResolver {

    private const val TAG = "YTResolver"

    data class StreamCandidate(
        val url: String,
        val qualityLabel: String,
        val mimeType: String,
        val source: String,
        val hasAudio: Boolean = true,
        val userAgent: String = BROWSER_USER_AGENT,
        val requestHeaders: Map<String, String> = emptyMap()
    )

    private data class YouTubeiProfile(
        val sourceName: String,
        val clientName: String,
        val clientVersion: String,
        val apiKey: String,
        val userAgent: String,
        val clientNumberHeader: String? = null
    )

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 4.1.2; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0 Mobile Safari/537.36"
    private const val YOUTUBE_ANDROID_USER_AGENT =
        "com.google.android.youtube/19.44.38 (Linux; U; Android 4.1.2; en_US)"

    private val browserHeaders = linkedMapOf(
        "Referer" to "https://www.youtube.com/",
        "Origin" to "https://www.youtube.com",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val androidHeaders = linkedMapOf(
        "Referer" to "https://www.youtube.com/",
        "Origin" to "https://www.youtube.com",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-YouTube-Client-Name" to "3",
        "X-YouTube-Client-Version" to "19.44.38"
    )

    private val invidiousInstances = listOf(
        "https://yewtu.be",
        "http://yewtu.be",
        "https://vid.puffyan.us",
        "http://vid.puffyan.us",
        "https://invidious.flokinet.to",
        "https://iv.melmac.space",
        "https://invidious.fdn.fr",
        "https://invidious.nerdvpn.de",
        "https://invidious.private.coffee",
        "https://invidious.slipfox.xyz"
    )

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.syncpundit.io",
        "https://api.piped.projectsegfau.lt",
        "http://api.piped.projectsegfau.lt"
    )

    private val youtubeiProfiles = listOf(
        YouTubeiProfile(
            sourceName = "youtubei-android",
            clientName = "ANDROID",
            clientVersion = "19.44.38",
            apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
            userAgent = YOUTUBE_ANDROID_USER_AGENT,
            clientNumberHeader = "3"
        ),
        YouTubeiProfile(
            sourceName = "youtubei-embedded",
            clientName = "ANDROID_EMBEDDED_PLAYER",
            clientVersion = "17.31.35",
            apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
            userAgent = YOUTUBE_ANDROID_USER_AGENT
        ),
        YouTubeiProfile(
            sourceName = "youtubei-web",
            clientName = "WEB",
            clientVersion = "2.20241126.01.00",
            apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
            userAgent = BROWSER_USER_AGENT,
            clientNumberHeader = "1"
        )
    )

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.NONE) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
        enableModernTls(builder)
        builder.build()
    }

    suspend fun resolve(
        videoId: String,
        includeVideoOnlyMp4: Boolean = false
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        val safeId = sanitizeVideoId(videoId)
        if (safeId.isBlank()) return@withContext emptyList()

        val collected = mutableListOf<StreamCandidate>()

        val youtubeiCandidates = fetchYoutubeiCandidates(safeId, includeVideoOnlyMp4)
        if (youtubeiCandidates.isNotEmpty()) {
            collected.addAll(youtubeiCandidates)
        }

        if (collected.size < 4) {
            for (instance in invidiousInstances) {
                val candidates = fetchInvidiousCandidates(instance, safeId, includeVideoOnlyMp4)
                if (candidates.isNotEmpty()) {
                    collected.addAll(candidates)
                    if (collected.size >= 8) break
                }
            }
        }

        if (collected.size < 4) {
            for (instance in pipedInstances) {
                val candidates = fetchPipedCandidates(instance, safeId, includeVideoOnlyMp4)
                if (candidates.isNotEmpty()) {
                    collected.addAll(candidates)
                    if (collected.size >= 8) break
                }
            }
        }

        if (collected.isEmpty()) {
            collected.addAll(fetchWatchPageManifests(safeId))
        }

        if (collected.size < 4) {
            collected.addAll(fetchGetVideoInfoCandidates(safeId, includeVideoOnlyMp4))
        }

        if (collected.size < 4) {
            collected.addAll(fetchWatchPlayerResponseCandidates(safeId, includeVideoOnlyMp4))
        }

        val sorted = sortCandidates(collected)
        Log.d(TAG, "resolve videoId=$safeId candidates=${sorted.size}")
        sorted
    }

    private fun fetchYoutubeiCandidates(
        videoId: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        val all = mutableListOf<StreamCandidate>()
        for (profile in youtubeiProfiles) {
            val candidates = fetchYoutubeiCandidates(videoId, profile, includeVideoOnlyMp4)
            if (candidates.isNotEmpty()) {
                all.addAll(candidates)
            }
            if (all.size >= 8) break
        }
        return all
    }

    private fun fetchYoutubeiCandidates(
        videoId: String,
        profile: YouTubeiProfile,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        val url = "https://www.youtube.com/youtubei/v1/player?key=${profile.apiKey}&prettyPrint=false"

        val payload = buildYoutubeiPayload(videoId, profile)
        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, payload)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Version", profile.clientVersion)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")

        profile.clientNumberHeader?.let {
            requestBuilder.header("X-YouTube-Client-Name", it)
        }

        val body = try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body()?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.d(TAG, "youtubei ${profile.sourceName} http=${response.code()} for $videoId")
                    return emptyList()
                }
                if (responseBody.isBlank()) {
                    Log.d(TAG, "youtubei ${profile.sourceName} empty body for $videoId")
                    return emptyList()
                }
                responseBody
            }
        } catch (t: Throwable) {
            Log.d(TAG, "youtubei ${profile.sourceName} failed for $videoId")
            return emptyList()
        }

        return parsePlayerResponseCandidates(body, profile.sourceName, includeVideoOnlyMp4)
    }

    private fun buildYoutubeiPayload(videoId: String, profile: YouTubeiProfile): String {
        val embeddedThirdParty = if (profile.clientName == "ANDROID_EMBEDDED_PLAYER") {
            """,
              "thirdParty": { "embedUrl": "https://www.youtube.com/" }
            """.trimIndent()
        } else {
            ""
        }

        return """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "hl": "en",
                  "gl": "US",
                  "clientName": "${profile.clientName}",
                  "clientVersion": "${profile.clientVersion}",
                  "androidSdkVersion": 16
                }$embeddedThirdParty
              },
              "contentCheckOk": true,
              "racyCheckOk": true
            }
        """.trimIndent()
    }

    private fun fetchInvidiousCandidates(
        instance: String,
        videoId: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        val url = "$instance/api/v1/videos/$videoId"
        val body = downloadText(url) ?: return emptyList()
        return parseInvidiousCandidates(body, instance, includeVideoOnlyMp4)
    }

    private fun fetchPipedCandidates(
        instance: String,
        videoId: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        val url = "$instance/streams/$videoId"
        val body = downloadText(url) ?: return emptyList()
        return parsePipedCandidates(body, instance, includeVideoOnlyMp4)
    }

    private fun fetchWatchPageManifests(videoId: String): List<StreamCandidate> {
        val url = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val body = downloadText(url) ?: return emptyList()

        val candidates = mutableListOf<StreamCandidate>()

        val hlsUrl = extractManifestUrl(body, "hlsManifestUrl")
        val dashUrl = extractManifestUrl(body, "dashManifestUrl")

        if (hlsUrl.startsWith("http")) {
            candidates.add(makeCandidate(hlsUrl, "hls", "application/x-mpegURL", "youtube-watch"))
        }
        if (dashUrl.startsWith("http")) {
            candidates.add(makeCandidate(dashUrl, "dash", "application/dash+xml", "youtube-watch"))
        }

        return candidates
    }

    private fun fetchGetVideoInfoCandidates(
        videoId: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        val url =
            "https://www.youtube.com/get_video_info?video_id=$videoId&el=detailpage&hl=en&eurl=https%3A%2F%2Fyoutube.googleapis.com%2Fv%2F$videoId"
        val body = downloadText(url) ?: return emptyList()
        val query = parseQueryParams(body)
        val playerResponseRaw = query["player_response"].orEmpty()
        if (playerResponseRaw.isBlank()) return emptyList()

        val decoded = decodeUrl(playerResponseRaw)
        return parsePlayerResponseCandidates(
            decoded,
            "youtube-get_video_info",
            includeVideoOnlyMp4
        )
    }

    private fun fetchWatchPlayerResponseCandidates(
        videoId: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        val url = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val body = downloadText(url) ?: return emptyList()

        val regex = Regex(
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val jsonText = regex.find(body)?.groupValues?.getOrNull(1).orEmpty()
        if (jsonText.isBlank()) return emptyList()

        return parsePlayerResponseCandidates(
            jsonText,
            "youtube-watch-player_response",
            includeVideoOnlyMp4
        )
    }

    private fun downloadText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Referer", "https://www.youtube.com/")
            .header("Origin", "https://www.youtube.com")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body()?.string()
            }
        } catch (_: Throwable) {
            Log.d(TAG, "downloadText failed url=$url")
            null
        }
    }

    private fun parseInvidiousCandidates(
        body: String,
        source: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val candidates = mutableListOf<StreamCandidate>()

            val hlsUrl = root.getString("hlsUrl")
            if (hlsUrl.startsWith("http")) {
                candidates.add(makeCandidate(hlsUrl, "hls", "application/x-mpegURL", source))
            }

            val dashUrl = root.getString("dashUrl")
            if (dashUrl.startsWith("http")) {
                candidates.add(makeCandidate(dashUrl, "dash", "application/dash+xml", source))
            }

            val formatStreams = root.getJsonArray("formatStreams")
            for (element in formatStreams) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject

                val streamUrl = item.getString("url")
                if (!streamUrl.startsWith("http")) continue

                val mimeType = item.getString("type").ifBlank { item.getString("mimeType") }
                val quality = item.getString("quality").ifBlank { item.getString("qualityLabel") }

                if (mimeType.contains("video/mp4")) {
                    candidates.add(
                        makeCandidate(
                            streamUrl,
                            quality.ifBlank { "mp4" },
                            "video/mp4",
                            source,
                            hasAudio = true
                        )
                    )
                }
            }

            val adaptiveFormats = root.getJsonArray("adaptiveFormats")
            for (element in adaptiveFormats) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject

                val streamUrl = item.getString("url")
                if (!streamUrl.startsWith("http")) continue

                val mimeType = item.getString("type").ifBlank { item.getString("mimeType") }
                val quality = item.getString("qualityLabel").ifBlank { item.getString("quality") }

                val isMp4 = mimeType.contains("video/mp4")
                val hasAudioInCodec = mimeType.contains("mp4a")
                if (isMp4 && (hasAudioInCodec || includeVideoOnlyMp4)) {
                    candidates.add(
                        makeCandidate(
                            streamUrl,
                            quality.ifBlank { "mp4" },
                            "video/mp4",
                            source,
                            hasAudio = hasAudioInCodec
                        )
                    )
                }
            }

            sortCandidates(candidates)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parsePipedCandidates(
        body: String,
        source: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val candidates = mutableListOf<StreamCandidate>()

            val hlsUrl = root.getString("hls")
            if (hlsUrl.startsWith("http")) {
                candidates.add(makeCandidate(hlsUrl, "hls", "application/x-mpegURL", source))
            }

            val dashUrl = root.getString("dash")
            if (dashUrl.startsWith("http")) {
                candidates.add(makeCandidate(dashUrl, "dash", "application/dash+xml", source))
            }

            val videoStreams = root.getJsonArray("videoStreams")
            for (element in videoStreams) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject

                val streamUrl = item.getString("url")
                if (!streamUrl.startsWith("http")) continue

                val videoOnly = item.getBoolean("videoOnly")
                if (videoOnly && !includeVideoOnlyMp4) continue

                val format = item.getString("format")
                val quality = item.getString("quality")
                val mimeType = if (format.contains("mp4", ignoreCase = true)) {
                    "video/mp4"
                } else {
                    "video/*"
                }

                candidates.add(
                    makeCandidate(
                        streamUrl,
                        quality.ifBlank { "video" },
                        mimeType,
                        source,
                        hasAudio = !videoOnly
                    )
                )
            }

            sortCandidates(candidates)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parsePlayerResponseCandidates(
        playerResponseJson: String,
        source: String,
        includeVideoOnlyMp4: Boolean
    ): List<StreamCandidate> {
        return try {
            val root = JsonParser.parseString(playerResponseJson).asJsonObject
            val streamingDataElement = root.get("streamingData")
            if (streamingDataElement == null || !streamingDataElement.isJsonObject) {
                logPlayabilityStatus(root, source)
                return emptyList()
            }
            val streamingData = streamingDataElement.asJsonObject
            val candidates = mutableListOf<StreamCandidate>()

            val hlsUrl = streamingData.getString("hlsManifestUrl")
            if (hlsUrl.startsWith("http")) {
                candidates.add(makeCandidate(hlsUrl, "hls", "application/x-mpegURL", source))
            }

            val dashUrl = streamingData.getString("dashManifestUrl")
            if (dashUrl.startsWith("http")) {
                candidates.add(makeCandidate(dashUrl, "dash", "application/dash+xml", source))
            }

            val formats = streamingData.getJsonArray("formats")
            for (element in formats) {
                if (!element.isJsonObject) continue
                addCandidateFromFormat(
                    candidates,
                    element.asJsonObject,
                    source,
                    includeVideoOnlyMp4
                )
            }

            val adaptiveFormats = streamingData.getJsonArray("adaptiveFormats")
            for (element in adaptiveFormats) {
                if (!element.isJsonObject) continue
                addCandidateFromFormat(
                    candidates,
                    element.asJsonObject,
                    source,
                    includeVideoOnlyMp4
                )
            }

            val sorted = sortCandidates(candidates)
            if (sorted.isEmpty()) {
                logPlayabilityStatus(root, source)
            }
            sorted
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun addCandidateFromFormat(
        target: MutableList<StreamCandidate>,
        format: JsonObject,
        source: String,
        includeVideoOnlyMp4: Boolean
    ) {
        val mimeType = format.getString("mimeType")
        val quality = format.getString("qualityLabel").ifBlank { format.getString("quality") }
        val baseUrl = format.getString("url")

        var resolvedUrl = baseUrl
        if (resolvedUrl.isBlank()) {
            val cipher = format.getString("signatureCipher").ifBlank { format.getString("cipher") }
            if (cipher.isNotBlank()) {
                val parsedCipher = parseQueryParams(cipher)
                val cipherUrl = decodeUrl(parsedCipher["url"].orEmpty())
                val sp = parsedCipher["sp"].orEmpty().ifBlank { "signature" }
                val sig = parsedCipher["sig"].orEmpty().ifBlank { parsedCipher["signature"].orEmpty() }
                val s = parsedCipher["s"].orEmpty() // encrypted signature, cannot be used without decipher
                resolvedUrl = when {
                    cipherUrl.isBlank() -> ""
                    sig.isNotBlank() -> "$cipherUrl&$sp=$sig"
                    s.isNotBlank() -> "" // skip encrypted signatures
                    else -> cipherUrl
                }
            }
        }

        if (!resolvedUrl.startsWith("http")) return

        if (mimeType.contains("audio/")) return

        val normalizedMime = when {
            mimeType.contains("application/x-mpegURL", ignoreCase = true) -> "application/x-mpegURL"
            mimeType.contains("application/dash+xml", ignoreCase = true) -> "application/dash+xml"
            mimeType.contains("video/mp4", ignoreCase = true) -> "video/mp4"
            else -> return
        }

        val hasAudioTrack = mimeType.contains("mp4a") || mimeType.contains("opus") || mimeType.contains("vorbis")
        if (
            normalizedMime == "video/mp4" &&
            !hasAudioTrack &&
            quality.isNotBlank() &&
            !includeVideoOnlyMp4
        ) {
            // Skip video-only MP4 tracks on old devices.
            return
        }

        target.add(
            makeCandidate(
                resolvedUrl,
                quality.ifBlank { "video" },
                normalizedMime,
                source,
                hasAudio = hasAudioTrack
            )
        )
    }

    private fun sortCandidates(input: List<StreamCandidate>): List<StreamCandidate> {
        return input
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
            .sortedBy { scoreForOldDevice(it) }
    }

    private fun scoreForOldDevice(candidate: StreamCandidate): Int {
        val sourceScore = when {
            candidate.source.startsWith("youtubei") -> 0
            candidate.source.contains("invidious", ignoreCase = true) -> 150
            candidate.source.contains("piped", ignoreCase = true) -> 200
            candidate.source.contains("watch", ignoreCase = true) -> 1200
            else -> 400
        }

        val mimeScore = when {
            candidate.mimeType.contains("application/x-mpegURL") -> 0
            candidate.mimeType.contains("video/mp4") -> 1000
            candidate.mimeType.contains("application/dash+xml") -> 2000
            else -> 4000
        }

        val quality = parseQuality(candidate.qualityLabel)
        val qualityScore = if (quality > 0) abs(quality - 480) else 500

        return sourceScore + mimeScore + qualityScore
    }

    private fun makeCandidate(
        url: String,
        qualityLabel: String,
        mimeType: String,
        source: String,
        hasAudio: Boolean = true
    ): StreamCandidate {
        val normalizedUrl = normalizeStreamUrl(url)
        val userAgent = if (source.startsWith("youtubei-android") || source.startsWith("youtubei-embedded")) {
            YOUTUBE_ANDROID_USER_AGENT
        } else {
            BROWSER_USER_AGENT
        }
        val headers = when {
            source.startsWith("youtubei-android") || source.startsWith("youtubei-embedded") -> androidHeaders
            source.startsWith("youtubei") -> browserHeaders
            source.contains("youtube", ignoreCase = true) -> browserHeaders
            else -> emptyMap()
        }
        return StreamCandidate(
            url = normalizedUrl,
            qualityLabel = qualityLabel,
            mimeType = mimeType,
            source = source,
            hasAudio = hasAudio,
            userAgent = userAgent,
            requestHeaders = headers
        )
    }

    private fun normalizeStreamUrl(rawUrl: String): String {
        var url = decodeEscapedUrl(rawUrl).trim()
        if (!url.startsWith("http")) return url
        if (url.contains("googlevideo.com") && !url.contains("ratebypass=")) {
            url += if (url.contains("?")) "&ratebypass=yes" else "?ratebypass=yes"
        }
        return url
    }

    private fun parseQuality(text: String): Int {
        val match = Regex("(\\d{2,4})p").find(text.lowercase())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun decodeEscapedUrl(raw: String): String {
        return raw
            .replace("\\\\u0026", "&")
            .replace("\\u0026", "&")
            .replace("\\\\u003d", "=")
            .replace("\\u003d", "=")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun decodeUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            decodeEscapedUrl(URLDecoder.decode(raw, "UTF-8"))
        } catch (_: Throwable) {
            decodeEscapedUrl(raw)
        }
    }

    private fun parseQueryParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val parts = raw.split("&")
        for (part in parts) {
            if (part.isBlank()) continue
            val idx = part.indexOf("=")
            val key = if (idx >= 0) part.substring(0, idx) else part
            val value = if (idx >= 0) part.substring(idx + 1) else ""
            if (key.isBlank()) continue
            map[decodeUrl(key)] = decodeUrl(value)
        }
        return map
    }

    private fun extractManifestUrl(body: String, key: String): String {
        val directPattern = Regex("\"$key\":\"([^\"]+)\"")
        val escapedPattern = Regex("\\\\\"$key\\\\\":\\\\\"([^\\\\\"]+)\\\\\"")

        val direct = directPattern.find(body)?.groupValues?.getOrNull(1).orEmpty()
        if (direct.isNotBlank()) return decodeEscapedUrl(direct)

        val escaped = escapedPattern.find(body)?.groupValues?.getOrNull(1).orEmpty()
        if (escaped.isNotBlank()) return decodeEscapedUrl(escaped)

        return ""
    }

    private fun sanitizeVideoId(raw: String): String {
        return raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun logPlayabilityStatus(root: JsonObject, source: String) {
        try {
            val playability = root.get("playabilityStatus")
            if (playability != null && playability.isJsonObject) {
                val status = playability.asJsonObject.getString("status")
                val reason = playability.asJsonObject.getString("reason")
                if (status.isNotBlank() || reason.isNotBlank()) {
                    Log.d(TAG, "playability source=$source status=$status reason=$reason")
                }
            }
        } catch (_: Throwable) {
            // Best-effort debug logging only.
        }
    }

    private fun enableModernTls(builder: OkHttpClient.Builder) {
        try {
            val trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(null as KeyStore?)
            val trustManager =
                trustManagerFactory.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
                    ?: return

            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(Tls12SocketFactory(sslContext.socketFactory), trustManager)
            builder.connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build(),
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )
        } catch (_: Throwable) {
            // Keep defaults if TLS customization is unavailable on the device.
        }
    }

    private class Tls12SocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {

        override fun createSocket(): Socket = patch(delegate.createSocket())

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(
            s: Socket,
            host: String,
            port: Int,
            autoClose: Boolean
        ): Socket = patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = patch(delegate.createSocket(address, port, localAddress, localPort))

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                socket.enabledProtocols = socket.enabledProtocols
                    .filter { it.equals("TLSv1.2", ignoreCase = true) || it.equals("TLSv1.1", ignoreCase = true) }
                    .ifEmpty { listOf("TLSv1.2") }
                    .toTypedArray()
            }
            return socket
        }
    }

    private fun JsonObject.getString(key: String): String {
        val value = this.get(key) ?: return ""
        return try {
            if (value.isJsonNull) "" else value.asString
        } catch (_: Throwable) {
            ""
        }
    }

    private fun JsonObject.getBoolean(key: String): Boolean {
        val value = this.get(key) ?: return false
        return try {
            !value.isJsonNull && value.asBoolean
        } catch (_: Throwable) {
            false
        }
    }

    private fun JsonObject.getJsonArray(key: String): JsonArray {
        val value = this.get(key)
        return if (value != null && value.isJsonArray) value.asJsonArray else JsonArray()
    }
}
