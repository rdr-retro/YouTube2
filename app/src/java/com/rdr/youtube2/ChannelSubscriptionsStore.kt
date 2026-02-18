package com.rdr.youtube2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class LocalChannelSubscription(
    val channelId: String,
    val channelName: String,
    val channelIconUrl: String = ""
)

object ChannelSubscriptionsStore {

    private const val PREFS_NAME = "channel_subscriptions_store"
    private const val KEY_SUBSCRIPTIONS_JSON = "subscriptions_json"

    fun list(context: Context): List<LocalChannelSubscription> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SUBSCRIPTIONS_JSON, "[]").orEmpty()
        val parsed = mutableListOf<LocalChannelSubscription>()
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val channelId = item.optString("channelId").trim()
                val channelName = item.optString("channelName").trim()
                val channelIconUrl = item.optString("channelIconUrl").trim()
                if (channelName.isBlank()) continue
                parsed.add(
                    LocalChannelSubscription(
                        channelId = channelId,
                        channelName = channelName,
                        channelIconUrl = channelIconUrl
                    )
                )
            }
        } catch (_: Throwable) {
            // Ignore malformed data and return empty list.
        }
        return parsed.sortedBy { it.channelName.lowercase(Locale.ROOT) }
    }

    fun isSubscribed(context: Context, channelId: String, channelName: String): Boolean {
        val targetId = normalizeKey(channelId, channelName)
        if (targetId.isBlank()) return false
        return list(context).any { normalizeKey(it.channelId, it.channelName) == targetId }
    }

    fun subscribe(context: Context, subscription: LocalChannelSubscription) {
        val existing = list(context).toMutableList()
        val targetKey = normalizeKey(subscription.channelId, subscription.channelName)
        if (targetKey.isBlank()) return

        val withoutTarget = existing.filterNot {
            normalizeKey(it.channelId, it.channelName) == targetKey
        }.toMutableList()
        withoutTarget.add(subscription)
        persist(context, withoutTarget)
    }

    fun unsubscribe(context: Context, channelId: String, channelName: String) {
        val targetKey = normalizeKey(channelId, channelName)
        if (targetKey.isBlank()) return
        val filtered = list(context).filterNot {
            normalizeKey(it.channelId, it.channelName) == targetKey
        }
        persist(context, filtered)
    }

    private fun persist(context: Context, subscriptions: List<LocalChannelSubscription>) {
        val array = JSONArray()
        subscriptions.forEach { item ->
            if (item.channelName.isBlank()) return@forEach
            val obj = JSONObject()
            obj.put("channelId", item.channelId.trim())
            obj.put("channelName", item.channelName.trim())
            obj.put("channelIconUrl", item.channelIconUrl.trim())
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SUBSCRIPTIONS_JSON, array.toString()).apply()
    }

    private fun normalizeKey(channelId: String, channelName: String): String {
        val id = channelId.trim()
        if (id.isNotBlank()) return "id:$id"
        val name = channelName.trim().lowercase(Locale.ROOT)
        if (name.isBlank()) return ""
        return "name:$name"
    }
}
