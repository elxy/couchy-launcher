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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork accessors for [AppEntry].
 *
 * An entry points at the WebP files the scan wrote ([AppEntry.art]) and never
 * carries decoded pixels: rasterizing every INSTALLED package meant a dock that
 * shows 8 icons still paid for 25 banners, and every install added another
 * 0.6 MB. The one RAM copy is [AppArtworkCache], shared by the dock, the
 * drawer, the settings lists and the section pickers, and decodes are only
 * started for cards that are actually composed.
 */
@Composable
fun appBanner(app: AppEntry): ImageBitmap? = rememberDecoded(app.art?.bannerFile)

/** 128px icon to draw for [app], when it ships no banner. */
@Composable
fun appIcon(app: AppEntry): ImageBitmap? = rememberDecoded(app.art?.iconFile)

/**
 * Decode [file] once per (path, card) and hold the result across recomposition.
 * The initial value is a synchronous cache peek — after the first pass that is
 * a plain map hit, so a re-scrolled card never flashes empty.
 */
@Composable
private fun rememberDecoded(file: File?): ImageBitmap? {
    if (file == null) return null
    var bitmap by remember(file) { mutableStateOf(AppArtworkCache.get(file)) }
    LaunchedEffect(file) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) { AppArtworkCache.decode(file) }
    }
    return bitmap
}
