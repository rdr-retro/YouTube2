package com.rdr.youtube2

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ChannelDetailActivity : AppCompatActivity() {

    private enum class SortMode { RECENT, OLDEST }

    private lateinit var screenTitle: TextView
    private lateinit var headerLogoContainer: View
    private lateinit var headerLogo: ImageView
    private lateinit var channelAvatar: ImageView
    private lateinit var channelNameText: TextView
    private lateinit var channelSubscribersText: TextView
    private lateinit var channelUploadCountText: TextView
    private lateinit var subscribeButton: Button
    private lateinit var recentSortButton: Button
    private lateinit var oldestSortButton: Button
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var listLayoutManager: LinearLayoutManager

    private val videos = mutableListOf<FeedVideo>()
    private val recentVideos = mutableListOf<FeedVideo>()
    private val oldestVideos = mutableListOf<FeedVideo>()
    private lateinit var adapter: ChannelVideoAdapter

    private var channelName: String = ""
    private var channelId: String = ""
    private var channelIconUrl: String = ""
    private var subscriberLabel: String = ""
    private var currentSortMode: SortMode = SortMode.RECENT
    private var isLoadingRecentVideos: Boolean = false
    private var isLoadingOldestVideos: Boolean = false
    private var recentRequestedLimit: Int = 0
    private var oldestRequestedLimit: Int = 0
    private var recentReachedEnd: Boolean = false
    private var oldestReachedEnd: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_detail)
        initViews()
    }

    private fun initViews() {
        try {
            screenTitle = findViewById(R.id.channel_screen_title)
            headerLogoContainer = findViewById(R.id.channel_header_logo_container)
            headerLogo = findViewById(R.id.channel_header_logo)
            channelAvatar = findViewById(R.id.channel_avatar)
            channelNameText = findViewById(R.id.channel_name)
            channelSubscribersText = findViewById(R.id.channel_subscribers)
            channelUploadCountText = findViewById(R.id.channel_upload_count)
            subscribeButton = findViewById(R.id.channel_subscribe_button)
            recentSortButton = findViewById(R.id.channel_sort_recent_button)
            oldestSortButton = findViewById(R.id.channel_sort_oldest_button)
            emptyText = findViewById(R.id.channel_empty_text)
            recyclerView = findViewById(R.id.channel_video_list)
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = "Error inicializando vistas: ${e.javaClass.simpleName} - ${e.message}"
            android.util.Log.e("ChannelDetail", msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty().trim()
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty().trim()
        channelIconUrl = intent.getStringExtra(EXTRA_CHANNEL_ICON_URL).orEmpty().trim()
        if (channelName.isBlank()) {
            channelName = "Canal desconocido"
        }

        adapter = ChannelVideoAdapter(videos) { item ->
            openVideoDetail(item)
        }
        setupRecyclerView()

        subscribeButton.setOnClickListener { toggleSubscription() }
        recentSortButton.setOnClickListener { applySort(SortMode.RECENT) }
        oldestSortButton.setOnClickListener { applySort(SortMode.OLDEST) }
        headerLogoContainer.setOnClickListener { openHome() }
        headerLogo.setOnClickListener { openHome() }
        findViewById<TextView>(R.id.channel_header_logo_suffix).setOnClickListener {
            startActivity(Intent(this, TwoActivity::class.java))
        }

        try {
            renderHeader()
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = "Error renderHeader: ${e.javaClass.simpleName} - ${e.message}"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        updateSortButtons()
        updateSubscribeButton()
        loadChannelData()
    }

    private fun loadChannelData() {
        emptyText.visibility = View.VISIBLE
        emptyText.text = "Cargando videos..."

        lifecycleScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    PublicYouTubeFeedClient.resolveChannelSummary(
                        channelId = channelId,
                        channelName = channelName
                    )
                }
                if (resolved != null) {
                    if (channelId.isBlank()) channelId = resolved.channelId
                    if (channelName.isBlank() || channelName == "Canal desconocido") {
                        channelName = resolved.channelName.ifBlank { channelName }
                    }
                    if (resolved.channelIconUrl.isNotBlank()) channelIconUrl = resolved.channelIconUrl
                    if (resolved.subscriberLabel.isNotBlank()) subscriberLabel = resolved.subscriberLabel
                }
                renderHeader()
                resetPaginationState()
                applySort(currentSortMode)
                updateUploadCount()

                if (ChannelSubscriptionsStore.isSubscribed(this@ChannelDetailActivity, channelId, channelName)) {
                    ChannelSubscriptionsStore.subscribe(this@ChannelDetailActivity, currentSubscription())
                    updateSubscribeButton()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ChannelDetailActivity, "Error cargando canal", Toast.LENGTH_SHORT).show()
                emptyText.text = "Error al conectar."
            }
        }
    }

    private fun setupRecyclerView() {
        listLayoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = listLayoutManager
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null
        recyclerView.setItemViewCacheSize(4)
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 24)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return
                maybeLoadMore()
            }
        })
    }

    private fun resetPaginationState() {
        isLoadingRecentVideos = false
        isLoadingOldestVideos = false
        recentRequestedLimit = 0
        oldestRequestedLimit = 0
        recentReachedEnd = false
        oldestReachedEnd = false
        videos.clear()
        recentVideos.clear()
        oldestVideos.clear()
        adapter.notifyDataSetChanged()
    }

    private fun renderHeader() {
        screenTitle.text = channelName
        channelNameText.text = channelName
        channelSubscribersText.text = if (subscriberLabel.isBlank()) {
            "Subscribers --"
        } else {
            subscriberLabel
        }
        Glide.with(this)
            .load(channelIconUrl)
            .placeholder(R.drawable.icon)
            .into(channelAvatar)
    }

    private fun toggleSubscription() {
        val subscribed = ChannelSubscriptionsStore.isSubscribed(this, channelId, channelName)
        if (subscribed) {
            ChannelSubscriptionsStore.unsubscribe(this, channelId, channelName)
            Toast.makeText(this, "Canal eliminado de suscripciones", Toast.LENGTH_SHORT).show()
        } else {
            ChannelSubscriptionsStore.subscribe(this, currentSubscription())
            Toast.makeText(this, "Canal suscrito localmente", Toast.LENGTH_SHORT).show()
        }
        updateSubscribeButton()
    }

    private fun applySort(mode: SortMode) {
        currentSortMode = mode
        updateSortButtons()
        when (mode) {
            SortMode.RECENT -> {
                if (recentVideos.isNotEmpty()) {
                    renderVisibleVideos(recentVideos)
                } else {
                    loadRecentVideos(forceRefresh = true)
                }
            }
            SortMode.OLDEST -> {
                if (oldestVideos.isNotEmpty()) {
                    renderVisibleVideos(oldestVideos)
                } else {
                    loadOldestVideos(forceRefresh = true)
                }
            }
        }
    }

    private fun updateSortButtons() {
        if (!::recentSortButton.isInitialized || !::oldestSortButton.isInitialized) return
        recentSortButton.alpha = if (currentSortMode == SortMode.RECENT) 1f else 0.72f
        oldestSortButton.alpha = if (currentSortMode == SortMode.OLDEST) 1f else 0.72f
    }

    private fun openHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun loadRecentVideos(forceRefresh: Boolean = false) {
        if (isLoadingRecentVideos) return
        if (!forceRefresh && recentReachedEnd) return

        val nextLimit = if (forceRefresh || recentRequestedLimit <= 0) {
            PAGE_SIZE
        } else {
            increaseLimit(recentRequestedLimit)
        }
        if (!forceRefresh && nextLimit <= recentRequestedLimit) {
            recentReachedEnd = true
            return
        }

        isLoadingRecentVideos = true
        if (videos.isEmpty()) {
            emptyText.text = "Cargando videos..."
            emptyText.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val loadedVideos = withContext(Dispatchers.IO) {
                    PublicYouTubeFeedClient.loadChannelVideos(
                        channelId = channelId,
                        channelName = channelName,
                        limit = nextLimit
                    )
                }
                val sorted = loadedVideos.sortedByDescending { parseIsoToMillis(it.publishedAt) ?: Long.MIN_VALUE }
                val canAppend = !forceRefresh &&
                    currentSortMode == SortMode.RECENT &&
                    isSamePrefix(videos, sorted)

                recentVideos.clear()
                recentVideos.addAll(sorted)
                recentRequestedLimit = nextLimit
                recentReachedEnd = sorted.size < nextLimit
                isLoadingRecentVideos = false
                updateUploadCount()

                if (currentSortMode == SortMode.RECENT) {
                    if (canAppend) {
                        appendVisibleVideos(sorted)
                    } else {
                        renderVisibleVideos(sorted)
                    }
                }
            } catch (e: Exception) {
                isLoadingRecentVideos = false
                emptyText.text = "Error cargando videos."
                emptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun loadOldestVideos(forceRefresh: Boolean = false) {
        if (isLoadingOldestVideos) return
        if (!forceRefresh && oldestReachedEnd) return

        val nextLimit = if (forceRefresh || oldestRequestedLimit <= 0) {
            PAGE_SIZE
        } else {
            increaseLimit(oldestRequestedLimit)
        }
        if (!forceRefresh && nextLimit <= oldestRequestedLimit) {
            oldestReachedEnd = true
            return
        }

        isLoadingOldestVideos = true
        if (videos.isEmpty()) {
            emptyText.text = "Cargando videos más antiguos..."
            emptyText.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    PublicYouTubeFeedClient.loadChannelVideosOldest(
                        channelId = channelId,
                        channelName = channelName,
                        limit = nextLimit
                    )
                }
                val sorted = if (loaded.isNotEmpty()) {
                    loaded.sortedBy { parseIsoToMillis(it.publishedAt) ?: Long.MAX_VALUE }
                } else {
                    recentVideos.sortedBy { parseIsoToMillis(it.publishedAt) ?: Long.MAX_VALUE }
                }
                val canAppend = !forceRefresh &&
                    currentSortMode == SortMode.OLDEST &&
                    isSamePrefix(videos, sorted)

                oldestVideos.clear()
                oldestVideos.addAll(sorted)
                oldestRequestedLimit = nextLimit
                oldestReachedEnd = sorted.size < nextLimit
                isLoadingOldestVideos = false
                updateUploadCount()

                if (currentSortMode == SortMode.OLDEST) {
                    if (canAppend) {
                        appendVisibleVideos(sorted)
                    } else {
                        renderVisibleVideos(sorted)
                    }
                }
            } catch (e: Exception) {
                isLoadingOldestVideos = false
                emptyText.text = "Error cargando videos antiguos."
                emptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun maybeLoadMore() {
        val lastVisible = listLayoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return
        if (videos.isEmpty()) return
        if (lastVisible < videos.size - PREFETCH_THRESHOLD) return

        when (currentSortMode) {
            SortMode.RECENT -> loadRecentVideos(forceRefresh = false)
            SortMode.OLDEST -> loadOldestVideos(forceRefresh = false)
        }
    }

    private fun renderVisibleVideos(source: List<FeedVideo>) {
        videos.clear()
        videos.addAll(source)
        adapter.notifyDataSetChanged()
        if (videos.isEmpty()) {
            emptyText.text = "Este canal no tiene videos visibles."
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }
    }

    private fun appendVisibleVideos(source: List<FeedVideo>) {
        if (videos.size >= source.size) {
            renderVisibleVideos(source)
            return
        }
        val start = videos.size
        videos.addAll(source.subList(start, source.size))
        adapter.notifyItemRangeInserted(start, source.size - start)
        if (videos.isEmpty()) {
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }
    }

    private fun isSamePrefix(base: List<FeedVideo>, updated: List<FeedVideo>): Boolean {
        if (base.isEmpty()) return false
        if (updated.size < base.size) return false
        for (index in base.indices) {
            if (base[index].videoId != updated[index].videoId) {
                return false
            }
        }
        return true
    }

    private fun increaseLimit(current: Int): Int {
        if (current <= 0) return PAGE_SIZE
        if (current >= Int.MAX_VALUE - PAGE_SIZE) return Int.MAX_VALUE
        return current + PAGE_SIZE
    }

    private fun updateUploadCount() {
        val loaded = maxOf(recentVideos.size, oldestVideos.size)
        val hasMore = when (currentSortMode) {
            SortMode.RECENT -> !recentReachedEnd
            SortMode.OLDEST -> !oldestReachedEnd
        }
        val suffix = if (loaded > 0 && hasMore) "+" else ""
        channelUploadCountText.text = "Uploads $loaded$suffix"
    }

    private fun updateSubscribeButton() {
        val subscribed = ChannelSubscriptionsStore.isSubscribed(this, channelId, channelName)
        subscribeButton.text = if (subscribed) {
            "Suscrito"
        } else {
            "Suscribirse"
        }
        subscribeButton.alpha = if (subscribed) 0.9f else 1f
    }

    private fun currentSubscription(): LocalChannelSubscription {
        return LocalChannelSubscription(
            channelId = channelId,
            channelName = channelName,
            channelIconUrl = channelIconUrl
        )
    }

    private fun openVideoDetail(item: FeedVideo) {
        val intent = Intent(this, VideoDetailActivity::class.java).apply {
            putExtra(VideoDetailActivity.EXTRA_VIDEO_ID, item.videoId)
            putExtra(VideoDetailActivity.EXTRA_TITLE, item.title)
            putExtra(VideoDetailActivity.EXTRA_CHANNEL, item.channel)
            putExtra(VideoDetailActivity.EXTRA_SECTION, "Uploads")
            putExtra(VideoDetailActivity.EXTRA_THUMBNAIL, item.thumbnailUrl)
        }
        startActivity(intent)
    }

    private fun formatRelativeDate(publishedAt: String): String {
        if (publishedAt.isBlank()) return "hace poco"
        val publishedMillis = parseIsoToMillis(publishedAt) ?: return "hace poco"
        val diff = (System.currentTimeMillis() - publishedMillis).coerceAtLeast(0L)
        val dayMs = 24L * 60L * 60L * 1000L
        val days = diff / dayMs
        return when {
            days < 1L -> "hoy"
            days == 1L -> "hace 1 día"
            days < 7L -> "hace $days días"
            days < 30L -> {
                val weeks = (days / 7L).coerceAtLeast(1L)
                if (weeks == 1L) "hace 1 semana" else "hace $weeks semanas"
            }
            days < 365L -> {
                val months = (days / 30L).coerceAtLeast(1L)
                if (months == 1L) "hace 1 mes" else "hace $months meses"
            }
            else -> {
                val years = (days / 365L).coerceAtLeast(1L)
                if (years == 1L) "hace 1 año" else "hace $years años"
            }
        }
    }

    private fun parseIsoToMillis(iso: String): Long? {
        val normalized = normalizeIsoOffset(iso)
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

    private fun normalizeIsoOffset(raw: String): String {
        var text = raw.trim()
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

    private inner class ChannelVideoAdapter(
        private val items: List<FeedVideo>,
        private val onClick: (FeedVideo) -> Unit
    ) : RecyclerView.Adapter<ChannelVideoAdapter.ChannelVideoViewHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelVideoViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel_video, parent, false)
            return ChannelVideoViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChannelVideoViewHolder, position: Int) {
            holder.bind(items[position], onClick)
        }

        override fun getItemId(position: Int): Long {
            return items[position].videoId.hashCode().toLong()
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: ChannelVideoViewHolder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        override fun onViewDetachedFromWindow(holder: ChannelVideoViewHolder) {
            holder.detach()
            super.onViewDetachedFromWindow(holder)
        }

        inner class ChannelVideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val thumbnail: ImageView = view.findViewById(R.id.channel_item_thumbnail)
            private val duration: TextView = view.findViewById(R.id.channel_item_duration)
            private val title: TextView = view.findViewById(R.id.channel_item_title)
            private val channel: TextView = view.findViewById(R.id.channel_item_channel)
            private val meta: TextView = view.findViewById(R.id.channel_item_meta)

            fun bind(item: FeedVideo, onClick: (FeedVideo) -> Unit) {
                Glide.with(itemView.context).clear(thumbnail)
                Glide.with(itemView.context)
                    .load(item.thumbnailUrl)
                    .placeholder(R.drawable.vid)
                    .into(thumbnail)
                duration.text = item.duration.ifBlank { "--:--" }
                title.text = item.title
                channel.text = "by ${item.channel}"
                val relative = formatRelativeDate(item.publishedAt)
                val viewsText = item.viewCount.ifBlank { "0 views" }
                meta.text = "$relative | $viewsText"
                itemView.setOnClickListener { onClick(item) }
            }

            fun recycle() {
                itemView.setOnClickListener(null)
                Glide.with(itemView.context).clear(thumbnail)
                thumbnail.setImageDrawable(null)
            }

            fun detach() {
                Glide.with(itemView.context).clear(thumbnail)
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_CHANNEL_ICON_URL = "extra_channel_icon_url"
        private const val PAGE_SIZE = 24
        private const val PREFETCH_THRESHOLD = 8
    }
}
