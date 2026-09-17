package com.example.data.guide

import android.util.Log
import com.example.data.model.ConnectionStatus
import com.example.data.model.KnownChannels
import com.example.data.model.MediaItem
import com.example.data.socket.CyTubeSocketClient
import kotlinx.coroutines.CoroutineScope

/** One block on the guide timeline. */
data class GuideProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val isCurrent: Boolean,
    val mediaId: String = "",
    val mediaType: String = ""
) {
    val durationMs: Long get() = endMs - startMs
}

/** One row on the guide. */
data class GuideChannel(
    val room: String,
    val label: String,
    val isActive: Boolean,
    val status: ConnectionStatus,
    val programs: List<GuideProgram>,
    /** Diagnostics: how many items the room's playlist currently holds. */
    val queueSize: Int = 0
)

/**
 * Keeps a lightweight read-only socket ("scout") open to every known room that is NOT the one
 * being watched, so the TV guide can show what's on across all channels. The active room's
 * data comes from the main player socket instead, so no room is joined twice.
 */
class GuideRepository(private val scope: CoroutineScope) {

    private val scouts = mutableMapOf<String, CyTubeSocketClient>()
    private var activeRoom: String? = null
    private var credentials: Pair<String, String>? = null

    fun scout(room: String): CyTubeSocketClient =
        scouts.getOrPut(room) { CyTubeSocketClient(scope) }

    /** Connect scouts for every room except [room]; disconnect the scout for [room] itself. */
    fun setActiveRoom(room: String, savedCredentials: Pair<String, String>? = credentials) {
        if (room == activeRoom && savedCredentials == credentials) return
        activeRoom = room
        credentials = savedCredentials
        KnownChannels.forEach { ch ->
            val client = scout(ch.room)
            if (ch.room == room) {
                client.disconnect()
            } else if (client.connectionStatus.value == ConnectionStatus.IDLE ||
                client.connectionStatus.value == ConnectionStatus.OFFLINE
            ) {
                Log.d(TAG, "Guide scout connecting to ${ch.room}")
                client.connect(ch.room, credentials)
            }
        }
    }

    /** Drop and re-open every scout with new credentials (after login or logout). */
    fun reconnectWithCredentials(savedCredentials: Pair<String, String>?) {
        credentials = savedCredentials
        val room = activeRoom ?: return
        KnownChannels.filter { it.room != room }.forEach { ch ->
            Log.d(TAG, "Guide scout reconnecting to ${ch.room} (login=${savedCredentials != null})")
            scout(ch.room).connect(ch.room, credentials)
        }
    }

    fun shutdown() {
        scouts.values.forEach { it.disconnect() }
        scouts.clear()
    }

    companion object {
        private const val TAG = "GuideRepository"
        private const val FALLBACK_DURATION_SEC = 180.0
        private const val MAX_PROGRAMS = 40

        /**
         * Turn "what's playing + the playlist" into a timeline anchored at [nowMs].
         * The current item starts in the past by however far it has already played.
         */
        fun buildPrograms(
            nowPlaying: MediaItem?,
            playlist: List<MediaItem>,
            nowMs: Long,
            scheduleFallback: List<MediaItem> = emptyList()
        ): List<GuideProgram> {
            val out = mutableListOf<GuideProgram>()
            var cursor = nowMs

            if (nowPlaying != null) {
                val dur = if (nowPlaying.durationSeconds > 0) nowPlaying.durationSeconds else FALLBACK_DURATION_SEC
                val start = nowMs - (nowPlaying.currentTimeSeconds.coerceAtLeast(0.0) * 1000).toLong()
                val end = start + (dur * 1000).toLong()
                out += GuideProgram(nowPlaying.title, start, maxOf(end, nowMs + 30_000L), true, nowPlaying.id, nowPlaying.type)
                cursor = out.last().endMs
            }

            var upcoming = remainingAfter(nowPlaying, playlist)
            if (upcoming.isEmpty() && scheduleFallback.isNotEmpty()) {
                // Room exposes no queue (e.g. a bot that adds one item at a time): use the
                // channel's published schedule instead, minus whatever is playing right now.
                val curTitle = nowPlaying?.title?.trim()?.lowercase()
                upcoming = scheduleFallback.filter { it.title.trim().lowercase() != curTitle }
            }
            for (item in upcoming.take(MAX_PROGRAMS)) {
                val dur = if (item.durationSeconds > 0) item.durationSeconds else FALLBACK_DURATION_SEC
                val end = cursor + (dur * 1000).toLong()
                out += GuideProgram(item.title, cursor, end, false, item.id, item.type)
                cursor = end
            }
            return out
        }

        private fun remainingAfter(current: MediaItem?, playlist: List<MediaItem>): List<MediaItem> {
            if (playlist.isEmpty()) return emptyList()
            if (current == null) return playlist
            val idx = playlist.indexOfFirst {
                (it.id.isNotBlank() && it.id == current.id) ||
                    (it.title.isNotBlank() && it.title == current.title)
            }
            // CyTube playlists loop, so after the last item comes the first one again.
            return if (idx != -1) playlist.drop(idx + 1) + playlist.take(idx)
            else playlist.filter { it.id != current.id && it.title != current.title }
        }
    }
}
