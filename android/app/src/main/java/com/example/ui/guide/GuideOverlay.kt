package com.example.ui.guide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.R
import com.example.data.guide.GuideChannel
import com.example.data.guide.GuideProgram
import com.example.data.model.ConnectionStatus
import com.example.data.model.MovieInfo
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.example.ui.components.formatClock
import com.example.ui.theme.AccentIceBlue
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.AccentPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import kotlinx.coroutines.delay

private const val WINDOW_MINUTES = 150L          // 2.5 h visible
private const val SLOT_MINUTES = 30L
private const val MS_PER_MIN = 60_000L

/**
 * Full-screen programme grid: channels down the left, time across the top.
 * Purely state-driven; the Activity moves [focusRow]/[focusCol] with the D-pad.
 */
@Composable
fun GuideOverlay(
    isVisible: Boolean,
    channels: List<GuideChannel>,
    focusRow: Int,
    focusCol: Int,
    scrolledBack: Boolean = false,
    movieInfo: MovieInfo? = null,
    lookupState: String = "",
    use24HourClock: Boolean,
    isTv: Boolean,
    onProgramClick: (row: Int, col: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = isVisible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        // "Now" ticks every 30 s so the red line and the current block keep moving.
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(isVisible) {
            while (isVisible) {
                nowMs = System.currentTimeMillis()
                delay(30_000L)
            }
        }

        val focusedChannel = channels.getOrNull(focusRow)
        val focusedProgram = focusedChannel?.programs?.getOrNull(focusCol)

        // Visible window start, snapped to the half hour. Anchored at "now" (the red line);
        // it only moves forward to follow a focused future programme, and only moves back
        // when the user explicitly presses LEFT on the current block.
        val nowSlot = floorToSlot(nowMs)
        var windowStartMs by remember { mutableLongStateOf(nowSlot) }
        LaunchedEffect(focusedProgram?.startMs, focusedProgram?.isCurrent, scrolledBack, nowSlot) {
            val p = focusedProgram
            val windowLen = WINDOW_MINUTES * MS_PER_MIN
            windowStartMs = when {
                p == null -> nowSlot
                scrolledBack && p.isCurrent -> floorToSlot(p.startMs)
                p.isCurrent -> nowSlot
                p.startMs >= windowStartMs + windowLen - 20 * MS_PER_MIN -> floorToSlot(p.startMs - SLOT_MINUTES * MS_PER_MIN)
                p.startMs < windowStartMs -> maxOf(nowSlot, floorToSlot(p.startMs))
                else -> windowStartMs
            }
        }

        val pad = if (isTv) 48.dp else 16.dp
        val channelColWidth = if (isTv) 200.dp else 130.dp
        val rowHeight = if (isTv) 64.dp else 52.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(horizontal = pad, vertical = if (isTv) 36.dp else 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.guide_title),
                        color = PureWhite,
                        fontSize = if (isTv) 22.sp else 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = formatClock(nowMs, use24HourClock),
                        color = AccentLavender,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(14.dp))

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val timelineWidth: Dp = maxWidth - channelColWidth
                    val dpPerMin: Dp = timelineWidth / WINDOW_MINUTES.toFloat()
                    fun xOf(ms: Long): Dp = dpPerMin * ((ms - windowStartMs).toFloat() / MS_PER_MIN.toFloat())

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Time ruler
                        Row(modifier = Modifier.fillMaxWidth().height(26.dp)) {
                            Spacer(Modifier.width(channelColWidth))
                            Box(modifier = Modifier.width(timelineWidth).fillMaxHeight().clipToBounds()) {
                                var t = windowStartMs
                                val end = windowStartMs + WINDOW_MINUTES * MS_PER_MIN
                                while (t < end) {
                                    Text(
                                        text = formatClock(t, use24HourClock),
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.offset(x = xOf(t) + 6.dp)
                                    )
                                    Box(
                                        Modifier
                                            .offset(x = xOf(t))
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(Color.White.copy(alpha = 0.12f))
                                    )
                                    t += SLOT_MINUTES * MS_PER_MIN
                                }
                            }
                        }

                        // Channel rows
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                channels.forEachIndexed { row, ch ->
                                    val rowFocused = row == focusRow
                                    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                                        ChannelCell(ch, rowFocused, Modifier.width(channelColWidth - 8.dp).fillMaxHeight())
                                        Spacer(Modifier.width(8.dp))
                                        Box(modifier = Modifier.width(timelineWidth).fillMaxHeight().clipToBounds()) {
                                            if (ch.programs.isEmpty()) {
                                                EmptyCell(ch.status, Modifier.fillMaxSize())
                                            }
                                            ch.programs.forEachIndexed { col, p ->
                                                val visStart = maxOf(p.startMs, windowStartMs)
                                                val visEnd = minOf(p.endMs, windowStartMs + WINDOW_MINUTES * MS_PER_MIN)
                                                if (visEnd <= visStart) return@forEachIndexed
                                                ProgramCell(
                                                    program = p,
                                                    focused = rowFocused && col == focusCol,
                                                    clippedLeft = p.startMs < windowStartMs,
                                                    modifier = Modifier
                                                        .offset(x = xOf(visStart))
                                                        .width(xOf(visEnd) - xOf(visStart))
                                                        .fillMaxHeight(),
                                                    onClick = { onProgramClick(row, col) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // "Now" line
                            if (nowMs >= windowStartMs && nowMs <= windowStartMs + WINDOW_MINUTES * MS_PER_MIN) {
                                Box(
                                    Modifier
                                        .offset(x = channelColWidth + xOf(nowMs))
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(Color(0xFFFF3030))
                                )
                            }
                        }
                    }
                }

                // Detail strip
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isTv) 118.dp else 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (focusedProgram != null && focusedChannel != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val poster = movieInfo?.posterUrl
                            if (!poster.isNullOrBlank()) {
                                AsyncImage(
                                    model = poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(if (isTv) 64.dp else 52.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                val displayTitle = movieInfo?.title?.takeIf { it.isNotBlank() } ?: focusedProgram.title
                                val year = movieInfo?.year?.let { "  ($it)" } ?: ""
                                Text(
                                    text = displayTitle + year,
                                    color = PureWhite,
                                    fontSize = if (isTv) 18.sp else 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                val range = formatClock(focusedProgram.startMs, use24HourClock) + " – " +
                                    formatClock(focusedProgram.endMs, use24HourClock)
                                val dur = formatDurationMs(focusedProgram.durationMs)
                                val extras = listOfNotNull(
                                    movieInfo?.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                                    movieInfo?.rating?.let { "★ %.1f".format(it) }
                                ).joinToString("  ·  ")
                                Text(
                                    text = listOf(focusedChannel.label, range, dur, extras)
                                        .filter { it.isNotBlank() }.joinToString("  ·  "),
                                    color = AccentLavender,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val plot = movieInfo?.plot
                                if (!plot.isNullOrBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = plot,
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                run {
                                    // Diagnostics (temporary while we chase the playlist issue).
                                    Spacer(Modifier.height(3.dp))
                                    val media = if (focusedProgram.mediaId.isNotBlank())
                                        "${focusedProgram.mediaType.ifBlank { "?" }}:${focusedProgram.mediaId.take(24)}" else "no media id"
                                    Text(
                                        text = listOf(
                                            if (focusedChannel.isActive) "player socket" else "scout",
                                            focusedChannel.status.name,
                                            "queue ${focusedChannel.queueSize}",
                                            media,
                                            lookupState
                                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                                        color = TextMuted.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = if (focusedChannel.isActive) stringResource(R.string.guide_hint_active)
                                else stringResource(R.string.guide_hint_switch, focusedChannel.label),
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 2
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.guide_footer_hint),
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(ch: GuideChannel, focused: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) SurfaceCard else SurfaceDark.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    when {
                        ch.status == ConnectionStatus.LIVE -> StatusLiveGreen
                        ch.status == ConnectionStatus.RECONNECTING -> TextMuted
                        else -> TextMuted.copy(alpha = 0.5f)
                    },
                    CircleShape
                )
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = ch.label,
                color = PureWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (ch.isActive) {
                Text(
                    text = stringResource(R.string.guide_watching),
                    color = AccentLavender,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun ProgramCell(
    program: GuideProgram,
    focused: Boolean,
    clippedLeft: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        focused -> AccentPurple
        program.isCurrent -> SurfaceCard
        else -> SurfaceDark.copy(alpha = 0.85f)
    }
    Box(
        modifier = modifier
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (focused) Modifier.border(2.dp, AccentIceBlue, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = (if (clippedLeft) "‹ " else "") + program.title,
            color = if (focused || program.isCurrent) PureWhite else PureWhite.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = if (focused || program.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyCell(status: ConnectionStatus, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(
                if (status == ConnectionStatus.LIVE) R.string.guide_no_schedule else R.string.guide_loading
            ),
            color = TextMuted,
            fontSize = 13.sp
        )
    }
}

private fun floorToSlot(ms: Long): Long = ms - ms % (SLOT_MINUTES * MS_PER_MIN)

private fun formatDurationMs(ms: Long): String {
    val totalMin = (ms / MS_PER_MIN).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
