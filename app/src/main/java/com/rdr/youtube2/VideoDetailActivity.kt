package com.rdr.youtube2

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoDetailActivity : AppCompatActivity() {

    private enum class Tab { INFO, RELATED, COMMENTS }

    private lateinit var tabInfo: TextView
    private lateinit var tabRelated: TextView
    private lateinit var tabComments: TextView
    private lateinit var tabBottomMask: View
    private lateinit var headerBar: View
    private lateinit var tabsContainer: View

    private lateinit var infoContent: View
    private lateinit var relatedContent: View
    private lateinit var commentsContent: View

    private lateinit var scrollView: ScrollView
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var playerLoading: ProgressBar
    private lateinit var playerControls: View
    private lateinit var playPauseButton: ImageView
    private lateinit var loopButton: ImageView
    private lateinit var fullScreenButton: ImageView
    private lateinit var channelButton: Button
    private lateinit var channelArrowButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    private var player: ExoPlayer? = null
    private var currentVideoId: String = ""
    private var streamCandidates: List<YouTubeStreamResolver.StreamCandidate> = emptyList()
    private var streamCandidateIndex: Int = 0
    private val resolverHandler = Handler(Looper.getMainLooper())
    private var resolveRetryRunnable: Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var progressUpdateRunnable: Runnable? = null
    private var autoHideControlsRunnable: Runnable? = null
    private var isUserSeeking: Boolean = false
    private var controlsVisible: Boolean = true
    private var isLoopEnabled: Boolean = false
    private var isPlayerFullScreen: Boolean = false
    private var selectedTab: Tab = Tab.INFO
    private var playerDefaultHeightPx: Int = 0
    private var activityInstanceId: Int = -1
    private var currentChannelName: String = ""
    private var currentChannelId: String = ""
    private var currentChannelIconUrl: String = ""
    private var pendingDownloadPermissionRequest: Boolean = false
    private var isDownloadingVideo: Boolean = false
    private var didForceStopForClose: Boolean = false



    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.d(TAG, "player error: ${error.errorCodeName}")
            streamCandidateIndex += 1
            playCurrentCandidate()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            refreshKeepScreenOn()
            if (isPlaying) {
                showControls(autoHide = true)
            } else {
                showControls(autoHide = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshKeepScreenOn()
            when (playbackState) {
                Player.STATE_BUFFERING -> showPlayerLoading()
                Player.STATE_READY -> {
                    hidePlayerLoading()
                    findViewById<TextView>(R.id.detail_date).text = "Reproduciendo"
                    updateProgressUi()
                }
                Player.STATE_ENDED -> {
                    hidePlayerLoading()
                    updateProgressUi()
                    showControls(autoHide = false)
                }
                else -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_video_detail)
        activityInstanceId = System.identityHashCode(this)
        headerBar = findViewById(R.id.detail_header_bar)
        tabsContainer = findViewById(R.id.detail_tabs_container)
        scrollView = findViewById(R.id.detail_scroll_view)
        playerContainer = findViewById(R.id.detail_player_container)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        currentVideoId = videoId
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: ""
        val section = intent.getStringExtra(EXTRA_SECTION) ?: ""
        currentChannelName = channel

        findViewById<TextView>(R.id.detail_title).text = title
        findViewById<TextView>(R.id.detail_channel_section).text = "by $channel from $section"
        findViewById<View>(R.id.detail_header_logo_container).setOnClickListener {
            openHome()
        }
        findViewById<ImageView>(R.id.detail_header_logo).setOnClickListener { openHome() }
        findViewById<TextView>(R.id.detail_header_logo_suffix).setOnClickListener {
            startActivity(Intent(this, TwoActivity::class.java))
        }
        findViewById<View>(R.id.detail_header_edit).setOnClickListener {
            showShareOptionsDialog()
        }

        setupPlayerView()
        updateChannelButton(channel)
        val reusedSameVideoPlayback = attachSharedPlayer(videoId)
        if (!reusedSameVideoPlayback) {
            resolveAndPlayVideo(videoId)
        } else {
            findViewById<TextView>(R.id.detail_date).text = "Reproduciendo"
        }

        tabInfo = findViewById(R.id.tab_info)
        tabRelated = findViewById(R.id.tab_related)
        tabComments = findViewById(R.id.tab_comments)
        tabBottomMask = findViewById(R.id.tab_bottom_mask)

        infoContent = findViewById(R.id.detail_info_content)
        relatedContent = findViewById(R.id.detail_related_content)
        commentsContent = findViewById(R.id.detail_comments_content)

        tabInfo.setOnClickListener { selectTab(Tab.INFO) }
        tabRelated.setOnClickListener { selectTab(Tab.RELATED) }
        tabComments.setOnClickListener { selectTab(Tab.COMMENTS) }

        tabInfo.post { selectTab(Tab.INFO) }

        if (videoId.isNotEmpty()) {
            loadVideoDetails(videoId)
            loadRelatedVideos(videoId)
            loadComments(videoId)
        }
    }

    override fun onStart() {
        super.onStart()
        stopPlaybackKeepAliveService()
        refreshKeepScreenOn()
        if (isPlayerFullScreen) {
            applyFullscreenSystemUi(true)
        }
    }

    override fun onStop() {
        updatePlayPauseIcon()
        showControls(autoHide = false)
        setKeepScreenOn(false)
        super.onStop()
        updateKeepAliveServiceState()
    }

    override fun onDestroy() {
        setKeepScreenOn(false)
        if (isPlayerFullScreen) {
            applyFullscreenSystemUi(false)
        }
        cancelResolveRetry()
        stopProgressUpdates()
        cancelControlsAutoHide()
        if (isFinishing) {
            forceStopPlaybackForClose()
        } else {
            player?.removeListener(playerListener)
            player = null
            stopPlaybackKeepAliveService()
        }
        if (::playerView.isInitialized) {
            playerView.player = null
        }
        super.onDestroy()
    }

    override fun finish() {
        forceStopPlaybackForClose()
        super.finish()
    }

    private fun setupPlayerView() {
        playerView = findViewById(R.id.detail_player_view)
        playerLoading = findViewById(R.id.detail_player_loading)
        playerControls = findViewById(R.id.detail_player_controls)
        playPauseButton = findViewById(R.id.detail_control_play_pause)
        loopButton = findViewById(R.id.detail_control_loop)
        fullScreenButton = findViewById(R.id.detail_control_fullscreen)
        channelButton = findViewById(R.id.detail_channel_button)
        channelArrowButton = findViewById(R.id.detail_channel_arrow_button)
        seekBar = findViewById(R.id.detail_control_seekbar)
        currentTimeText = findViewById(R.id.detail_control_current_time)
        totalTimeText = findViewById(R.id.detail_control_total_time)
        if (playerDefaultHeightPx <= 0) {
            playerDefaultHeightPx = playerContainer.layoutParams.height
                .takeIf { it > 0 } ?: (230f * resources.displayMetrics.density).toInt()
        }

        playerView.useController = false
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setOnClickListener { toggleControls() }

        playPauseButton.setOnClickListener { togglePlayPause() }
        loopButton.setOnClickListener { toggleLoopMode() }
        fullScreenButton.setOnClickListener { togglePlayerFullScreen() }
        ImageViewCompat.setImageTintList(loopButton, null)
        isLoopEnabled = sharedLoopEnabled
        updateLoopIcon()
        updateFullScreenIcon()
        channelButton.setOnClickListener {
            openCurrentChannelPage()
        }
        channelArrowButton.setOnClickListener {
            onChannelArrowButtonClicked()
        }

        seekBar.max = SEEK_BAR_MAX
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val durationMs = player?.duration ?: 0L
                if (durationMs > 0L) {
                    val pos = progressToPosition(progress, durationMs)
                    currentTimeText.text = formatPlaybackTime(pos)
                    // Enable live scrubbing: seek while dragging
                    player?.seekTo(pos)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                showControls(autoHide = false)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: 0
                val durationMs = player?.duration ?: 0L
                if (durationMs > 0L) {
                    player?.seekTo(progressToPosition(target, durationMs))
                }
                isUserSeeking = false
                updateProgressUi()
                showControls(autoHide = true)
            }
        })

        currentTimeText.text = "0:00"
        totalTimeText.text = "0:00"
        updatePlayPauseIcon()
        showControls(autoHide = false)
        startProgressUpdates()
        showPlayerLoading()
    }

    private fun attachSharedPlayer(videoId: String): Boolean {
        val shared = sharedPlayer ?: return false
        player = shared
        playerView.player = shared
        shared.removeListener(playerListener)
        shared.addListener(playerListener)
        sharedOwnerInstanceId = activityInstanceId
        isLoopEnabled = sharedLoopEnabled
        applyLoopState(shared)
        updateLoopIcon()
        updatePlayPauseIcon()
        updateProgressUi()
        hidePlayerLoading()
        showControls(autoHide = shared.isPlaying)
        refreshKeepScreenOn()
        startProgressUpdates()
        return videoId.isNotBlank() && videoId == sharedVideoId
    }

    private fun resolveAndPlayVideo(videoId: String, fromRetry: Boolean = false) {
        if (videoId.isBlank()) {
            showPlayerLoading()
            findViewById<TextView>(R.id.detail_date).text = "Video sin ID"
            return
        }

        if (!fromRetry) {
            findViewById<TextView>(R.id.detail_date).text = "Resolviendo stream..."
        }
        showPlayerLoading()

        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                YouTubeStreamResolver.resolve(videoId)
            }

            if (videoId != currentVideoId) return@launch

            if (candidates.isEmpty()) {
                findViewById<TextView>(R.id.detail_date).text = "Reintentando stream..."
                scheduleResolveRetry(videoId)
                return@launch
            }

            cancelResolveRetry()
            streamCandidates = candidates
            streamCandidateIndex = 0
            playCurrentCandidate()
        }
    }

    private fun ensurePlayer(candidate: YouTubeStreamResolver.StreamCandidate) {
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = "https://www.youtube.com/"
        headers["Origin"] = "https://www.youtube.com"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers.putAll(candidate.requestHeaders)

        val userAgent = candidate.userAgent.ifBlank {
            "Mozilla/5.0 (Linux; Android 4.1.2; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0 Mobile Safari/537.36"
        }
        val signature = buildString {
            append(userAgent)
            append("|")
            headers.entries.sortedBy { it.key }.forEach {
                append(it.key)
                append("=")
                append(it.value)
                append(";")
            }
        }

        if (sharedPlayer != null && sharedPlayerSignature == signature) {
            player = sharedPlayer
            playerView.player = player
            player?.removeListener(playerListener)
            player?.addListener(playerListener)
            sharedOwnerInstanceId = activityInstanceId
            applyLoopState(player)
            return
        }

        releaseSharedPlayer()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(20000)
        val dataSourceFactory = DefaultDataSource.Factory(applicationContext, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val newPlayer = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        applyLoopState(newPlayer)
        newPlayer.addListener(playerListener)
        playerView.player = newPlayer
        player = newPlayer
        sharedPlayer = newPlayer
        sharedPlayerSignature = signature
        sharedOwnerInstanceId = activityInstanceId
        updatePlayPauseIcon()
        updateProgressUi()
        refreshKeepScreenOn()
        startProgressUpdates()
    }

    private fun playCurrentCandidate() {
        if (streamCandidateIndex >= streamCandidates.size) {
            findViewById<TextView>(R.id.detail_date).text = "Reintentando stream..."
            Log.d(TAG, "all stream candidates failed for videoId=$currentVideoId")
            scheduleResolveRetry(currentVideoId)
            return
        }

        val candidate = streamCandidates[streamCandidateIndex]
        findViewById<TextView>(R.id.detail_date).text = "Cargando ${candidate.qualityLabel}..."
        Log.d(TAG, "trying candidate idx=$streamCandidateIndex mime=${candidate.mimeType} source=${candidate.source}")
        showPlayerLoading()
        ensurePlayer(candidate)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(candidate.url)

        val mimeType = when {
            candidate.mimeType.contains("mpegURL", ignoreCase = true) -> "application/x-mpegURL"
            candidate.mimeType.contains("dash+xml", ignoreCase = true) -> "application/dash+xml"
            candidate.mimeType.contains("video/mp4", ignoreCase = true) -> "video/mp4"
            else -> null
        }
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        val mediaItem = mediaItemBuilder.build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        sharedVideoId = currentVideoId
        sharedLoopEnabled = isLoopEnabled
        updateProgressUi()
        showControls(autoHide = true)
        refreshKeepScreenOn()
        updateKeepAliveServiceState()
    }

    private fun releaseSharedPlayer() {
        sharedPlayer?.removeListener(playerListener)
        sharedPlayer?.release()
        sharedPlayer = null
        sharedPlayerSignature = ""
        sharedVideoId = ""
        sharedOwnerInstanceId = -1
        player = null
        setKeepScreenOn(false)
        updatePlayPauseIcon()
        updateProgressUi()
    }

    private fun showPlayerLoading() {
        if (::playerLoading.isInitialized) {
            playerLoading.visibility = View.VISIBLE
        }
    }

    private fun hidePlayerLoading() {
        if (::playerLoading.isInitialized) {
            playerLoading.visibility = View.GONE
        }
    }

    private fun togglePlayPause() {
        val activePlayer = player ?: return
        if (activePlayer.isPlaying) {
            activePlayer.pause()
        } else {
            if (activePlayer.playbackState == Player.STATE_ENDED) {
                activePlayer.seekTo(0)
            }
            activePlayer.playWhenReady = true
            activePlayer.play()
        }
        updatePlayPauseIcon()
        showControls(autoHide = true)
        refreshKeepScreenOn()
        updateKeepAliveServiceState()
    }

    private fun refreshKeepScreenOn() {
        val activePlayer = player
        val shouldKeepScreenOn = activePlayer?.let {
            it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE
        } ?: false
        setKeepScreenOn(shouldKeepScreenOn)
    }

    private fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (::playerContainer.isInitialized) {
            playerContainer.keepScreenOn = keepOn
        }
        if (::playerView.isInitialized) {
            playerView.keepScreenOn = keepOn
        }
    }

    private fun toggleLoopMode() {
        isLoopEnabled = !isLoopEnabled
        sharedLoopEnabled = isLoopEnabled
        applyLoopState(player)
        updateLoopIcon()
    }

    private fun applyLoopState(targetPlayer: ExoPlayer?) {
        targetPlayer?.repeatMode = if (isLoopEnabled) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    private fun updateLoopIcon() {
        if (!::loopButton.isInitialized) return
        val color = if (isLoopEnabled) 0xFFFFFFFF.toInt() else 0xFFC9C9C9.toInt()
        loopButton.drawable?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        loopButton.alpha = if (isLoopEnabled) 1f else 0.85f
    }

    private fun togglePlayerFullScreen() {
        if (isPlayerFullScreen) {
            exitPlayerFullScreen()
        } else {
            enterPlayerFullScreen()
        }
    }

    private fun enterPlayerFullScreen() {
        if (isPlayerFullScreen) return
        isPlayerFullScreen = true

        if (::headerBar.isInitialized) headerBar.visibility = View.GONE
        if (::tabsContainer.isInitialized) tabsContainer.visibility = View.GONE
        if (::infoContent.isInitialized) infoContent.visibility = View.GONE
        if (::relatedContent.isInitialized) relatedContent.visibility = View.GONE
        if (::commentsContent.isInitialized) commentsContent.visibility = View.GONE

        if (::playerContainer.isInitialized) {
            val params = playerContainer.layoutParams
            if (playerDefaultHeightPx <= 0) {
                playerDefaultHeightPx = params.height.takeIf { it > 0 }
                    ?: (230f * resources.displayMetrics.density).toInt()
            }
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            playerContainer.layoutParams = params
        }

        if (::scrollView.isInitialized) {
            scrollView.post { scrollView.scrollTo(0, 0) }
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyFullscreenSystemUi(true)
        updateFullScreenIcon()
        showControls(autoHide = true)
    }

    private fun exitPlayerFullScreen() {
        if (!isPlayerFullScreen) return
        isPlayerFullScreen = false

        if (::headerBar.isInitialized) headerBar.visibility = View.VISIBLE
        if (::tabsContainer.isInitialized) tabsContainer.visibility = View.VISIBLE
        if (::tabInfo.isInitialized) selectTab(selectedTab)

        if (::playerContainer.isInitialized) {
            val params = playerContainer.layoutParams
            params.height = if (playerDefaultHeightPx > 0) {
                playerDefaultHeightPx
            } else {
                (230f * resources.displayMetrics.density).toInt()
            }
            playerContainer.layoutParams = params
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyFullscreenSystemUi(false)
        updateFullScreenIcon()
    }

    private fun applyFullscreenSystemUi(fullScreen: Boolean) {
        if (fullScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun updateFullScreenIcon() {
        if (!::fullScreenButton.isInitialized) return
        val icon = if (isPlayerFullScreen) {
            R.drawable.ic_player_fullscreen_exit
        } else {
            R.drawable.ic_player_fullscreen_enter
        }
        fullScreenButton.setImageResource(icon)
    }

    private fun toggleControls() {
        if (!::playerControls.isInitialized) return
        if (controlsVisible) {
            hideControls()
        } else {
            showControls(autoHide = true)
        }
    }

    private fun showControls(autoHide: Boolean) {
        if (!::playerControls.isInitialized) return
        playerControls.visibility = View.VISIBLE
        controlsVisible = true
        if (autoHide && player?.isPlaying == true && !isUserSeeking) {
            scheduleControlsAutoHide()
        } else {
            cancelControlsAutoHide()
        }
    }

    private fun hideControls() {
        if (!::playerControls.isInitialized) return
        playerControls.visibility = View.GONE
        controlsVisible = false
        cancelControlsAutoHide()
    }

    private fun scheduleControlsAutoHide() {
        cancelControlsAutoHide()
        autoHideControlsRunnable = Runnable {
            if (player?.isPlaying == true && !isUserSeeking) {
                hideControls()
            }
        }
        uiHandler.postDelayed(autoHideControlsRunnable!!, CONTROL_HIDE_DELAY_MS)
    }

    private fun cancelControlsAutoHide() {
        autoHideControlsRunnable?.let { uiHandler.removeCallbacks(it) }
        autoHideControlsRunnable = null
    }

    private fun updateKeepAliveServiceState() {
        // Background playback service is disabled:
        // playback must stop when VideoDetailActivity is closed.
        stopPlaybackKeepAliveService()
    }

    private fun stopPlaybackKeepAliveService() {
        try {
            stopService(Intent(this, PlaybackKeepAliveService::class.java))
        } catch (_: Throwable) {
            // Ignore if service was never started.
        }
    }

    private fun forceStopPlaybackForClose() {
        if (didForceStopForClose) return
        didForceStopForClose = true

        val activePlayer = player ?: sharedPlayer
        runCatching {
            activePlayer?.playWhenReady = false
            activePlayer?.stop()
        }
        releaseSharedPlayer()
        stopPlaybackKeepAliveService()
    }

    private fun startProgressUpdates() {
        if (progressUpdateRunnable != null) return
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                updateProgressUi()
                uiHandler.postDelayed(this, 500L)
            }
        }
        uiHandler.post(progressUpdateRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressUpdateRunnable?.let { uiHandler.removeCallbacks(it) }
        progressUpdateRunnable = null
    }

    private fun updatePlayPauseIcon() {
        if (!::playPauseButton.isInitialized) return
        val icon = if (player?.isPlaying == true) {
            R.drawable.ic_player_pause
        } else {
            R.drawable.ic_player_play
        }
        playPauseButton.setImageResource(icon)
    }

    private fun updateProgressUi() {
        if (!::seekBar.isInitialized || !::currentTimeText.isInitialized || !::totalTimeText.isInitialized) {
            return
        }

        val activePlayer = player
        if (activePlayer == null) {
            if (!isUserSeeking) {
                seekBar.progress = 0
                seekBar.secondaryProgress = 0
            }
            currentTimeText.text = "0:00"
            totalTimeText.text = "0:00"
            return
        }

        val durationMs = activePlayer.duration.takeIf { it > 0L } ?: 0L
        val positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
        val bufferedMs = activePlayer.bufferedPosition.coerceAtLeast(0L)

        if (!isUserSeeking) {
            val progress = positionToProgress(positionMs, durationMs)
            seekBar.progress = progress
        }
        seekBar.secondaryProgress = positionToProgress(bufferedMs, durationMs)
        currentTimeText.text = formatPlaybackTime(positionMs)
        totalTimeText.text = if (durationMs > 0L) formatPlaybackTime(durationMs) else "0:00"
    }

    private fun positionToProgress(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return ((positionMs * SEEK_BAR_MAX) / durationMs).toInt().coerceIn(0, SEEK_BAR_MAX)
    }

    private fun progressToPosition(progress: Int, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (progress.coerceIn(0, SEEK_BAR_MAX).toLong() * durationMs) / SEEK_BAR_MAX
    }

    private fun formatPlaybackTime(timeMs: Long): String {
        val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun scheduleResolveRetry(videoId: String) {
        if (videoId.isBlank()) return
        cancelResolveRetry()
        resolveRetryRunnable = Runnable {
            resolveAndPlayVideo(videoId, fromRetry = true)
        }
        resolverHandler.postDelayed(resolveRetryRunnable!!, 3500L)
    }

    private fun cancelResolveRetry() {
        resolveRetryRunnable?.let { resolverHandler.removeCallbacks(it) }
        resolveRetryRunnable = null
    }

    // ── API calls ───────────────────────────────────────────────────────

    private fun loadVideoDetails(videoId: String) {
        val dateView = findViewById<TextView>(R.id.detail_date)
        if (dateView.text.isNullOrBlank()) {
            dateView.text = "Public feed mode"
        }
        findViewById<TextView>(R.id.detail_stats).text = ""

        if (videoId.isBlank()) {
            if (findViewById<TextView>(R.id.detail_description).text.isNullOrBlank()) {
                findViewById<TextView>(R.id.detail_description).text = "No description available."
            }
            return
        }

        lifecycleScope.launch {
            val feedVideo = PublicYouTubeFeedClient.loadVideoDetails(videoId)
            // Ensure we are still on the same video
            if (videoId != currentVideoId) return@launch

            val descriptionView = findViewById<TextView>(R.id.detail_description)
            val realDescription = feedVideo?.description?.trim().orEmpty()
            val channelName = feedVideo?.channel?.trim().orEmpty()
            if (channelName.isNotEmpty()) {
                findViewById<TextView>(R.id.detail_channel_section).text = "by $channelName"
                updateChannelButton(channelName)
            }
            if (realDescription.isNotEmpty()) {
                descriptionView.text = realDescription
                descriptionView.movementMethod = LinkMovementMethod.getInstance()
            } else if (descriptionView.text.isNullOrBlank()) {
                descriptionView.text = "No description available."
            }
        }
    }

    private fun loadRelatedVideos(videoId: String) {
        lifecycleScope.launch {
            val container = findViewById<LinearLayout>(R.id.detail_related_list)
            container.removeAllViews()
            try {
                val feedItems = PublicYouTubeFeedClient.loadRecentVideos(limit = 12)
                    .filter { it.videoId != videoId }
                    .take(8)

                if (feedItems.isEmpty()) {
                    val empty = TextView(this@VideoDetailActivity).apply {
                        text = "No related videos found."
                        setTextColor(0xFFD0D0D0.toInt())
                        textSize = 13f
                    }
                    container.addView(empty)
                    return@launch
                }

                for (item in feedItems) {
                    val row = LayoutInflater.from(this@VideoDetailActivity)
                        .inflate(R.layout.item_related_video, container, false)

                    val thumbView = row.findViewById<ImageView>(R.id.related_thumbnail)
                    val titleView = row.findViewById<TextView>(R.id.related_title)

                    Glide.with(this@VideoDetailActivity)
                        .load(item.thumbnailUrl)
                        .placeholder(R.drawable.vid)
                        .into(thumbView)
                    titleView.text = item.title

                    row.setOnClickListener {
                        val intent = android.content.Intent(
                            this@VideoDetailActivity,
                            VideoDetailActivity::class.java
                        ).apply {
                            putExtra(EXTRA_VIDEO_ID, item.videoId)
                            putExtra(EXTRA_TITLE, item.title)
                            putExtra(EXTRA_CHANNEL, item.channel)
                            putExtra(EXTRA_SECTION, item.section)
                        }
                        startActivity(intent)
                    }

                    container.addView(row)
                }
            } catch (_: Throwable) {
                val empty = TextView(this@VideoDetailActivity).apply {
                    text = "No related videos found."
                    setTextColor(0xFFD0D0D0.toInt())
                    textSize = 13f
                }
                container.addView(empty)
            }
        }
    }

    private fun loadComments(videoId: String) {
        val container = findViewById<LinearLayout>(R.id.detail_comments_list)
        container.removeAllViews()
        val text = TextView(this).apply {
            this.text = "Comments no disponibles sin API key."
            setTextColor(0xFFD0D0D0.toInt())
            textSize = 13f
        }
        container.addView(text)
    }



    // ── Helpers ─────────────────────────────────────────────────────────



    private fun updateChannelButton(channelNameRaw: String) {
        if (!::channelButton.isInitialized) return
        val channelName = channelNameRaw.trim()
        currentChannelName = channelName
        if (channelName.isBlank()) {
            channelButton.visibility = View.GONE
            if (::channelArrowButton.isInitialized) {
                channelArrowButton.visibility = View.GONE
            }
            return
        }
        channelButton.text = channelName
        channelButton.visibility = View.VISIBLE
        if (::channelArrowButton.isInitialized) {
            channelArrowButton.visibility = View.VISIBLE
        }
    }

    private fun openCurrentChannelPage() {
        val name = currentChannelName.trim()
        if (name.isBlank()) return
        val intent = Intent(this, ChannelDetailActivity::class.java).apply {
            putExtra(ChannelDetailActivity.EXTRA_CHANNEL_NAME, name)
            putExtra(ChannelDetailActivity.EXTRA_CHANNEL_ID, currentChannelId)
            putExtra(ChannelDetailActivity.EXTRA_CHANNEL_ICON_URL, currentChannelIconUrl)
        }
        startActivity(intent)
    }

    private fun openHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun showShareOptionsDialog() {
        val youtubeUrl = buildYoutubeWatchUrl()
        if (youtubeUrl.isBlank()) {
            Toast.makeText(this, "Video no disponible para compartir", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("Compartir", "Copiar enlace de YouTube")
        AlertDialog.Builder(this)
            .setTitle("Compartir video")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareCurrentVideo(youtubeUrl)
                    1 -> copyCurrentVideoLink(youtubeUrl)
                }
            }
            .show()
    }

    private fun buildYoutubeWatchUrl(): String {
        val videoId = currentVideoId.trim()
        return if (videoId.isBlank()) "" else "https://www.youtube.com/watch?v=$videoId"
    }

    private fun shareCurrentVideo(youtubeUrl: String) {
        val title = findViewById<TextView>(R.id.detail_title).text?.toString().orEmpty().trim()
        val text = if (title.isBlank()) youtubeUrl else "$title\n$youtubeUrl"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "YouTube" })
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Compartir video"))
        } catch (_: Throwable) {
            Toast.makeText(this, "No hay apps para compartir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyCurrentVideoLink(youtubeUrl: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, "No se pudo copiar el enlace", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube link", youtubeUrl))
        Toast.makeText(this, "Enlace de YouTube copiado", Toast.LENGTH_SHORT).show()
    }

    private fun onChannelArrowButtonClicked() {
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "Video no disponible para descargar", Toast.LENGTH_SHORT).show()
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadPermissionRequest = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS_FOR_DOWNLOAD
            )
            return
        }
        continueToDownloadAfterNotificationCheck()
    }

    private fun continueToDownloadAfterNotificationCheck() {
        if (
            Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadPermissionRequest = true
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE_FOR_DOWNLOAD
            )
            return
        }
        showDownloadQualityDialog()
    }

    private fun showDownloadQualityDialog() {
        if (isDownloadingVideo) {
            Toast.makeText(this, "Ya hay una descarga en progreso", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) { resolveDownloadCandidates() }
            if (candidates.isEmpty()) {
                Toast.makeText(
                    this@VideoDetailActivity,
                    "No hay calidades MP4 disponibles para descargar",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val items = candidates.map { candidate ->
                val quality = candidate.qualityLabel.ifBlank { "MP4" }
                val audioTag = if (candidate.hasAudio) "" else " · sin audio"
                "$quality  (${candidate.source}$audioTag)"
            }

            AlertDialog.Builder(this@VideoDetailActivity)
                .setTitle("Elegir calidad de descarga")
                .setItems(items.toTypedArray()) { _, which ->
                    val selected = candidates.getOrNull(which) ?: return@setItems
                    startVideoDownload(selected, candidates)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private suspend fun resolveDownloadCandidates(): List<YouTubeStreamResolver.StreamCandidate> {
        val cachedMp4 = streamCandidates
            .filter { it.mimeType.contains("video/mp4", ignoreCase = true) }
            .distinctBy { it.url }

        val resolvedAll = YouTubeStreamResolver.resolve(
            currentVideoId,
            includeVideoOnlyMp4 = true
        )
        val resolvedMp4 = resolvedAll
            .filter { it.mimeType.contains("video/mp4", ignoreCase = true) }
            .distinctBy { it.url }

        if (resolvedMp4.isEmpty()) {
            return sortDownloadCandidates(cachedMp4)
        }

        return sortDownloadCandidates(
            (resolvedMp4 + cachedMp4).distinctBy { it.url }
        )
    }

    private fun sortDownloadCandidates(
        candidates: List<YouTubeStreamResolver.StreamCandidate>
    ): List<YouTubeStreamResolver.StreamCandidate> {
        return candidates
            .distinctBy { "${it.qualityLabel}|${it.url}" }
            .sortedWith(
                compareByDescending<YouTubeStreamResolver.StreamCandidate> { parseQualityScore(it.qualityLabel) }
                    .thenByDescending { it.hasAudio }
                    .thenBy { sourcePriorityForDownload(it.source) }
            )
    }

    private fun parseQualityScore(label: String): Int {
        val match = Regex("(\\d{2,4})p", RegexOption.IGNORE_CASE).find(label)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun sourcePriorityForDownload(source: String): Int {
        val normalized = source.lowercase()
        return when {
            normalized.contains("invidious") -> 0
            normalized.contains("piped") -> 1
            normalized.startsWith("youtubei-web") -> 2
            normalized.startsWith("youtubei") -> 3
            normalized.contains("watch") -> 4
            else -> 5
        }
    }

    private fun startVideoDownload(
        candidate: YouTubeStreamResolver.StreamCandidate,
        allCandidates: List<YouTubeStreamResolver.StreamCandidate>
    ) {
        if (isDownloadingVideo) return
        isDownloadingVideo = true

        val title = findViewById<TextView>(R.id.detail_title).text?.toString().orEmpty()
        val quality = candidate.qualityLabel.ifBlank { "MP4" }
        Toast.makeText(this, "Descargando en $quality...", Toast.LENGTH_SHORT).show()
        if (!candidate.hasAudio) {
            Toast.makeText(
                this,
                "Esta calidad se descargara sin audio",
                Toast.LENGTH_SHORT
            ).show()
        }

        lifecycleScope.launch {
            val attemptCandidates = buildList {
                add(candidate)
                allCandidates.forEach { alt ->
                    if (alt.url != candidate.url) add(alt)
                }
            }
            var finalResult = VideoDownloadManager.DownloadResult(
                success = false,
                errorMessage = "No se pudo iniciar la descarga"
            )

            withContext(Dispatchers.IO) {
                for (attempt in attemptCandidates) {
                    val result = VideoDownloadManager.downloadMp4(
                        context = this@VideoDetailActivity,
                        videoId = currentVideoId,
                        title = title,
                        candidate = attempt
                    )
                    finalResult = result
                    if (result.success) break
                }
            }
            isDownloadingVideo = false

            if (finalResult.success) {
                Toast.makeText(
                    this@VideoDetailActivity,
                    "Descarga iniciada: ${finalResult.fileName}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@VideoDetailActivity,
                    "Error al descargar: ${finalResult.errorMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS_FOR_DOWNLOAD) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingDownloadPermissionRequest) {
                pendingDownloadPermissionRequest = false
                continueToDownloadAfterNotificationCheck()
            } else {
                pendingDownloadPermissionRequest = false
                Toast.makeText(
                    this,
                    "Notificaciones denegadas, se descargara sin notificacion",
                    Toast.LENGTH_SHORT
                ).show()
                continueToDownloadAfterNotificationCheck()
            }
            return
        }
        if (requestCode != REQUEST_WRITE_STORAGE_FOR_DOWNLOAD) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && pendingDownloadPermissionRequest) {
            pendingDownloadPermissionRequest = false
            showDownloadQualityDialog()
        } else {
            pendingDownloadPermissionRequest = false
            Toast.makeText(
                this,
                "Permiso de almacenamiento denegado",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Tabs ────────────────────────────────────────────────────────────

    private fun selectTab(tab: Tab) {
        selectedTab = tab
        when (tab) {
            Tab.INFO -> {
                tabInfo.setBackgroundResource(R.drawable.tab_info_selected)
                tabRelated.setBackgroundResource(R.drawable.tab_unselected)
                tabComments.setBackgroundResource(R.drawable.tab_unselected)
                setTabState(tabInfo, true, 0xFFF2F2F2.toInt())
                setTabState(tabRelated, false, 0xFFB8B8B8.toInt())
                setTabState(tabComments, false, 0xFF8D8D8D.toInt())
                infoContent.visibility = View.VISIBLE
                relatedContent.visibility = View.GONE
                commentsContent.visibility = View.GONE
                moveBottomMaskUnder(tabInfo)
            }
            Tab.RELATED -> {
                tabInfo.setBackgroundResource(R.drawable.tab_unselected)
                tabRelated.setBackgroundResource(R.drawable.tab_selected_middle)
                tabComments.setBackgroundResource(R.drawable.tab_unselected)
                setTabState(tabInfo, false, 0xFFB8B8B8.toInt())
                setTabState(tabRelated, true, 0xFFF2F2F2.toInt())
                setTabState(tabComments, false, 0xFF8D8D8D.toInt())
                infoContent.visibility = View.GONE
                relatedContent.visibility = View.VISIBLE
                commentsContent.visibility = View.GONE
                moveBottomMaskUnder(tabRelated)
            }
            Tab.COMMENTS -> {
                tabInfo.setBackgroundResource(R.drawable.tab_unselected)
                tabRelated.setBackgroundResource(R.drawable.tab_unselected)
                tabComments.setBackgroundResource(R.drawable.tab_selected_right)
                setTabState(tabInfo, false, 0xFFB8B8B8.toInt())
                setTabState(tabRelated, false, 0xFFB8B8B8.toInt())
                setTabState(tabComments, true, 0xFFF2F2F2.toInt())
                infoContent.visibility = View.GONE
                relatedContent.visibility = View.GONE
                commentsContent.visibility = View.VISIBLE
                moveBottomMaskUnder(tabComments)
            }
        }
    }

    private fun setTabState(tab: TextView, selected: Boolean, color: Int) {
        tab.setTextColor(color)
        tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun moveBottomMaskUnder(targetTab: View) {
        val params = tabBottomMask.layoutParams as FrameLayout.LayoutParams
        params.width = targetTab.width
        params.leftMargin = targetTab.left
        tabBottomMask.layoutParams = params
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPlayerFullScreen) {
            exitPlayerFullScreen()
            return
        }
        super.onBackPressed()
    }

    companion object {
        private const val TAG = "YTPlayer"
        private const val SEEK_BAR_MAX = 1000
        private const val CONTROL_HIDE_DELAY_MS = 3200L
        private const val REQUEST_POST_NOTIFICATIONS_FOR_DOWNLOAD = 2200
        private const val REQUEST_WRITE_STORAGE_FOR_DOWNLOAD = 2201
        private var sharedPlayer: ExoPlayer? = null
        private var sharedPlayerSignature: String = ""
        private var sharedVideoId: String = ""
        private var sharedLoopEnabled: Boolean = false
        private var sharedOwnerInstanceId: Int = -1
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_THUMBNAIL = "extra_thumbnail"
    }
}
