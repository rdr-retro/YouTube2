package com.rdr.youtube2

import com.google.gson.annotations.SerializedName

// ── Video list / search responses ──────────────────────────────────────

data class YouTubeResponse(
    @SerializedName("items") val items: List<YouTubeItem> = emptyList(),
    @SerializedName("nextPageToken") val nextPageToken: String? = null
)

data class YouTubeItem(
    @SerializedName("id") val id: Any? = null,           // String for videos.list, object for search
    @SerializedName("snippet") val snippet: Snippet? = null,
    @SerializedName("statistics") val statistics: Statistics? = null,
    @SerializedName("contentDetails") val contentDetails: ContentDetails? = null
) {
    /** Extracts videoId whether item comes from videos.list or search.list */
    fun videoId(): String {
        return when (id) {
            is String -> id
            is Map<*, *> -> (id["videoId"] as? String) ?: ""
            else -> {
                // Gson deserializes objects as LinkedTreeMap
                try {
                    val map = id as? Map<*, *>
                    map?.get("videoId")?.toString() ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
        }
    }
}

data class Snippet(
    @SerializedName("title") val title: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("channelTitle") val channelTitle: String = "",
    @SerializedName("channelId") val channelId: String = "",
    @SerializedName("publishedAt") val publishedAt: String = "",
    @SerializedName("thumbnails") val thumbnails: Thumbnails? = null,
    @SerializedName("categoryId") val categoryId: String? = null
)

data class Thumbnails(
    @SerializedName("default") val default: ThumbnailInfo? = null,
    @SerializedName("medium") val medium: ThumbnailInfo? = null,
    @SerializedName("high") val high: ThumbnailInfo? = null
)

data class ThumbnailInfo(
    @SerializedName("url") val url: String = "",
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0
)

data class Statistics(
    @SerializedName("viewCount") val viewCount: String = "0",
    @SerializedName("likeCount") val likeCount: String = "0",
    @SerializedName("dislikeCount") val dislikeCount: String = "0",
    @SerializedName("commentCount") val commentCount: String = "0"
)

data class ContentDetails(
    @SerializedName("duration") val duration: String = ""  // ISO 8601 e.g. PT4M13S
)

// ── Comment responses ──────────────────────────────────────────────────

data class CommentThreadResponse(
    @SerializedName("items") val items: List<CommentThread> = emptyList()
)

data class CommentThread(
    @SerializedName("snippet") val snippet: CommentThreadSnippet? = null
)

data class CommentThreadSnippet(
    @SerializedName("topLevelComment") val topLevelComment: TopLevelComment? = null
)

data class TopLevelComment(
    @SerializedName("snippet") val snippet: CommentSnippet? = null
)

data class CommentSnippet(
    @SerializedName("authorDisplayName") val authorDisplayName: String = "",
    @SerializedName("textDisplay") val textDisplay: String = "",
    @SerializedName("likeCount") val likeCount: Int = 0,
    @SerializedName("publishedAt") val publishedAt: String = ""
)
