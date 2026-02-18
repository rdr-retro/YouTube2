package com.rdr.youtube2

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.rdr.youtube2.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private data class AdvancedSearchOptions(
        val query: String,
        val minimumAgeYears: Int? = null,
        val longVideosOnly: Boolean = false,
        val minimumViews: Long? = null,
        val sortOldestFirst: Boolean = false
    )

    private lateinit var binding: ActivityMainBinding
    private val videoList = mutableListOf<VideoItem>()
    private lateinit var adapter: OldYoutubeAdapter
    private var publicFeedCache: List<VideoItem> = emptyList()
    private var onlineVideoPool: List<VideoItem> = emptyList()
    private var currentVisibleVideoIds: Set<String> = emptySet()
    private var previousSessionVideoIds: Set<String> = emptySet()
    private var isLoadingVideos: Boolean = false
    private var isLoadingMore: Boolean = false
    private var feedPage: Int = 0
    private var hasMorePages: Boolean = true
    // Pool of pre-fetched videos waiting to be shown progressively
    private val pendingPool = mutableListOf<VideoItem>()
    private var searchJob: Job? = null
    private var activeSearchQuery: String = ""
    private var hideShortsEnabled: Boolean = false
    private var subscriptionsFeedOnlyEnabled: Boolean = false
    private var subscriptionsFingerprint: String = ""
    private var lastUnfilteredVideoItems: List<VideoItem> = emptyList()
    private val durationCache = ConcurrentHashMap<String, String>()
    private val prefs by lazy(LazyThreadSafetyMode.NONE) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerView()
        setupPullToRefresh()
        previousSessionVideoIds = loadPreviousSessionVideoIds()
        restoreDurationCache()
        hideShortsEnabled = prefs.getBoolean(KEY_HIDE_SHORTS, false)
        subscriptionsFeedOnlyEnabled = prefs.getBoolean(KEY_SUBSCRIPTIONS_FEED_ONLY, false)
        subscriptionsFingerprint = buildSubscriptionsFingerprint()
        updateHeaderSearchQuery("")

        binding.headerSearch.setOnClickListener { showSearchDialog() }
        binding.headerEdit.setOnClickListener { showAdvancedSearchDialog() }
        val openHomeFromHeader: () -> Unit = {
            if (activeSearchQuery.isNotBlank()) {
                loadPopularVideos()
            }
            binding.chatRecyclerView.scrollToPosition(0)
        }
        findViewById<View>(R.id.header_logo_container).setOnClickListener {
            openHomeFromHeader()
        }
        binding.headerTitleLogo.setOnClickListener {
            openHomeFromHeader()
        }
        findViewById<TextView>(R.id.header_title_logo_suffix).setOnClickListener {
            startActivity(Intent(this, TwoActivity::class.java))
        }

        if (savedInstanceState == null && !hasSeenWelcomeDialog()) {
            showWelcomeDialog()
        }

        loadPopularVideos()
    }

    override fun onResume() {
        super.onResume()
        val latestHideShorts = prefs.getBoolean(KEY_HIDE_SHORTS, false)
        val latestSubscriptionsFeedOnly = prefs.getBoolean(KEY_SUBSCRIPTIONS_FEED_ONLY, false)
        val latestFingerprint = buildSubscriptionsFingerprint()

        val hideShortsChanged = latestHideShorts != hideShortsEnabled
        val subscriptionsFeedChanged = latestSubscriptionsFeedOnly != subscriptionsFeedOnlyEnabled
        val subscriptionsChanged = latestFingerprint != subscriptionsFingerprint

        if (hideShortsChanged) {
            hideShortsEnabled = latestHideShorts
        }
        if (subscriptionsFeedChanged) {
            subscriptionsFeedOnlyEnabled = latestSubscriptionsFeedOnly
        }
        if (subscriptionsChanged) {
            subscriptionsFingerprint = latestFingerprint
        }

        if (subscriptionsFeedChanged || (subscriptionsFeedOnlyEnabled && subscriptionsChanged)) {
            loadPopularVideos()
            return
        }
        if (hideShortsChanged) {
            updateVideoList(lastUnfilteredVideoItems)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_legacy_options, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (handleLegacyMenuAction(item.itemId)) {
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showBottomMenu()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleLegacyMenuAction(itemId: Int): Boolean {
        return when (itemId) {
            R.id.menu_settings -> {
                openSettingsScreen()
                true
            }
            R.id.menu_subscriptions -> {
                openSubscriptionsScreen()
                true
            }
            R.id.menu_search -> {
                showSearchDialog()
                true
            }
            else -> false
        }
    }

    private fun showWelcomeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_welcome, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialogView.findViewById<Button>(R.id.welcome_accept_button).setOnClickListener {
            markWelcomeDialogAsSeen()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun hasSeenWelcomeDialog(): Boolean {
        return prefs.getBoolean(KEY_WELCOME_SHOWN, false)
    }

    private fun markWelcomeDialogAsSeen() {
        prefs.edit {
            putBoolean(KEY_WELCOME_SHOWN, true)
        }
    }

    private fun setupRecyclerView() {
        adapter = OldYoutubeAdapter(videoList) { video ->
            openVideoDetail(video)
        }
        val layoutManager = LinearLayoutManager(this)
        binding.chatRecyclerView.layoutManager = layoutManager
        binding.chatRecyclerView.adapter = adapter

        // ── RecyclerView performance optimizations ──
        // Fixed size: layout doesn't change when items are added
        binding.chatRecyclerView.setHasFixedSize(true)
        // Keep more off-screen views cached for smoother scroll
        binding.chatRecyclerView.setItemViewCacheSize(8)
        // Disable change animations for instant updates
        binding.chatRecyclerView.itemAnimator = null

        // Infinite scroll listener
        binding.chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return // only trigger on scroll down
                if (isLoadingMore) return
                if (activeSearchQuery.isNotBlank()) return // don't paginate search results

                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (lastVisibleItem >= totalItemCount - INFINITE_SCROLL_THRESHOLD) {
                    loadMoreVideos()
                }
            }
        })
    }

    private fun setupPullToRefresh() {
        binding.feedSwipeRefresh.setColorSchemeResources(android.R.color.holo_red_light)
        binding.feedSwipeRefresh.setOnRefreshListener {
            loadPopularVideos(fromSwipe = true)
        }
    }

    private fun loadPopularVideos(fromSwipe: Boolean = false) {
        searchJob?.cancel()
        activeSearchQuery = ""
        updateHeaderSearchQuery("")

        if (isLoadingVideos) {
            binding.feedSwipeRefresh.isRefreshing = false
            return
        }
        if (!fromSwipe && videoList.isEmpty()) {
            showLoadingPlaceholders()
        }
        isLoadingVideos = true
        feedPage = 0
        hasMorePages = true
        binding.feedSwipeRefresh.isRefreshing = fromSwipe

        lifecycleScope.launch {
            try {
                val subscriptions = if (subscriptionsFeedOnlyEnabled) {
                    ChannelSubscriptionsStore.list(this@MainActivity)
                } else {
                    emptyList()
                }

                val mappedItems = if (subscriptionsFeedOnlyEnabled) {
                    if (subscriptions.isEmpty()) {
                        emptyList()
                    } else {
                        loadFeedFromSubscriptions(subscriptions, limit = 56)
                    }
                } else {
                    val feedItems = PublicYouTubeFeedClient.loadRecentVideos(limit = 56)
                    feedItems.map { item ->
                        VideoItem(
                            videoId = item.videoId,
                            title = item.title,
                            channel = item.channel,
                            thumbnailUrl = item.thumbnailUrl,
                            duration = item.duration.ifBlank { durationCache[item.videoId].orEmpty() },
                            viewCount = item.viewCount,
                            section = item.section,
                            description = item.description,
                            publishedAt = item.publishedAt
                        )
                    }.filter { it.title.isNotBlank() }
                }

                if (mappedItems.isEmpty()) {
                    if (subscriptionsFeedOnlyEnabled) {
                        publicFeedCache = emptyList()
                        onlineVideoPool = emptyList()
                        updateVideoList(emptyList())
                        hasMorePages = false
                        val message = if (subscriptions.isEmpty()) {
                            "No tienes suscripciones. Suscríbete a canales para usar este filtro."
                        } else {
                            "No hay videos recientes de tus canales suscritos."
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    } else {
                        loadDemoVideos()
                        hasMorePages = false
                        if (!fromSwipe) {
                            Toast.makeText(
                                this@MainActivity,
                                "No se pudieron cargar videos online. Modo demo.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    publicFeedCache = mappedItems
                    onlineVideoPool = mappedItems
                    // Shuffle items to ensure random feed
                    val shuffledItems = mappedItems.shuffled()
                    
                    // Show a small initial batch; keep rest in pool
                    val initialBatch = shuffledItems.take(INITIAL_BATCH_SIZE)
                    pendingPool.clear()
                    pendingPool.addAll(shuffledItems.drop(INITIAL_BATCH_SIZE))
                    val hydrated = withResolvedDurations(initialBatch)
                    mergeDurationsIntoCaches(hydrated)
                    updateVideoList(hydrated)
                }
            } catch (e: Throwable) {
                if (subscriptionsFeedOnlyEnabled) {
                    publicFeedCache = emptyList()
                    onlineVideoPool = emptyList()
                    updateVideoList(emptyList())
                    hasMorePages = false
                    Toast.makeText(
                        this@MainActivity,
                        "Sin conexion estable. No se pudo cargar feed por suscripciones.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    loadDemoVideos()
                    hasMorePages = false
                    if (!fromSwipe) {
                        Toast.makeText(
                            this@MainActivity,
                            "Sin conexion estable. Mostrando videos demo.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                isLoadingVideos = false
                binding.feedSwipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadMoreVideos() {
        if (isLoadingMore) return
        if (activeSearchQuery.isNotBlank()) return
        isLoadingMore = true

        // ── Phase 1: drain the local pending pool first (instant, no network) ──
        if (pendingPool.isNotEmpty()) {
            val batch = pendingPool.take(LOAD_MORE_BATCH_SIZE).toList()
            pendingPool.subList(0, batch.size).clear()
            lifecycleScope.launch {
                val hydrated = withResolvedDurations(batch)
                mergeDurationsIntoCaches(hydrated)
                appendVideos(hydrated)
                isLoadingMore = false
            }
            return
        }

        // ── Phase 2: fetch from network (extra channels) ──
        feedPage++

        // Add a loading indicator at the bottom
        val loadingItem = VideoItem(
            videoId = "loading_more",
            title = "Cargando más videos...",
            channel = "",
            thumbnailUrl = "",
            duration = "--:--",
            viewCount = "",
            section = "",
            description = "Espera un momento",
            publishedAt = "",
            isPlaceholder = true
        )
        videoList.add(loadingItem)
        adapter.notifyItemInserted(videoList.size - 1)

        lifecycleScope.launch {
            try {
                val existingIds = videoList
                    .filterNot { it.isPlaceholder }
                    .mapNotNull { it.videoId.takeIf(String::isNotBlank) }
                    .toSet()

                // First try RSS channels
                var feedItems = if (subscriptionsFeedOnlyEnabled) {
                    val subscriptions = ChannelSubscriptionsStore.list(this@MainActivity)
                    if (subscriptions.isNotEmpty()) {
                        loadFeedFromSubscriptions(subscriptions, limit = 56 + feedPage * 20)
                            .filterNot { it.videoId in existingIds }
                    } else {
                        emptyList()
                    }
                } else {
                    val rssItems = PublicYouTubeFeedClient.loadMoreRecentVideos(
                        page = feedPage,
                        limit = 30,
                        excludeIds = existingIds
                    )
                    rssItems.map { item ->
                        VideoItem(
                            videoId = item.videoId,
                            title = item.title,
                            channel = item.channel,
                            thumbnailUrl = item.thumbnailUrl,
                            duration = item.duration.ifBlank { durationCache[item.videoId].orEmpty() },
                            viewCount = item.viewCount,
                            section = item.section,
                            description = item.description,
                            publishedAt = item.publishedAt
                        )
                    }.filter { it.title.isNotBlank() }
                }

                // If RSS is exhausted, fall back to search-based discovery
                if (feedItems.isEmpty() && !subscriptionsFeedOnlyEnabled) {
                    val discovered = PublicYouTubeFeedClient.discoverVideos(
                        limit = 20,
                        excludeIds = existingIds
                    )
                    feedItems = discovered.map { item ->
                        VideoItem(
                            videoId = item.videoId,
                            title = item.title,
                            channel = item.channel,
                            thumbnailUrl = item.thumbnailUrl,
                            duration = item.duration.ifBlank { durationCache[item.videoId].orEmpty() },
                            viewCount = item.viewCount,
                            section = item.section,
                            description = item.description,
                            publishedAt = item.publishedAt
                        )
                    }.filter { it.title.isNotBlank() }
                }

                // Remove loading indicator
                removeLoadingIndicator()

                if (feedItems.isNotEmpty()) {
                    // Shuffle items to ensure random feed
                    val shuffledFeed = feedItems.shuffled()
                    
                    // Show first batch now, keep rest in pool
                    val showNow = shuffledFeed.take(LOAD_MORE_BATCH_SIZE)
                    val saveLater = shuffledFeed.drop(LOAD_MORE_BATCH_SIZE)
                    pendingPool.addAll(saveLater)
                    val hydrated = withResolvedDurations(showNow)
                    mergeDurationsIntoCaches(hydrated)
                    appendVideos(hydrated)
                }
                // Feed never stops: discovery queries rotate infinitely
            } catch (_: Throwable) {
                removeLoadingIndicator()
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun removeLoadingIndicator() {
        val loadingIndex = videoList.indexOfLast { it.videoId == "loading_more" }
        if (loadingIndex >= 0) {
            videoList.removeAt(loadingIndex)
            adapter.notifyItemRemoved(loadingIndex)
        }
    }

    private fun appendVideos(newItems: List<VideoItem>) {
        val filtered = if (hideShortsEnabled) {
            newItems.filterNot(::isShortVideoItem)
        } else {
            newItems
        }
        if (filtered.isEmpty()) return
        lastUnfilteredVideoItems = lastUnfilteredVideoItems + newItems
        val insertStart = videoList.size
        videoList.addAll(filtered)
        adapter.notifyItemRangeInserted(insertStart, filtered.size)
        updateCurrentVisibleIds(videoList)
    }

    private fun searchVideos(query: String) {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) return
        rememberSearchQuery(rawQuery)
        runSearch(rawQuery, options = null)
    }

    private fun loadSearchHistory(): MutableList<String> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return raw
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_SEARCH_HISTORY_ITEMS)
            .toMutableList()
    }

    private fun saveSearchHistory(history: List<String>) {
        val serialized = history
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_SEARCH_HISTORY_ITEMS)
            .joinToString("\n")
        prefs.edit {
            putString(KEY_SEARCH_HISTORY, serialized)
        }
    }

    private fun rememberSearchQuery(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        val history = loadSearchHistory()
        history.removeAll { it.equals(query, ignoreCase = true) }
        history.add(0, query)
        saveSearchHistory(history)
    }

    private fun searchVideosAdvanced(options: AdvancedSearchOptions) {
        // Construct API query from options
        val baseQuery = options.query.trim()
        val queryParts = mutableListOf<String>()
        
        if (baseQuery.isNotEmpty()) {
            queryParts.add(baseQuery)
        }
        
        // Add date filter if minimum age is specified
        if (options.minimumAgeYears != null) {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.YEAR, -options.minimumAgeYears)
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            // Format YYYY-MM-DD
            val dateStr = String.format("%d-%02d-%02d", year, month, day)
            queryParts.add("before:$dateStr")
        }
        
        // Add duration filter
        if (options.longVideosOnly) {
            queryParts.add("duration:long") // Works in YouTube search
        }
        
        val apiQuery = queryParts.joinToString(" ")
        
        if (apiQuery.isBlank()) return
        
        // Pass original options for post-filtering if needed, but apiQuery is used for fetching
        runSearch(apiQuery, options)
    }

    private fun runSearch(rawQuery: String, options: AdvancedSearchOptions?) {
        searchJob?.cancel()
        activeSearchQuery = rawQuery
        updateHeaderSearchQuery(buildSearchHeaderLabel(rawQuery, options))
        showSearchingPlaceholders(rawQuery)

        val normalizedQuery = normalizeSearchText(rawQuery)
        val localBase = if (publicFeedCache.isNotEmpty()) publicFeedCache else onlineVideoPool

        searchJob = lifecycleScope.launch {
            val localMatches = localBase.filter { matchesSearchQuery(it, normalizedQuery) }

            val remoteLimit = if (options == null) 36 else 56
            val remoteMatches = PublicYouTubeFeedClient.searchVideos(rawQuery, limit = remoteLimit)
                .map { mapFeedItemToVideoItem(it) }

            if (activeSearchQuery != rawQuery) return@launch

            var merged = mergeSearchResults(localMatches, remoteMatches)
            merged = applyAdvancedFilters(merged, options)
            if (options?.sortOldestFirst == true) {
                merged = merged.sortedBy { parseIsoToMillisSafe(it.publishedAt) ?: Long.MAX_VALUE }
            }

            val visible = merged.take(if (options == null) 28 else 40)
            if (visible.isEmpty()) {
                if (publicFeedCache.isEmpty() && onlineVideoPool.isEmpty()) {
                    searchDemoVideos(rawQuery)
                }
                Toast.makeText(
                    this@MainActivity,
                    "No hay resultados para \"$rawQuery\"",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val hydrated = withResolvedDurations(visible)
            if (activeSearchQuery != rawQuery) return@launch
            mergeDurationsIntoCaches(hydrated)
            
            // Append search results to the end of the feed
            val headerTitle = buildSearchHeaderLabel(rawQuery, options)
            val header = VideoItem(
                videoId = "search_header_${System.currentTimeMillis()}",
                title = "Resultados de \"$headerTitle\"",
                channel = "",
                thumbnailUrl = "",
                duration = "",
                viewCount = "",
                section = "",
                isSearchHeader = true
            )
            
            updateVideoList(listOf(header) + hydrated)
        }
    }

    private fun updateHeaderSearchQuery(query: String) {
        // No longer using fixed header
    }

    private fun exitSearchMode() {
        searchJob?.cancel()
        activeSearchQuery = ""
        loadPopularVideos()
    }

    private fun buildSearchHeaderLabel(query: String, options: AdvancedSearchOptions?): String {
        if (options == null) return query
        val tags = mutableListOf<String>()
        options.minimumAgeYears?.let { tags.add("+$it años") }
        if (options.longVideosOnly) tags.add("largos")
        options.minimumViews?.let { tags.add("${it / 1000}K+ vistas") }
        if (options.sortOldestFirst) tags.add("antiguos")
        if (tags.isEmpty()) return query
        return "$query · ${tags.joinToString(" · ")}"
    }

    private fun applyAdvancedFilters(
        items: List<VideoItem>,
        options: AdvancedSearchOptions?
    ): List<VideoItem> {
        if (options == null) return items

        var filtered = items

        options.minimumAgeYears?.let { years ->
            val cutoff = System.currentTimeMillis() - (years * 365.25 * DAY_MS).toLong()
            filtered = filtered.filter { item ->
                val publishedMillis = parseIsoToMillisSafe(item.publishedAt) ?: return@filter false
                publishedMillis <= cutoff
            }
        }

        if (options.longVideosOnly) {
            filtered = filtered.filter { parseDurationToSeconds(it.duration) >= LONG_VIDEO_SECONDS }
        }

        options.minimumViews?.let { minViews ->
            filtered = filtered.filter { parseViewsToLong(it.viewCount) >= minViews }
        }

        return filtered
    }

    private fun parseDurationToSeconds(raw: String): Long {
        val clean = raw.trim()
        if (clean.isBlank()) return 0L
        val parts = clean.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun parseViewsToLong(raw: String): Long {
        if (raw.isBlank()) return 0L
        val cleaned = raw.uppercase(Locale.US).replace("VIEWS", "").trim()
        val match = Regex("([0-9]+(?:[\\.,][0-9]+)?)\\s*([KMB]?)").find(cleaned) ?: return 0L
        val numberText = match.groupValues.getOrNull(1).orEmpty().replace(",", ".")
        val suffix = match.groupValues.getOrNull(2).orEmpty()
        val base = numberText.toDoubleOrNull() ?: return 0L
        val absolute = when (suffix) {
            "K" -> base * 1_000.0
            "M" -> base * 1_000_000.0
            "B" -> base * 1_000_000_000.0
            else -> base
        }
        return absolute.toLong()
    }

    private fun parseIsoToMillisSafe(iso: String): Long? {
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
    private fun showSearchingPlaceholders(query: String) {
        val placeholders = (1..6).map { index ->
            VideoItem(
                videoId = "searching_$index",
                title = "Buscando \"$query\"...",
                channel = "",
                thumbnailUrl = "",
                duration = "--:--",
                viewCount = "",
                section = "Search",
                description = "Consultando resultados online",
                publishedAt = "",
                isPlaceholder = true
            )
        }
        updateVideoList(placeholders)
    }

    private fun normalizeSearchText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
        return normalized
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun matchesSearchQuery(item: VideoItem, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return true
        val haystack = normalizeSearchText(
            listOf(item.title, item.channel, item.section, item.description).joinToString(" ")
        )
        if (haystack.isBlank()) return false
        val terms = normalizedQuery.split(" ").filter { it.isNotBlank() }
        if (terms.isEmpty()) return false
        return terms.all { haystack.contains(it) }
    }

    private fun mergeSearchResults(
        localMatches: List<VideoItem>,
        remoteMatches: List<VideoItem>
    ): List<VideoItem> {
        val merged = LinkedHashMap<String, VideoItem>()

        fun keyOf(item: VideoItem): String {
            return item.videoId.ifBlank {
                "${item.title}|${item.channel}|${item.section}".lowercase(Locale.ROOT)
            }
        }

        localMatches.forEach { item ->
            if (item.title.isNotBlank()) {
                merged[keyOf(item)] = item
            }
        }
        remoteMatches.forEach { item ->
            if (item.title.isNotBlank()) {
                val key = keyOf(item)
                if (!merged.containsKey(key)) {
                    merged[key] = item
                }
            }
        }
        return merged.values.toList()
    }

    private fun mapFeedItemToVideoItem(item: FeedVideo): VideoItem {
        return VideoItem(
            videoId = item.videoId,
            title = item.title,
            channel = item.channel,
            thumbnailUrl = item.thumbnailUrl,
            duration = item.duration.ifBlank { durationCache[item.videoId].orEmpty() },
            viewCount = item.viewCount,
            section = item.section,
            description = item.description,
            publishedAt = item.publishedAt
        )
    }

    private suspend fun loadFeedFromSubscriptions(
        subscriptions: List<LocalChannelSubscription>,
        limit: Int
    ): List<VideoItem> = coroutineScope {
        if (subscriptions.isEmpty()) return@coroutineScope emptyList()

        val unique = subscriptions
            .distinctBy { subscriptionKey(it) }
            .take(12)
        if (unique.isEmpty()) return@coroutineScope emptyList()

        val perChannelLimit = (limit / unique.size).coerceIn(4, 18)
        val loaded = unique.map { subscription ->
            async(Dispatchers.IO) {
                PublicYouTubeFeedClient.loadChannelVideos(
                    channelId = subscription.channelId,
                    channelName = subscription.channelName,
                    limit = perChannelLimit
                ).map { mapFeedItemToVideoItem(it) }
            }
        }.awaitAll().flatten()

        loaded
            .filter { it.title.isNotBlank() }
            .distinctBy { item ->
                item.videoId.ifBlank {
                    "${item.title}|${item.channel}".lowercase(Locale.ROOT)
                }
            }
            .sortedByDescending { parseIsoToMillisSafe(it.publishedAt) ?: Long.MIN_VALUE }
            .take(limit)
    }

    private fun subscriptionKey(item: LocalChannelSubscription): String {
        val channelId = item.channelId.trim()
        if (channelId.isNotBlank()) return "id:$channelId"
        val normalizedName = normalizeSearchText(item.channelName)
        if (normalizedName.isBlank()) return ""
        return "name:$normalizedName"
    }

    private fun buildSubscriptionsFingerprint(): String {
        return ChannelSubscriptionsStore.list(this)
            .asSequence()
            .map { subscriptionKey(it) }
            .filter { it.isNotBlank() }
            .joinToString(";")
    }

    private fun loadDemoVideos() {
        val demo = listOf(
            VideoItem(
                videoId = "",
                title = "Live Coco Cam Teaser",
                channel = "teamcoco",
                thumbnailUrl = "",
                duration = "00:19",
                viewCount = "9,163 views",
                section = "Featured Videos",
                description = "A young woman informs her manager about a surprise.",
                publishedAt = "2024-02-15T12:00:00Z"
            ),
            VideoItem(
                videoId = "",
                title = "Pior cobranca de escanteio da historia do futebol mundial",
                channel = "MegaHyan3",
                thumbnailUrl = "",
                duration = "00:13",
                viewCount = "1,722 views",
                section = "Most Popular",
                description = "A music video style clip with a classic internet vibe.",
                publishedAt = "2024-02-15T12:00:00Z"
            ),
            VideoItem(
                videoId = "",
                title = "F*** GUMBY!!",
                channel = "RayWilliamJohnson",
                thumbnailUrl = "",
                duration = "04:51",
                viewCount = "8,901 views",
                section = "Most Discussed",
                description = "Fast paced commentary clip and reactions.",
                publishedAt = "2024-02-15T12:00:00Z",
                isNew = true
            ),
            VideoItem(
                videoId = "",
                title = "Conan O'Brien TBS Promo: Conan Washes His Desk!",
                channel = "teamcoco",
                thumbnailUrl = "",
                duration = "00:31",
                viewCount = "3,841 views",
                section = "Featured Videos",
                description = "A short promo clip with a comedic setup.",
                publishedAt = "2024-02-15T12:00:00Z"
            )
        )
        onlineVideoPool = emptyList()
        publicFeedCache = emptyList()
        updateVideoList(demo)
    }

    private fun showLoadingPlaceholders() {
        val placeholders = (1..8).map { index ->
            VideoItem(
                videoId = "loading_$index",
                title = "Cargando videos...",
                channel = "",
                thumbnailUrl = "",
                duration = "--:--",
                viewCount = "",
                section = "",
                description = "Espera un momento",
                publishedAt = "",
                isPlaceholder = true
            )
        }
        updateVideoList(placeholders)
    }

    private suspend fun showRandomBatch(
        preferDifferent: Boolean,
        extraAvoidIds: Set<String> = emptySet()
    ): Boolean {
        if (onlineVideoPool.isEmpty()) return false

        val batchSize = minOf(24, onlineVideoPool.size)
        val shuffled = onlineVideoPool.shuffled()
        val selected = mutableListOf<VideoItem>()
        val avoidIds = currentVisibleVideoIds + extraAvoidIds

        if (preferDifferent && avoidIds.isNotEmpty()) {
            val differentItems = shuffled.filterNot { it.videoId in avoidIds }
            selected.addAll(differentItems.take(batchSize))
        }

        if (selected.size < batchSize) {
            for (item in shuffled) {
                if (selected.size >= batchSize) break
                if (!selected.contains(item)) {
                    selected.add(item)
                }
            }
        }

        if (selected.isEmpty()) return false

        val nextIds = selected.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toSet()
        val changed = nextIds.isNotEmpty() && nextIds != currentVisibleVideoIds
        val hydrated = withResolvedDurations(selected)
        mergeDurationsIntoCaches(hydrated)
        updateVideoList(hydrated)
        return changed
    }

    private fun updateVideoList(newItems: List<VideoItem>) {
        lastUnfilteredVideoItems = newItems
        val visibleItems = if (hideShortsEnabled) {
            newItems.filterNot(::isShortVideoItem)
        } else {
            newItems
        }
        videoList.clear()
        videoList.addAll(visibleItems)
        adapter.notifyDataSetChanged()
        updateCurrentVisibleIds(visibleItems)
    }

    private fun isShortVideoItem(item: VideoItem): Boolean {
        if (item.isPlaceholder) return false
        val metadata = normalizeSearchText(
            listOf(item.title, item.section, item.description).joinToString(" ")
        )
        // 1. Check metadata first (works even if duration is unknown)
        if (SHORTS_WORD_REGEX.containsMatchIn(metadata)) return true
        
        // 2. Check duration if available
        val durationSeconds = parseDurationToSeconds(item.duration)
        if (durationSeconds > 0) {
            return durationSeconds <= SHORT_VIDEO_MAX_SECONDS
        }
        
        return false
    }

    private fun updateCurrentVisibleIds(items: List<VideoItem>) {
        currentVisibleVideoIds = items
            .filterNot { it.isPlaceholder }
            .mapNotNull { it.videoId.takeIf(String::isNotBlank) }
            .toSet()
        if (currentVisibleVideoIds.isNotEmpty()) {
            savePreviousSessionVideoIds(currentVisibleVideoIds)
        }
    }

    private suspend fun withResolvedDurations(items: List<VideoItem>): List<VideoItem> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope items

        val semaphore = Semaphore(DURATION_LOOKUP_PARALLELISM)
        items.map { item ->
            async(Dispatchers.IO) {
                if (item.videoId.isBlank()) {
                    return@async item.copy(duration = item.duration.ifBlank { "--:--" })
                }
                if (item.duration.isNotBlank()) {
                    return@async item
                }

                val cached = durationCache[item.videoId]
                if (!cached.isNullOrBlank()) {
                    return@async item.copy(duration = cached)
                }

                val resolved = semaphore.withPermit {
                    withTimeoutOrNull(DURATION_LOOKUP_TIMEOUT_MS) {
                        PublicYouTubeFeedClient.loadVideoDuration(item.videoId)
                    }.orEmpty()
                }

                if (resolved.isNotBlank()) {
                    durationCache[item.videoId] = resolved
                }
                item.copy(duration = resolved.ifBlank { "--:--" })
            }
        }.awaitAll()
    }

    private fun mergeDurationsIntoCaches(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val durationsById = items
            .filter { it.videoId.isNotBlank() && it.duration.isNotBlank() }
            .associate { it.videoId to it.duration }
        if (durationsById.isEmpty()) return

        publicFeedCache = publicFeedCache.map { item ->
            val duration = durationsById[item.videoId] ?: return@map item
            if (item.duration == duration) item else item.copy(duration = duration)
        }
        onlineVideoPool = onlineVideoPool.map { item ->
            val duration = durationsById[item.videoId] ?: return@map item
            if (item.duration == duration) item else item.copy(duration = duration)
        }
        persistDurationCache()
    }

    private fun restoreDurationCache() {
        val raw = prefs.getString(KEY_DURATION_CACHE, "").orEmpty()
        if (raw.isBlank()) return
        raw.split(";").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx <= 0 || idx >= pair.length - 1) return@forEach
            val id = pair.substring(0, idx)
            val duration = pair.substring(idx + 1)
            if (id.isNotBlank() && duration.isNotBlank()) {
                durationCache[id] = duration
            }
        }
    }

    private fun persistDurationCache() {
        if (durationCache.isEmpty()) return
        val serialized = durationCache.entries
            .asSequence()
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .take(DURATION_CACHE_MAX_ENTRIES)
            .joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit {
            putString(KEY_DURATION_CACHE, serialized)
        }
    }

    private fun loadPreviousSessionVideoIds(): Set<String> {
        val raw = prefs.getString(KEY_PREVIOUS_FEED_IDS, "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun savePreviousSessionVideoIds(ids: Set<String>) {
        prefs.edit {
            putString(KEY_PREVIOUS_FEED_IDS, ids.joinToString(","))
        }
    }

    private fun searchDemoVideos(query: String) {
        val base = listOf(
            VideoItem("", "Live Coco Cam Teaser", "teamcoco", "", "00:19", "9,163 views", "Featured Videos", "A young woman informs her manager about a surprise.", "2024-02-15T12:00:00Z"),
            VideoItem("", "Pior cobranca de escanteio da historia do futebol mundial", "MegaHyan3", "", "00:13", "1,722 views", "Most Popular", "A music video style clip with a classic internet vibe.", "2024-02-15T12:00:00Z"),
            VideoItem("", "F*** GUMBY!!", "RayWilliamJohnson", "", "04:51", "8,901 views", "Most Discussed", "Fast paced commentary clip and reactions.", "2024-02-15T12:00:00Z", isNew = true),
            VideoItem("", "Conan O'Brien TBS Promo: Conan Washes His Desk!", "teamcoco", "", "00:31", "3,841 views", "Featured Videos", "A short promo clip with a comedic setup.", "2024-02-15T12:00:00Z")
        )
        val q = query.lowercase()
        val filtered = base.filter { it.title.lowercase().contains(q) || it.channel.lowercase().contains(q) }
        updateVideoList(filtered)
    }

    private fun showLegacyOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_legacy_options, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogView.findViewById<Button>(R.id.legacy_menu_settings_button).setOnClickListener {
            dialog.dismiss()
            handleLegacyMenuAction(R.id.menu_settings)
        }
        dialogView.findViewById<Button>(R.id.legacy_menu_subscriptions_button).setOnClickListener {
            dialog.dismiss()
            handleLegacyMenuAction(R.id.menu_subscriptions)
        }
        dialogView.findViewById<Button>(R.id.legacy_menu_search_button).setOnClickListener {
            dialog.dismiss()
            handleLegacyMenuAction(R.id.menu_search)
        }
        dialog.show()
    }

    private fun showSearchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_search_overlay, null)
        val dialog = AppCompatDialog(this, R.style.ThemeOverlay_Youtube2_SearchFullscreenDialog)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.TOP)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val editText = dialogView.findViewById<EditText>(R.id.search_overlay_edit_text)
        val goButton = dialogView.findViewById<ImageButton>(R.id.search_overlay_go_button)
        val suggestionsList = dialogView.findViewById<ListView>(R.id.search_overlay_suggestions)

        val suggestions = loadSearchHistory()
        val suggestionAdapter = ArrayAdapter(
            this,
            R.layout.item_search_overlay_suggestion,
            android.R.id.text1,
            suggestions
        )
        suggestionsList.adapter = suggestionAdapter

        fun submitSearch() {
            val query = editText.text.toString().trim()
            if (query.isEmpty()) return
            dialog.dismiss()
            searchVideos(query)
        }

        goButton.setOnClickListener { submitSearch() }
        suggestionsList.setOnItemClickListener { _, _, position, _ ->
            val selectedQuery = suggestions.getOrNull(position).orEmpty()
            if (selectedQuery.isBlank()) return@setOnItemClickListener
            editText.setText(selectedQuery)
            editText.setSelection(selectedQuery.length)
            submitSearch()
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch()
                true
            } else {
                false
            }
        }

        if (activeSearchQuery.isNotBlank()) {
            editText.setText(activeSearchQuery)
            editText.setSelection(editText.text?.length ?: 0)
        }

        dialog.setOnShowListener {
            editText.requestFocus()
            val inputManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            inputManager?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun showAdvancedSearchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_advanced_search, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val queryEdit = dialogView.findViewById<EditText>(R.id.advanced_search_edit_text)
        val spinnerDate = dialogView.findViewById<Spinner>(R.id.advanced_spinner_date)
        val spinnerDuration = dialogView.findViewById<Spinner>(R.id.advanced_spinner_duration)
        val spinnerSort = dialogView.findViewById<Spinner>(R.id.advanced_spinner_sort)

        // Setup Adapters
        val dateOptions = listOf("Cualquier fecha", "Hace más de 10 años", "Hace más de 15 años")
        val durationOptions = listOf("Cualquier duración", "Largos (+20 min)")
        val sortOptions = listOf("Relevancia", "Más antiguos")

        val simpleAdapter = { items: List<String> ->
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        }

        spinnerDate.adapter = simpleAdapter(dateOptions)
        spinnerDuration.adapter = simpleAdapter(durationOptions)
        spinnerSort.adapter = simpleAdapter(sortOptions)

        if (activeSearchQuery.isNotBlank()) {
            queryEdit.setText(activeSearchQuery)
            queryEdit.setSelection(queryEdit.text?.length ?: 0)
        }

        dialogView.findViewById<Button>(R.id.advanced_cancel_button).setOnClickListener {
            dialog.dismiss()
        }

        fun submitAdvancedSearch() {
            var query = queryEdit.text.toString().trim()
            
            // Map Spinners to Options
            val dateSelection = spinnerDate.selectedItemPosition
            val durationSelection = spinnerDuration.selectedItemPosition
            val sortSelection = spinnerSort.selectedItemPosition

            val hasFilters = dateSelection > 0 || durationSelection > 0 || sortSelection > 0

            // Block empty search if no filters are selected
            if (query.isEmpty() && !hasFilters) {
                Toast.makeText(this@MainActivity, "Escribe una búsqueda o selecciona un filtro", Toast.LENGTH_SHORT).show()
                return
            }

            // Logic for filters
            val minimumAgeYears = when (dateSelection) {
                1 -> 10
                2 -> 15
                else -> null
            }
            val isLongVideos = durationSelection == 1
            val isSortOldest = sortSelection == 1

            // If query is empty but filters exist, use a RANDOM generic term to ensure results.
            // Searching only "before:2016" often returns nothing on YouTube.
            // By adding a generic term like "vlog", "video", "world", etc., we get results 
            // that respect the filter.
            if (query.isEmpty()) {
                val fallbacks = listOf(
                    "video", "vlog", "news", "music", "game", "funny", 
                    "review", "tutorial", "live", "show", "clip", "best", "top"
                )
                query = fallbacks.random()
            }

            val options = AdvancedSearchOptions(
                query = query,
                minimumAgeYears = minimumAgeYears,
                longVideosOnly = isLongVideos,
                minimumViews = null, 
                sortOldestFirst = isSortOldest
            )
            dialog.dismiss()
            searchVideosAdvanced(options)
        }

        dialogView.findViewById<Button>(R.id.advanced_go_button).setOnClickListener {
            submitAdvancedSearch()
        }

        queryEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitAdvancedSearch()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun openVideoDetail(video: VideoItem) {
        val intent = Intent(this, VideoDetailActivity::class.java).apply {
            putExtra(VideoDetailActivity.EXTRA_VIDEO_ID, video.videoId)
            putExtra(VideoDetailActivity.EXTRA_TITLE, video.title)
            putExtra(VideoDetailActivity.EXTRA_CHANNEL, video.channel)
            putExtra(VideoDetailActivity.EXTRA_SECTION, video.section)
            putExtra(VideoDetailActivity.EXTRA_THUMBNAIL, video.thumbnailUrl)
        }
        startActivity(intent)
    }

    private fun openSubscriptionsScreen() {
        startActivity(Intent(this, SubscriptionsActivity::class.java))
    }

    private fun openSettingsScreen() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openVersionInfoScreen() {
        startActivity(Intent(this, VersionInfoActivity::class.java))
    }

    private fun openDownloadsScreen() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "main_feed_prefs"
        private const val KEY_PREVIOUS_FEED_IDS = "previous_feed_ids"
        private const val KEY_WELCOME_SHOWN = "welcome_shown"
        private const val KEY_DURATION_CACHE = "duration_cache"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_HIDE_SHORTS = "hide_shorts_enabled"
        private const val KEY_SUBSCRIPTIONS_FEED_ONLY = "subscriptions_feed_only"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val LONG_VIDEO_SECONDS = 20L * 60L
        private const val SHORT_VIDEO_MAX_SECONDS = 65L
        private const val DURATION_LOOKUP_PARALLELISM = 8
        private const val DURATION_LOOKUP_TIMEOUT_MS = 1200L
        private const val DURATION_CACHE_MAX_ENTRIES = 240
        private const val MAX_SEARCH_HISTORY_ITEMS = 12
        private const val INFINITE_SCROLL_THRESHOLD = 3
        private const val INITIAL_BATCH_SIZE = 20
        private const val LOAD_MORE_BATCH_SIZE = 15
        private val SHORTS_WORD_REGEX = Regex("shorts?|#shorts", RegexOption.IGNORE_CASE)
    }

    // ── Data class ──────────────────────────────────────────────────────

    data class VideoItem(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String,
        val duration: String,
        val viewCount: String,
        val section: String,
        val description: String = "",
        val publishedAt: String = "",
        val isNew: Boolean = false,
        val isPlaceholder: Boolean = false,
        val isSearchHeader: Boolean = false
    )

    // ── Adapter (optimized for infinite scroll) ─────────────────────────

    class OldYoutubeAdapter(
        private val items: List<VideoItem>,
        private val onItemClick: (VideoItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        companion object {
            private const val TYPE_VIDEO = 0
            private const val TYPE_SEARCH_HEADER = 1
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position].isSearchHeader) TYPE_SEARCH_HEADER else TYPE_VIDEO
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_SEARCH_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(14, 24, 14, 12)
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setBackgroundColor(Color.BLACK)
                }
                SearchHeaderViewHolder(tv)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_video_old, parent, false)
                VideoViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is VideoViewHolder) {
                holder.bind(items[position], onItemClick)
            } else if (holder is SearchHeaderViewHolder) {
                holder.bind(items[position])
            }
        }

        override fun getItemCount(): Int = items.size

        // Free Glide resources when views scroll off-screen
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            if (holder is VideoViewHolder) {
                holder.recycle()
            }
        }

        class SearchHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(item: VideoItem) {
                (itemView as TextView).text = item.title
            }
        }

        class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val thumbnail: ImageView = view.findViewById(R.id.item_thumbnail)
            private val duration: TextView = view.findViewById(R.id.item_duration)
            private val title: TextView = view.findViewById(R.id.item_title)
            private val subtitle: TextView = view.findViewById(R.id.item_subtitle)
            private val date: TextView = view.findViewById(R.id.item_date)
            private val views: TextView = view.findViewById(R.id.item_views)

            fun bind(item: VideoItem, onItemClick: (VideoItem) -> Unit) {
                // Optimized Glide: thumbnail preview + disk cache
                if (item.thumbnailUrl.isNotBlank()) {
                    Glide.with(itemView.context)
                        .load(item.thumbnailUrl)
                        .placeholder(R.drawable.vid)
                        .thumbnail(0.25f)
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .into(thumbnail)
                } else {
                    Glide.with(itemView.context).clear(thumbnail)
                    thumbnail.setImageResource(R.drawable.vid)
                }

                duration.text = item.duration.ifBlank { "--:--" }
                duration.visibility = View.VISIBLE

                if (item.isPlaceholder) {
                    title.text = item.title
                    subtitle.text = item.description.ifBlank { "Cargando..." }
                    date.text = "actualizando..."
                    views.text = ""
                    itemView.alpha = 0.82f
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                    return
                }

                title.text = item.title
                subtitle.text = buildSubtitle(item)
                date.text = formatRelativeDate(item.publishedAt)
                views.text = normalizeViewsText(item.viewCount)
                itemView.alpha = 1f
                itemView.isClickable = true
                itemView.setOnClickListener { onItemClick(item) }
            }

            /** Release Glide bitmap when this ViewHolder scrolls off screen */
            fun recycle() {
                Glide.with(itemView.context).clear(thumbnail)
            }

            private fun buildSubtitle(item: VideoItem): String {
                val raw = item.description.ifBlank { "A video by ${item.channel}" }
                return raw.replace(Regex("\\s+"), " ").trim()
            }

            private fun formatRelativeDate(publishedAt: String): String {
                if (publishedAt.isBlank()) return "hace poco"
                val publishedMillis = parseIsoToMillis(publishedAt) ?: return "hace poco"
                val now = System.currentTimeMillis()
                val diff = (now - publishedMillis).coerceAtLeast(0L)
                val days = diff / DAY_MS
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

            private fun normalizeViewsText(raw: String): String {
                if (raw.isBlank()) return "0 views"
                val cleaned = raw.uppercase(Locale.US).replace("VIEWS", "").trim()
                val match = Regex("([0-9]+(?:[\\.,][0-9]+)?)\\s*([KMB]?)").find(cleaned)
                if (match != null) {
                    val numberText = match.groupValues.getOrNull(1).orEmpty().replace(",", ".")
                    val suffix = match.groupValues.getOrNull(2).orEmpty()
                    val base = numberText.toDoubleOrNull()
                    if (base != null) {
                        val absolute = when (suffix) {
                            "K" -> base * 1_000.0
                            "M" -> base * 1_000_000.0
                            "B" -> base * 1_000_000_000.0
                            else -> base
                        }.toLong()
                        return "${NumberFormat.getIntegerInstance(Locale.US).format(absolute)} views"
                    }
                }
                return if (raw.lowercase(Locale.US).contains("views")) raw else "$raw views"
            }

            companion object {
                private const val DAY_MS = 24L * 60L * 60L * 1000L
            }
        }
    }


    private fun showBottomMenu() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_bottom_menu, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(android.view.Gravity.BOTTOM)
            // Optional: setWindowAnimations if you have a style. Default dialog animation is fine or use slide_up style.
        }

        // ── Actions ──
        dialogView.findViewById<View>(R.id.menu_home).setOnClickListener {
            // "Home" -> Feed
            if (activeSearchQuery.isNotEmpty()) {
                loadPopularVideos()
            } else {
                binding.chatRecyclerView.scrollToPosition(0)
            }
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.menu_subscriptions).setOnClickListener {
            // "Subscripciones" -> Toggle between "Recommendations" (Home) and "Subscriptions"
            if (!subscriptionsFeedOnlyEnabled) {
                val intent = Intent(this, SubscriptionsActivity::class.java)
                intent.putExtra("subscriptions_feed_only", false) // Just open the list
                startActivity(intent)
            } else {
                 loadPopularVideos()
            }
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.menu_search).setOnClickListener {
            dialog.dismiss()
            showSearchDialog()
        }

        dialogView.findViewById<View>(R.id.menu_my_channel).setOnClickListener {
            dialog.dismiss()
            openVersionInfoScreen()
        }

        dialogView.findViewById<View>(R.id.menu_upload).setOnClickListener {
            dialog.dismiss()
            openDownloadsScreen()
        }

        dialogView.findViewById<View>(R.id.menu_settings).setOnClickListener {
            dialog.dismiss()
            openSettingsScreen()
        }

        dialog.show()
    }
}
