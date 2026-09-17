package com.example.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.AppSettings
import com.example.data.model.ChannelEmote
import com.example.data.model.ChannelUser
import com.example.data.model.ChatLayout
import com.example.data.model.ChatMessage
import com.example.data.model.KnownChannels
import com.example.data.guide.GuideChannel
import com.example.data.guide.GuideRepository
import com.example.data.webqueue.WebQueueRepository
import com.example.data.webqueue.WebQueueState
import com.example.data.webqueue.WebQueueItem
import com.example.data.model.LoginState
import com.example.data.model.ConnectionStatus
import com.example.data.model.MediaItem
import com.example.data.model.MediaSyncUpdate
import com.example.data.model.MovieInfo
import com.example.data.model.QueueScheduleItem
import com.example.data.model.SettingsPage
import com.example.ui.nav.NavItem
import com.example.ui.theme.applyPalette
import com.example.data.repository.SettingsRepository
import com.example.data.movie.MovieInfoRepository
import com.example.data.movie.displayTitle
import com.example.data.scraper.DataScraper
import com.example.data.scraper.MetadataOverlayState
import com.example.data.socket.CyTubeSocketClient
import com.example.ui.player.extractYouTubeId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PlayerViewModel"

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepo = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val playerManager = VideoPlayerManager(
        context = application,
        scope = viewModelScope
    )

    private val socketClient = CyTubeSocketClient(viewModelScope)
    private val guideRepo = GuideRepository(viewModelScope)

    // ---------------- Channel-Z web queue (kryten-webqueue) ----------------
    val webQueueRepo = WebQueueRepository(application, viewModelScope)
    val webQueueState: StateFlow<WebQueueState> = webQueueRepo.state
    val webQueueItems: StateFlow<List<WebQueueItem>> = webQueueRepo.items

    fun webQueueLink(code: String) {
        viewModelScope.launch { webQueueRepo.link(code) }
    }

    fun webQueueDisconnect() = webQueueRepo.disconnect()

    // ---------------- TV Guide ----------------
    private val _isGuideOpen = MutableStateFlow(false)
    val isGuideOpen: StateFlow<Boolean> = _isGuideOpen.asStateFlow()
    private val _guideRow = MutableStateFlow(0)
    val guideRow: StateFlow<Int> = _guideRow.asStateFlow()
    private val _guideCol = MutableStateFlow(0)
    val guideCol: StateFlow<Int> = _guideCol.asStateFlow()
    /** True after the user pressed LEFT on the currently-playing block: show its start time. */
    private val _guideScrolledBack = MutableStateFlow(false)
    val guideScrolledBack: StateFlow<Boolean> = _guideScrolledBack.asStateFlow()
    // Prefetched metadata for every queue item across all channels, keyed by the raw stream
    // title. Refreshed on a timer so the guide never blocks on a per-item lookup.
    private val _metadataByTitle = MutableStateFlow<Map<String, MovieInfo?>>(emptyMap())
    val metadataByTitle: StateFlow<Map<String, MovieInfo?>> = _metadataByTitle.asStateFlow()
    private val metaCache = mutableMapOf<String, MovieInfo?>()
    private var metadataPrefetchJob: Job? = null


    private val _movieInfo = MutableStateFlow<MovieInfo?>(null)
    val movieInfo: StateFlow<MovieInfo?> = _movieInfo.asStateFlow()

    private val _isTriviaVisible = MutableStateFlow(false)
    val isTriviaVisible: StateFlow<Boolean> = _isTriviaVisible.asStateFlow()

    private val _isTriviaLoading = MutableStateFlow(false)
    val isTriviaLoading: StateFlow<Boolean> = _isTriviaLoading.asStateFlow()

    private var movieInfoJob: Job? = null

    val connectionStatus: StateFlow<ConnectionStatus> = socketClient.connectionStatus
    val nowPlaying: StateFlow<MediaItem?> = socketClient.nowPlaying
    val upNext: StateFlow<List<MediaItem>> = socketClient.upNext
    val userCount: StateFlow<Int> = socketClient.userCount
    val chatMessages: StateFlow<List<ChatMessage>> = socketClient.chatMessages
    val users: StateFlow<List<ChannelUser>> = socketClient.users
    val emotes: StateFlow<List<ChannelEmote>> = socketClient.emotes
    val loginState: StateFlow<LoginState> = socketClient.loginState
    val queueScheduleItems: StateFlow<List<QueueScheduleItem>> = dataScraper.queueScheduleItems
    val mediaSyncEvent: SharedFlow<MediaSyncUpdate> = socketClient.mediaSyncEvent

    /** One row per known channel; the active one is fed by the player socket, the rest by scouts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dataScraper = DataScraper(viewModelScope)
    private val movieInfoRepo = MovieInfoRepository()

    val guideChannels: StateFlow<List<GuideChannel>> = settings
        .map { it.roomName }
        .distinctUntilChanged()
        .flatMapLatest { active ->
            val rowFlows = KnownChannels.map { ch ->
                val client = if (ch.room == active) socketClient else guideRepo.scout(ch.room)
                // The scraped schedule (bot / Reddit EPG) is Grindhouse-specific; other rooms
                // get an empty fallback and rely purely on their CyTube queue.
                val fallback = when (ch.room) {
                    "420Grindhouse" -> dataScraper.scheduleItems
                    "Channel-Z" -> webQueueRepo.items.map { list -> list.map { it.media } }
                    else -> MutableStateFlow(emptyList())
                }
                combine(client.nowPlaying, client.playlist, client.connectionStatus, fallback) { np, pl, st, sched ->
                    GuideChannel(
                        room = ch.room,
                        label = ch.label,
                        isActive = ch.room == active,
                        status = st,
                        programs = GuideRepository.buildPrograms(np, pl, System.currentTimeMillis(), sched),
                        queueSize = pl.size
                    )
                }
            }
            combine(rowFlows) { it.toList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Metadata for the guide item under the cursor (looked up from the prefetched map). */
    val guideMovieInfo: StateFlow<MovieInfo?> = combine(
        _metadataByTitle, _guideRow, _guideCol, guideChannels
    ) { map, row, col, channels ->
        val title = channels.getOrNull(row)?.programs?.getOrNull(col)?.title ?: return@combine null
        map[title]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Formatted display name for a raw stream title, using prefetched metadata if present. */
    fun displayName(rawTitle: String): String =
        displayTitle(rawTitle, _metadataByTitle.value[rawTitle])


    val metadataOverlayState: StateFlow<MetadataOverlayState> = combine(
        socketClient.nowPlaying,
        socketClient.upNext,
        socketClient.playlist,
        dataScraper.scheduleItems,
        dataScraper.queueScheduleItems,
        dataScraper.redditScheduleTitle,
        dataScraper.redditScheduleText,
        dataScraper.isRedditFallback,
        socketClient.userCount,
        settings,
        webQueueRepo.items
    ) { args: Array<Any?> ->
        val now = args[0] as? MediaItem
        @Suppress("UNCHECKED_CAST")
        val socketNext = args[1] as? List<MediaItem> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val socketPlaylist = args[2] as? List<MediaItem> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val scheduleNext = args[3] as? List<MediaItem> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val scrapedQueueItems = args[4] as? List<QueueScheduleItem> ?: emptyList()
        val redditTitle = args[5] as? String
        val redditText = args[6] as? String
        val isReddit = args[7] as? Boolean ?: false
        val users = args[8] as? Int ?: 0
        val cfg = args[9] as? AppSettings ?: AppSettings()
        @Suppress("UNCHECKED_CAST")
        val webQueue = if (cfg.roomName == "Channel-Z") (args[10] as? List<WebQueueItem> ?: emptyList()) else emptyList()

        // Rangfolge: die per Socket gelieferte Raum-Playlist ist die tatsaechliche Warteschlange
        // und hat Vorrang. Der Reddit-EPG ist nur ein Notbehelf mit geschaetzten Laufzeiten
        // (pauschal 90 Minuten) und ab "jetzt" hochgerechneten Startzeiten — vorher hat er die
        // echten Daten 15 Sekunden nach dem Start ueberschrieben.
        // Channel-Z keeps its queue in kryten-webqueue rather than the CyTube playlist; when the
        // room reports nothing, use the web queue (it carries the server's own start estimates).
        val socketCandidates = when {
            socketNext.isNotEmpty() -> socketNext
            socketPlaylist.isNotEmpty() -> socketPlaylist
            else -> webQueue.map { it.media }
        }
        val socketQueue = buildQueueScheduleFromSocket(now, socketCandidates)

        val finalNext = when {
            socketNext.isNotEmpty() -> socketNext
            scheduleNext.isNotEmpty() -> scheduleNext
            else -> scrapedQueueItems.map { q ->
                MediaItem(
                    id = q.mediaId,
                    title = q.title,
                    durationSeconds = q.durationSeconds.toDouble()
                )
            }
        }

        val finalQueueItems = if (socketQueue.isNotEmpty()) socketQueue else scrapedQueueItems

        MetadataOverlayState(
            nowPlaying = now,
            upNext = finalNext.take(4),
            queueItems = finalQueueItems,
            channelName = cfg.roomName,
            userCount = users,
            isLoading = now == null,
            redditScheduleTitle = redditTitle,
            redditScheduleText = redditText,
            isRedditFallback = isReddit && socketQueue.isEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MetadataOverlayState()
    )

    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val isBuffering: StateFlow<Boolean> = playerManager.isBuffering
    val playerErrorMessage: StateFlow<String?> = playerManager.playerError
    val isMuted: StateFlow<Boolean> = playerManager.isMuted

    private val _isMetadataVisible = MutableStateFlow(false)
    val isMetadataVisible: StateFlow<Boolean> = _isMetadataVisible.asStateFlow()

    private val _isUpNextVisible = MutableStateFlow(false)
    val isUpNextVisible: StateFlow<Boolean> = _isUpNextVisible.asStateFlow()

    private val _isRemoteHintsVisible = MutableStateFlow(true)
    val isRemoteHintsVisible: StateFlow<Boolean> = _isRemoteHintsVisible.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)

    // Left navigation rail (Nuvio-style). Driven by the Activity's key dispatcher.
    private val _isNavRailOpen = MutableStateFlow(false)
    val isNavRailOpen: StateFlow<Boolean> = _isNavRailOpen.asStateFlow()
    private val _navRailIndex = MutableStateFlow(0)
    val navRailIndex: StateFlow<Int> = _navRailIndex.asStateFlow()
    private var navRailDismissJob: Job? = null
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    // Unterseite der Einstellungen. Liegt im ViewModel, damit die Zurueck-Taste sie kennt und
    // erst die Unterseite schliesst, statt gleich das ganze Menue.
    private val _settingsPage = MutableStateFlow(SettingsPage.MAIN)
    val settingsPage: StateFlow<SettingsPage> = _settingsPage.asStateFlow()

    private val _showExitDialog = MutableStateFlow(false)
    val showExitDialog: StateFlow<Boolean> = _showExitDialog.asStateFlow()

    private var metadataDismissJob: Job? = null
    private var upNextDismissJob: Job? = null
    private var remoteHintsDismissJob: Job? = null

    init {
        // Keep guide scouts in step with whichever room is being watched.
        viewModelScope.launch {
            settings.map { it.roomName }.distinctUntilChanged().collect { guideRepo.setActiveRoom(it, settingsRepo.chatCredentials()) }
        }

        // Background metadata prefetch for the whole queue.
        startMetadataPrefetch()
        // Re-run the prefetch promptly when the set of queued titles changes.
        viewModelScope.launch {
            guideChannels
                .map { chs -> chs.flatMap { it.programs.map { p -> p.title } }.toSet() }
                .distinctUntilChanged()
                .collect { runCatching { prefetchAllMetadata() } }
        }
        connectSocket()

        if (settings.value.customStreamUrl.isNotBlank()) {
            playerManager.loadMedia(null, settings.value.customStreamUrl)
        }

        // Listen for media changes from CyTube to trigger 5-second metadata overlay & load stream into player
        viewModelScope.launch {
            socketClient.mediaChangedEvent.collect { mediaItem ->
                showMetadataOverlay()
                playerManager.loadMedia(mediaItem, settings.value.customStreamUrl)
                loadMovieInfo(mediaItem.title, mediaItem)
            }
        }

        // Listen for media sync updates (time drift correction & play/pause synchronization from CyTube)
        viewModelScope.launch {
            socketClient.mediaSyncEvent.collect { sync ->
                if (nowPlaying.value?.isWebStream != true) {
                    playerManager.syncPosition(sync.currentTimeSeconds, sync.paused)
                }
            }
        }

        // Zugangsdaten erst wegschreiben, wenn der Kanal sie bestaetigt hat — sonst
        // wuerde sich ein vertipptes Passwort festbrennen und bei jedem Start still
        // scheitern. Die stille Wiederaufnahme nach Reconnects geht nicht durch
        // login(), braucht hier also nichts zu speichern (steht laengst fest).
        viewModelScope.launch {
            socketClient.loginState.collect { state ->
                if (state is LoginState.LoggedIn) {
                    pendingCredentials?.let { (name, pw) ->
                        settingsRepo.saveChatCredentials(name, pw)
                        _savedChatUsername.value = name
                        // The guide's background connections should see the same playlist.
                        guideRepo.reconnectWithCredentials(name to pw)
                    }
                    pendingCredentials = null
                }
            }
        }

        // Initial auto-hide timer for Remote Hints (6s on startup)
        scheduleRemoteHintsHide(6000L)

        // Untertitel-Einstellung an ExoPlayer weitergeben
        playerManager.setSubtitlesEnabled(settings.value.subtitlesEnabled)
        viewModelScope.launch {
            settings.collect { s ->
                playerManager.setSubtitlesEnabled(s.subtitlesEnabled)
            }
        }
    }

    fun getExoPlayer(): ExoPlayer? = playerManager.getPlayer()

    /**
     * Holt die Angaben zum laufenden Film nach. Laeuft nebenher: kommt nichts zurueck,
     * bleibt die Anzeige einfach so, wie sie ohne diese Daten aussieht.
     */
    private fun loadMovieInfo(rawTitle: String, media: MediaItem? = null) {
        movieInfoJob?.cancel()
        _movieInfo.value = null
        _isTriviaVisible.value = false
        if (!settings.value.movieInfoEnabled || rawTitle.isBlank()) return

        movieInfoJob = viewModelScope.launch {
            // Bei YouTube zuerst dort nachfragen: Trailer und Bumper stehen in keiner
            // Filmdatenbank, haben aber auf YouTube Titel, Kanal und Vorschaubild.
            val fromYouTube = if (media?.type?.lowercase() == "yt") {
                movieInfoRepo.lookupYouTube(extractYouTubeId(media.id))
            } else null

            val info = movieInfoRepo.lookup(rawTitle, useImdb = settings.value.imdbEnabled)
                ?: fromYouTube
            if (info != null) {
                Log.d(TAG, "Filminfo gefunden: ${info.title} (${info.year}) imdb=${info.imdbId}")
                _movieInfo.value = info
            }
        }
    }

    fun toggleTrivia() {
        if (_isTriviaVisible.value) {
            _isTriviaVisible.value = false
            return
        }
        val info = _movieInfo.value ?: return
        _isTriviaVisible.value = true
        if (info.trivia.isNotEmpty() || info.imdbId == null || !settings.value.imdbEnabled) return

        viewModelScope.launch {
            _isTriviaLoading.value = true
            val items = movieInfoRepo.loadTrivia(info.imdbId)
            _isTriviaLoading.value = false
            _movieInfo.value = _movieInfo.value?.copy(trivia = items)
        }
    }

    fun hideTrivia() {
        _isTriviaVisible.value = false
    }

    fun toggleMovieInfo() {
        settingsRepo.updateSettings { it.copy(movieInfoEnabled = !it.movieInfoEnabled) }
        if (!settings.value.movieInfoEnabled) {
            movieInfoJob?.cancel()
            _movieInfo.value = null
        } else {
            nowPlaying.value?.title?.let { loadMovieInfo(it) }
        }
    }

    fun toggleImdb() {
        settingsRepo.updateSettings { it.copy(imdbEnabled = !it.imdbEnabled) }
        nowPlaying.value?.title?.let { loadMovieInfo(it) }
    }

    private fun connectSocket() {
        // Gespeichertes Chat-Konto (Full-Ausgabe) mitgeben: nach dem Beitritt meldet
        // sich die Sitzung selbst an, Gaeste wie Konten. Ohne Gespeichertes ist es null
        // und der Socket bleibt abgemeldet.
        socketClient.connect(settings.value.roomName, settingsRepo.chatCredentials())
    }

    /** Cycle to the next known CyTube room and reconnect everything to it. */
    fun switchToNextRoom() {
        val current = settings.value.roomName
        val idx = KnownChannels.indexOfFirst { it.room == current }
        val next = KnownChannels[(idx + 1) % KnownChannels.size].room
        switchRoom(next)
    }

    fun switchRoom(room: String) {
        if (room == settings.value.roomName) return
        Log.d(TAG, "Switching room -> $room")
        settingsRepo.updateSettings { it.copy(roomName = room) }
        movieInfoJob?.cancel()
        _movieInfo.value = null
        hideTrivia()
        hideUpNext()
        hideMetadataOverlay()
        socketClient.switchRoom(room, settingsRepo.chatCredentials())
    }

    fun retryConnection() {
        Log.d(TAG, "Retrying CyTube Socket connection to room: ${settings.value.roomName}")
        socketClient.disconnect()
        connectSocket()
    }

    fun showMetadataOverlay() {
        _isMetadataVisible.value = true
        metadataDismissJob?.cancel()
        metadataDismissJob = viewModelScope.launch {
            delay(5000L)
            _isMetadataVisible.value = false
        }
    }

    fun hideMetadataOverlay() {
        metadataDismissJob?.cancel()
        _isMetadataVisible.value = false
    }

    fun toggleUpNext() {
        if (_isUpNextVisible.value) {
            hideUpNext()
        } else {
            showUpNext()
        }
    }

    fun showUpNext() {
        hideMetadataOverlay()
        _isUpNextVisible.value = true
        upNextDismissJob?.cancel()
        upNextDismissJob = viewModelScope.launch {
            delay(15000L)
            _isUpNextVisible.value = false
        }
    }

    fun hideUpNext() {
        upNextDismissJob?.cancel()
        _isUpNextVisible.value = false
    }

    fun onRemoteActivity() {
        _isRemoteHintsVisible.value = true
        scheduleRemoteHintsHide(5000L)
    }

    private fun scheduleRemoteHintsHide(delayMillis: Long) {
        remoteHintsDismissJob?.cancel()
        remoteHintsDismissJob = viewModelScope.launch {
            delay(delayMillis)
            _isRemoteHintsVisible.value = false
        }
    }

    fun toggleMute() {
        playerManager.toggleMute()
    }

    // ------------------------------------------------------------------ Chat (nur Full)

    private val _isUserListVisible = MutableStateFlow(false)
    val isUserListVisible: StateFlow<Boolean> = _isUserListVisible.asStateFlow()

    private val _isComposerOpen = MutableStateFlow(false)
    val isComposerOpen: StateFlow<Boolean> = _isComposerOpen.asStateFlow()

    private val _chatLayout = MutableStateFlow(ChatLayout.SUBTITLE)
    val chatLayout: StateFlow<ChatLayout> = _chatLayout.asStateFlow()

    /** Ansicht vor dem Wechsel in die Vollbild-Chat-Ansicht, fuer den Weg zurueck. */
    private var layoutBeforeChatOnly: ChatLayout = ChatLayout.SUBTITLE

    /** Zugang, der gerade geprueft wird — erst nach Bestaetigung des Kanals persistiert. */
    private var pendingCredentials: Pair<String, String>? = null

    /** Gespeicherter Kontoname fuer die Anzeige; das Passwort bleibt im Repository. */
    private val _savedChatUsername = MutableStateFlow(settingsRepo.chatCredentials()?.first.orEmpty())
    val savedChatUsername: StateFlow<String> = _savedChatUsername.asStateFlow()

    fun login(username: String, password: String) {
        pendingCredentials = username to password
        socketClient.login(username, password)
    }

    fun logout() {
        pendingCredentials = null
        settingsRepo.clearChatCredentials()
        _savedChatUsername.value = ""
        socketClient.logout()
        guideRepo.reconnectWithCredentials(null)
    }

    fun sendChat(message: String): Boolean = socketClient.sendChat(message)

    fun openComposer() {
        hideMetadataOverlay()
        hideUpNext()
        _isComposerOpen.value = true
    }

    fun closeComposer() {
        _isComposerOpen.value = false
    }

    fun toggleUserList() {
        _isUserListVisible.value = !_isUserListVisible.value
    }

    fun hideUserList() {
        _isUserListVisible.value = false
    }

    /** Reihum durch die Chat-Ansichten, wie ein Druck auf die Layout-Taste. */
    fun cycleChatLayout() {
        val all = ChatLayout.entries
        applyChatLayout(all[(all.indexOf(_chatLayout.value) + 1) % all.size])
    }

    /**
     * Vollbild-Chat fuer Handy und Tablet: hinein wechselt in die Nur-Chat-Ansicht,
     * zurueck in die Ansicht, die vorher aktiv war. Der Knopf sitzt in der
     * Touch-Bedienleiste, weil es dort keine Layout-Taste gibt.
     */
    fun toggleFullChatMode() {
        if (_chatLayout.value == ChatLayout.CHAT_ONLY) {
            applyChatLayout(layoutBeforeChatOnly)
        } else {
            layoutBeforeChatOnly = _chatLayout.value
            applyChatLayout(ChatLayout.CHAT_ONLY)
        }
    }

    private fun applyChatLayout(layout: ChatLayout) {
        _chatLayout.value = layout
        // In der Nur-Chat-Ansicht laeuft kein Video: das Geraet wird zum Chat-Fenster.
        if (layout == ChatLayout.CHAT_ONLY) {
            playerManager.pause()
            playerManager.setMuted(true)
        } else if (playerManager.isMuted.value) {
            playerManager.setMuted(false)
            playerManager.play()
        }
    }

    fun toggleChat() {
        settingsRepo.toggleChat()
    }

    fun updateChatOpacity(opacity: Float) {
        settingsRepo.updateSettings { it.copy(chatBackgroundOpacity = opacity) }
    }

    fun updateChatFontSize(fontSizeSp: Int) {
        settingsRepo.updateSettings { it.copy(chatFontSizeSp = fontSizeSp) }
    }

    fun updateChatMaxLines(lines: Int) {
        settingsRepo.updateSettings { it.copy(chatMaxLines = lines.coerceIn(1, 3)) }
    }

    fun toggleClockFormat() {
        settingsRepo.updateSettings { it.copy(use24HourClock = !it.use24HourClock) }
    }

    fun toggleSubtitles() {
        settingsRepo.toggleSubtitles()
    }

    fun updateLanguage(languageCode: String) {
        settingsRepo.updateSettings { it.copy(languageCode = languageCode) }
    }

    fun updateChatAutoHide(seconds: Int) {
        settingsRepo.updateChatAutoHide(seconds)
    }

    fun updateChatTheme(theme: String) {
        settingsRepo.updateChatTheme(theme)
    }

    /**
     * Farbthema umschalten. Die Farben werden hier gesetzt und nicht waehrend des Zeichnens,
     * sonst braeuchte jeder Wechsel einen zusaetzlichen Zeichendurchlauf.
     */
    fun updateAppTheme(id: String) {
        settingsRepo.updateAppTheme(id)
        applyPalette(settingsRepo.settings.value.appTheme)
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    fun openSettings() {
        hideMetadataOverlay()
        hideUpNext()
        _settingsPage.value = SettingsPage.MAIN
        _isSettingsOpen.value = true
    }

    fun closeSettings() {
        _isSettingsOpen.value = false
        _settingsPage.value = SettingsPage.MAIN
    }

    fun openSettingsPage(page: SettingsPage) {
        _settingsPage.value = page
    }

    fun toggleSettings() {
        if (_isSettingsOpen.value) {
            closeSettings()
        } else {
            openSettings()
        }
    }

    fun openGuide() {
        closeNavRail()
        hideMetadataOverlay()
        hideUpNext()
        hideTrivia()
        val rows = guideChannels.value
        _guideRow.value = rows.indexOfFirst { it.isActive }.coerceAtLeast(0)
        _guideCol.value = 0
        _guideScrolledBack.value = false
        _isGuideOpen.value = true
    }

    fun closeGuide() {
        _isGuideOpen.value = false
    }

    fun guideMove(dRow: Int, dCol: Int) {
        val rows = guideChannels.value
        if (rows.isEmpty()) return
        val row = (_guideRow.value + dRow).coerceIn(0, rows.size - 1)
        val programs = rows[row].programs
        val col = if (programs.isEmpty()) 0 else (_guideCol.value + dCol).coerceIn(0, programs.size - 1)
        // LEFT while already on the first (current) block = "show me when it started".
        _guideScrolledBack.value = dCol < 0 && _guideCol.value == 0 && col == 0 && dRow == 0
        _guideRow.value = row
        _guideCol.value = col
    }

    private fun cleanGuideTitle(raw: String): String =
        raw.replace(Regex("\\[[^\\]]*]"), " ")              // [1080p], [Remastered]
            .replace(Regex("\\((?!\\d{4}\\))[^)]*\\)"), " ")   // (Director's Cut) but keep (1985)
            .replace(Regex("(?i)\\b(1080p|720p|480p|2160p|4k|x264|x265|h264|h265|bluray|web-?dl|hdtv|dvdrip|brrip|remux)\\b"), " ")
            .replace(Regex("(?i)\\.(mp4|mkv|avi|mov|webm)$"), " ")
            .replace('.', ' ').replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    // Every 20 minutes (and on open), resolve metadata for everything currently queued across
    // all channels, in the background. Skips very short clips (< 5 min) and anything already
    // resolved this session.
    private fun startMetadataPrefetch() {
        if (metadataPrefetchJob?.isActive == true) return
        metadataPrefetchJob = viewModelScope.launch {
            while (isActive) {
                runCatching { prefetchAllMetadata() }
                    .onFailure { Log.w(TAG, "metadata prefetch failed", it) }
                delay(METADATA_REFRESH_MS)
            }
        }
    }

    private suspend fun prefetchAllMetadata() {
        if (!settings.value.movieInfoEnabled) return
        val useImdb = settings.value.imdbEnabled

        // Gather every distinct queued item, carrying its duration and media identity.
        data class Pending(val title: String, val durationSec: Double, val type: String, val id: String)
        val pending = LinkedHashMap<String, Pending>()
        for (ch in guideChannels.value) {
            for (p in ch.programs) {
                if (p.title.isBlank() || pending.containsKey(p.title)) continue
                val durSec = (p.durationMs / 1000.0)
                pending[p.title] = Pending(p.title, durSec, p.mediaType, p.mediaId)
            }
        }
        if (pending.isEmpty()) return

        val resolved = _metadataByTitle.value.toMutableMap()
        for ((title, item) in pending) {
            if (metaCache.containsKey(title)) { resolved[title] = metaCache[title]; continue }
            // #7: don't spend a lookup on short clips (bumpers, trailers, idents).
            if (item.durationSec in 0.1..MIN_METADATA_SECONDS) {
                metaCache[title] = null; resolved[title] = null; continue
            }
            val info = resolveOne(title, item.type, item.id, useImdb)
            metaCache[title] = info
            resolved[title] = info
            _metadataByTitle.value = resolved.toMap()   // publish incrementally so UI fills in
            delay(120L)                                  // be gentle on the metadata hosts
        }
    }

    private suspend fun resolveOne(title: String, type: String, id: String, useImdb: Boolean): MovieInfo? {
        var info = runCatching { movieInfoRepo.lookup(title, useImdb = useImdb) }.getOrNull()
        if (info == null) {
            val clean = cleanGuideTitle(title)
            if (clean.isNotBlank() && clean != title) {
                info = runCatching { movieInfoRepo.lookup(clean, useImdb = useImdb) }.getOrNull()
            }
        }
        if (info == null && type.lowercase() == "yt" && id.isNotBlank()) {
            info = runCatching { movieInfoRepo.lookupYouTube(extractYouTubeId(id)) }.getOrNull()
        }
        return info
    }

    fun guideSelect(row: Int = _guideRow.value, col: Int = _guideCol.value) {
        val ch = guideChannels.value.getOrNull(row) ?: return
        _guideRow.value = row
        _guideCol.value = col
        closeGuide()
        if (!ch.isActive) switchRoom(ch.room)
    }

    fun openNavRail() {
        hideMetadataOverlay()
        hideUpNext()
        hideTrivia()
        _isNavRailOpen.value = true
        scheduleNavRailHide()
    }

    fun closeNavRail() {
        navRailDismissJob?.cancel()
        _isNavRailOpen.value = false
    }

    fun toggleNavRail() {
        if (_isNavRailOpen.value) closeNavRail() else openNavRail()
    }

    fun navRailMove(delta: Int) {
        val n = NavItem.all.size
        _navRailIndex.value = ((_navRailIndex.value + delta) % n + n) % n
        scheduleNavRailHide()
    }

    fun navRailSelect() {
        navRailActivate(NavItem.all[_navRailIndex.value])
    }

    fun navRailActivate(item: NavItem) {
        _navRailIndex.value = NavItem.all.indexOf(item)
        closeNavRail()
        when (item) {
            NavItem.NOW_PLAYING -> showMetadataOverlay()
            NavItem.SCHEDULE -> showUpNext()
            NavItem.CHAT -> toggleChat()
            NavItem.GUIDE -> openGuide()
            NavItem.SETTINGS -> openSettings()
        }
    }

    private fun scheduleNavRailHide() {
        navRailDismissJob?.cancel()
        navRailDismissJob = viewModelScope.launch {
            delay(8000L)
            _isNavRailOpen.value = false
        }
    }

    fun promptExitDialog() {
        hideMetadataOverlay()
        hideUpNext()
        _showExitDialog.value = true
    }

    // Press BACK twice within a short window to exit (instead of a confirmation dialog).
    private val _showExitHint = MutableStateFlow(false)
    val showExitHint: StateFlow<Boolean> = _showExitHint.asStateFlow()
    private var lastBackPressMs = 0L
    private var exitHintJob: Job? = null

    /** @return true if the app should now exit. */
    fun backPressedOnIdleScreen(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < EXIT_WINDOW_MS) {
            _showExitHint.value = false
            return true
        }
        lastBackPressMs = now
        _showExitHint.value = true
        exitHintJob?.cancel()
        exitHintJob = viewModelScope.launch {
            delay(EXIT_WINDOW_MS)
            _showExitHint.value = false
        }
        return false
    }

    fun dismissExitDialog() {
        _showExitDialog.value = false
    }

    fun handleBackPress(): Boolean {
        return when {
            _showExitDialog.value -> {
                dismissExitDialog()
                true
            }
            _isComposerOpen.value -> {
                closeComposer()
                true
            }
            _isUserListVisible.value -> {
                hideUserList()
                true
            }
            _isGuideOpen.value -> {
                closeGuide()
                true
            }
            _isNavRailOpen.value -> {
                // Second BACK: close the menu and let the Activity show the exit hint.
                closeNavRail()
                false
            }
            _isTriviaVisible.value -> {
                hideTrivia()
                true
            }
            _isSettingsOpen.value && _settingsPage.value != SettingsPage.MAIN -> {
                _settingsPage.value = SettingsPage.MAIN
                true
            }
            _isSettingsOpen.value -> {
                closeSettings()
                true
            }
            _isUpNextVisible.value -> {
                hideUpNext()
                true
            }
            _isMetadataVisible.value -> {
                hideMetadataOverlay()
                true
            }
            else -> {
                // First BACK on the bare player opens the menu. If the exit hint is showing
                // (i.e. this is the third BACK), fall through so the Activity exits.
                if (_showExitHint.value) {
                    false
                } else {
                    openNavRail()
                    true
                }
            }
        }
    }

    fun playDemoStream() {
        val demoItem = MediaItem(
            id = "dQw4w9WgXcQ",
            title = "Channel-Z Demo Feed",
            durationSeconds = 212.0,
            type = "yt",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            currentTimeSeconds = 0.0,
            paused = false,
            directUrl = ""
        )
        playerManager.loadMedia(demoItem, "")
    }

    private fun buildQueueScheduleFromSocket(
        nowPlaying: MediaItem?,
        upcomingList: List<MediaItem>
    ): List<QueueScheduleItem> {
        if (upcomingList.isEmpty()) return emptyList()

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val nowMs = System.currentTimeMillis()

        val remainingCurrentSec = if (nowPlaying != null && nowPlaying.durationSeconds > 0) {
            val rem = nowPlaying.durationSeconds - nowPlaying.currentTimeSeconds
            if (rem > 0) rem else 0.0
        } else 0.0

        var accumulatedSec = remainingCurrentSec
        val result = mutableListOf<QueueScheduleItem>()

        for (item in upcomingList) {
            val durationSec = item.durationSeconds.toInt()
            val startMs = nowMs + (accumulatedSec * 1000).toLong()
            val startFormatted = timeFormat.format(Date(startMs))
            val durFormatted = formatDurationSec(durationSec)

            result.add(
                QueueScheduleItem(
                    title = item.title,
                    durationSeconds = durationSec,
                    startTimeFormatted = startFormatted,
                    startTimeMillis = startMs,
                    durationFormatted = durFormatted,
                    mediaId = item.id
                )
            )
            accumulatedSec += if (durationSec > 0) durationSec else 180
        }

        return result
    }

    private fun formatDurationSec(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    override fun onCleared() {
        guideRepo.shutdown()
        super.onCleared()
        socketClient.disconnect()
        dataScraper.stopScraping()
    }
}

private const val EXIT_WINDOW_MS = 2500L
private const val METADATA_REFRESH_MS = 20 * 60 * 1000L
private const val MIN_METADATA_SECONDS = 300.0
