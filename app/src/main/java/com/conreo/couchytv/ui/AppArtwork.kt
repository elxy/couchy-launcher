package com.conreo.couchytv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.conreo.couchytv.data.AppArtworkCache
import com.conreo.couchytv.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork accessors for [AppEntry].
 *
 * An entry never carries decoded pixels: [AppEntry.art] names the WebP files
 * the scan already wrote, and a card decodes only what it is about to draw. The
 * result goes into [AppArtworkCache] — one budgeted LRU shared by the grid, the
 * dock, the settings lists and the drawer, so the same picture is never held
 * twice and scrolling back to a card usually finds it still resident.
 */
@Composable
fun appBanner(app: AppEntry): ImageBitmap? {
    val file = app.art?.bannerFile ?: return null
    return rememberDecoded(file)
}

/** 128px icon to draw for [app], or null when the app ships a banner. */
@Composable
fun appIcon(app: AppEntry): ImageBitmap? {
    val file = app.art?.iconFile ?: return null
    return rememberDecoded(file)
}

/**
 * Decode [file] once per composition scope and hold the result across
 * recomposition. The initial value is a synchronous cache peek — after the
 * first pass that is a plain LRU hit, so a re-scrolled card never flashes empty.
 */
@Composable
private fun rememberDecoded(file: java.io.File): ImageBitmap? {
    var bitmap by remember(file) { mutableStateOf(AppArtworkCache.get(file)) }
    LaunchedEffect(file) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) { AppArtworkCache.decode(file) }
    }
    return bitmap
}
