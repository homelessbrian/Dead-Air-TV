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
    val isCurrent: Boolean
) {
    val durationMs: Long get() = endMs - startMs
}

/** One row on the guide. */
data class GuideChannel(
    val room: String,
    val label: String,
    val isActive: Boolean,
    val status: ConnectionStatus,
    val programs: List<GuideProgram>
)

/**
 * Keeps a lightweight read-only socket ("scout") open to every known room that is NOT the one
 * being watched, so the TV guide can show what's on across all channels. The active room's
 * data comes from the main player socket instead, so no room is joined twice.
 */
class GuideRepository(private val scope: CoroutineScope) {

    private val scouts = mutableMapOf<String, CyTubeSocketClient>()
    private var activeRoom: String? = null

    fun scout(room: String): CyTubeSocketClient =
        scouts.getOrPut(room) { CyTubeSocketClient(scope) }

    /** Connect scouts for every room except [room]; disconnect the scout for [room] itself. */
    fun setActiveRoom(room: String) {
        if (room == activeRoom) return
        activeRoom = room
        KnownChannels.forEach { ch ->
            val client = scout(ch.room)
            if (ch.room == room) {
                client.disconnect()
            } else if (client.connectionStatus.value == ConnectionStatus.IDLE ||
                client.connectionStatus.value == ConnectionStatus.OFFLINE
            ) {
                Log.d(TAG, "Guide scout connecting to ${ch.room}")
                client.connect(ch.room)
            }
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
        fun buildPrograms(nowPlaying: MediaItem?, playlist: List<MediaItem>, nowMs: Long): List<GuideProgram> {
            val out = mutableListOf<GuideProgram>()
            var cursor = nowMs

            if (nowPlaying != null) {
                val dur = if (nowPlaying.durationSeconds > 0) nowPlaying.durationSeconds else FALLBACK_DURATION_SEC
                val start = nowMs - (nowPlaying.currentTimeSeconds.coerceAtLeast(0.0) * 1000).toLong()
                val end = start + (dur * 1000).toLong()
                out += GuideProgram(nowPlaying.title, start, maxOf(end, nowMs + 30_000L), isCurrent = true)
                cursor = out.last().endMs
            }

            val upcoming = remainingAfter(nowPlaying, playlist)
            for (item in upcoming.take(MAX_PROGRAMS)) {
                val dur = if (item.durationSeconds > 0) item.durationSeconds else FALLBACK_DURATION_SEC
                val end = cursor + (dur * 1000).toLong()
                out += GuideProgram(item.title, cursor, end, isCurrent = false)
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
            return if (idx != -1) playlist.drop(idx + 1)
            else playlist.filter { it.id != current.id && it.title != current.title }
        }
    }
}
