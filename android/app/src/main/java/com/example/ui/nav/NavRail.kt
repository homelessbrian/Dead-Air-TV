package com.example.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AccentIceBlue
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.AccentPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.TextMuted

/**
 * Entries of the persistent left navigation rail. Order = order on screen.
 */
enum class NavItem(val icon: ImageVector, val labelRes: Int) {
    NOW_PLAYING(Icons.Default.PlayCircle, R.string.nav_now_playing),
    SCHEDULE(Icons.Default.CalendarMonth, R.string.nav_schedule),
    GUIDE(Icons.Default.GridView, R.string.nav_guide),
    DETAILS(Icons.Default.Info, R.string.nav_details),
    CHAT(Icons.AutoMirrored.Filled.Chat, R.string.nav_chat),
    CHANNEL(Icons.Default.Tv, R.string.nav_channel),
    SETTINGS(Icons.Default.Settings, R.string.nav_settings);

    companion object {
        val all: List<NavItem> = entries
    }
}

/**
 * Nuvio-style left navigation rail.
 *
 * Purely state-driven: the Activity's key dispatcher moves [selectedIndex] and calls select,
 * this composable only renders. D-Pad LEFT slides it in over the player; UP/DOWN move the
 * highlight; OK selects; RIGHT/BACK slide it away.
 */
@Composable
fun NavRail(
    isOpen: Boolean,
    selectedIndex: Int,
    isChatOn: Boolean,
    isLive: Boolean,
    channelLabel: String = "",
    isTv: Boolean,
    onItemClick: (NavItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { -it / 2 },
        exit = fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it / 2 },
        modifier = modifier
    ) {
        val railWidth = if (isTv) 300.dp else 240.dp
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim: solid on the left, fading out to the right so the video stays visible.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(railWidth + 220.dp)
                    .background(
                        Brush.horizontalGradient(
                            0.0f to Color.Black.copy(alpha = 0.94f),
                            0.55f to Color.Black.copy(alpha = 0.78f),
                            1.0f to Color.Transparent
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(railWidth)
                    .padding(start = if (isTv) 40.dp else 20.dp, top = 40.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Brand block
                Column {
                    Text(
                        text = "DEAD AIR",
                        color = PureWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (isLive) StatusLiveGreen else TextMuted, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (isLive) R.string.nav_status_live else R.string.nav_status_offline) +
                                    (if (channelLabel.isNotBlank()) "  ·  $channelLabel" else ""),
                            color = TextMuted,
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp
                        )
                    }
                }

                // Menu items
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NavItem.all.forEachIndexed { index, item ->
                        val label = when (item) {
                            NavItem.CHAT -> stringResource(
                                if (isChatOn) R.string.nav_chat_on else R.string.nav_chat_off
                            )
                            NavItem.CHANNEL -> stringResource(R.string.nav_channel) +
                                    (if (channelLabel.isNotBlank()) " · $channelLabel" else "")
                            else -> stringResource(item.labelRes)
                        }
                        NavRailRow(
                            icon = item.icon,
                            label = label,
                            selected = index == selectedIndex,
                            onClick = { onItemClick(item) }
                        )
                    }
                }

                // Footer hint
                Text(
                    text = stringResource(R.string.nav_footer_hint),
                    color = TextMuted.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun NavRailRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, tween(150), label = "navScale")
    val bg = if (selected) AccentPurple else Color.Transparent
    val fg = if (selected) PureWhite else PureWhite.copy(alpha = 0.72f)

    Row(
        modifier = Modifier
            .scale(scale)
            .width(228.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .background(bg, RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(
                    Brush.horizontalGradient(listOf(AccentPurple, AccentIceBlue.copy(alpha = 0.55f))),
                    RoundedCornerShape(14.dp)
                ) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) PureWhite else AccentLavender,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
    }
}
