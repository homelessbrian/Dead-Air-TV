package com.example

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MediaItem
import com.example.data.model.SubtitleTrack
import com.example.player.VideoPlayerManager
import com.example.player.resolveSubtitleMimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubtitleSupportTest {

    @Test
    fun `resolveSubtitleMimeType correctly infers mime type from content type or extension`() {
        assertEquals(MimeTypes.TEXT_VTT, resolveSubtitleMimeType("https://example.com/subs.vtt", ""))
        assertEquals(MimeTypes.TEXT_VTT, resolveSubtitleMimeType("https://example.com/subs.srt", "text/vtt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, resolveSubtitleMimeType("https://example.com/subs.srt", "application/x-subrip"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, resolveSubtitleMimeType("https://example.com/subs.srt", ""))
        assertEquals(MimeTypes.TEXT_SSA, resolveSubtitleMimeType("https://example.com/subs.ass", ""))
        assertEquals(MimeTypes.APPLICATION_TTML, resolveSubtitleMimeType("https://example.com/subs.ttml", ""))
    }

    @Test
    fun `videoPlayerManager loads media with subtitle tracks and toggles track selection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = VideoPlayerManager(context)

        val tracks = listOf(
            SubtitleTrack(
                url = "https://prod.420grindhouseserver.com/media/original/subtitles/movie-en.srt",
                label = "English",
                language = "en",
                mimeType = "text/vtt",
                isDefault = true
            )
        )

        val testMedia = MediaItem(
            id = "https://prod.420grindhouseserver.com/media/manifest.json",
            title = "Bloody Muscle Body Builder in Hell",
            type = "cm",
            directUrl = "https://prod.420grindhouseserver.com/media/video.mp4",
            subtitleTracks = tracks
        )

        manager.loadMedia(testMedia)
        assertEquals(testMedia, manager.currentMedia.value)
        assertEquals(1, manager.currentMedia.value?.subtitleTracks?.size)

        val player = manager.getPlayer()
        assertNotNull(player)

        // Subtitles enabled by default
        manager.setSubtitlesEnabled(true)
        val enabledParams = player!!.trackSelectionParameters
        assertEquals(false, enabledParams.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

        // Toggle subtitles off
        manager.setSubtitlesEnabled(false)
        val disabledParams = player.trackSelectionParameters
        assertEquals(true, disabledParams.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

        manager.release()
    }
}
