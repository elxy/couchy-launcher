package com.conreo.couchytv.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.conreo.couchytv.data.AppEntry
import com.conreo.couchytv.data.AppRepository
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork accessors for [AppEntry].
 *
 * A normal scan entry already carries decoded bitmaps ([AppEntry.banner] /
 * [AppEntry.icon]). An entry rebuilt from the on-disk snapshot of the previous
 * scan does not — it points at the same WebP files the scan would have written
 * ([AppRepository.SnapshotArt]). Decoding those up front is exactly the ~2-4s
 * of work the snapshot exists to skip, so instead each card decodes its own
 * artwork only when it is actually composed. The cache is keyed by path, so a
 * card that scrolls away and back does not decode twice, and the real scan
 * replaces these entries within a second or two anyway.
 */
private val artCache = Collections.synchronizedMap(HashMap<String, ImageBitmap>())

private suspend fun decodeCached(file: File, wantBanner: Boolean): ImageBitmap? =
    withContext(Dispatchers.IO) {
        artCache[file.path] ?: run {
            val opts = BitmapFactory.Options().apply {
                // Banners are already at their paint size on disk; icons 128px.
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.path, opts)
                ?.asImageBitmap()
                ?.also { artCache[file.path] = it }
        }
    }

/** Banner to draw for [app], or null when the app ships none. */
@Composable
fun appBanner(app: AppEntry): ImageBitmap? {
    app.banner?.let { return it }
    val art = app.art ?: return null
    val file = art.bannerFile ?: return null
    return rememberDecoded(file, app)
}

/** 128px icon to draw for [app], or null when the app ships a banner. */
@Composable
fun appIcon(app: AppEntry): ImageBitmap? {
    app.icon?.let { return it }
    val art = app.art ?: return null
    val file = art.iconFile ?: return null
    return rememberDecoded(file, app)
}

/**
 * Decode [file] once per (path, entry) and hold the result across recomposition.
 * The initial value is a synchronous cache peek — after the first pass that is a
 * plain map hit, so a re-scrolled card never flashes empty.
 */
@Composable
private fun rememberDecoded(file: File, app: AppEntry): ImageBitmap? {
    val cached = artCache[file.path]
    var bitmap by remember(file, app) { mutableStateOf(cached) }
    LaunchedEffect(file, app) {
        if (bitmap == null) bitmap = decodeCached(file, wantBanner = true)
    }
    return bitmap
}
