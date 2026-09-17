package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.example.data.model.MovieInfo
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.AccentPurple
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceDark

/** Preferred poster source: btttr.cc by IMDb id, then whatever the metadata lookup found. */
fun posterCandidates(info: MovieInfo?, extra: String? = null): List<String> {
    val out = mutableListOf<String>()
    info?.imdbId?.takeIf { it.isNotBlank() }?.let { out += "https://btttr.cc/poster/imdb/poster-default/$it.jpg?tag=none" }
    info?.posterUrl?.takeIf { it.isNotBlank() }?.let { out += it }
    extra?.takeIf { it.isNotBlank() }?.let { out += it }
    return out.distinct()
}

/**
 * Poster with an app-themed stand-in while loading (and if nothing loads). Tries each URL in
 * [candidates] in order, moving to the next on error.
 */
@Composable
fun MoviePoster(
    candidates: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    var index by remember(candidates) { mutableStateOf(0) }
    var loaded by remember(candidates) { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!loaded) PosterPlaceholder(Modifier.fillMaxSize(), showLabel)
        val url = candidates.getOrNull(index)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> loaded = true
                        is AsyncImagePainter.State.Error -> if (index < candidates.size - 1) index++
                        else -> Unit
                    }
                }
            )
        }
    }
}

/** Dead Air house style: dark gradient, purple accent, a film icon. */
@Composable
fun PosterPlaceholder(modifier: Modifier = Modifier, showLabel: Boolean = true) {
    Column(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(SurfaceCard, SurfaceDark, AccentPurple.copy(alpha = 0.35f)))
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Movie,
            contentDescription = null,
            tint = AccentLavender.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp)
        )
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "DEAD AIR",
                color = AccentLavender.copy(alpha = 0.7f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}
