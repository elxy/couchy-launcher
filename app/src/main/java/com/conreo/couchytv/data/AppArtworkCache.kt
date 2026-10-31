package com.conreo.couchytv.data

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Decoded app artwork (banners and 128px icons), keyed by the cache file it was
 * decoded from.
 *
 * This is the launcher's only RAM copy of a tile picture. The scan writes the
 * WebP and keeps the file reference ([AppEntry.art]); a card decodes it the
 * first time it is ACTUALLY COMPOSED, so an app on the far side of a section
 * costs a path, not 0.6 MB — and one that gets uninstalled stops costing
 * anything at all.
 *
 * Budgeted in bytes rather than entries: 540×303 banners are 0.62 MB each on a
 * 1080p TV, so an entry count that fits one density overshoots on another.
 */
object AppArtworkCache {

    /**
     * Enough for the dock plus a screenful of the open drawer, small enough that
     * being asked to trim only costs a re-decode of a file already on disk.
     */
    private val budget: Int = (Runtime.getRuntime().maxMemory() / 16)
        .coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024)
        .toInt()

    // Access-ordered LRU keyed by absolute path: a card that scrolls away and
    // back re-decodes only if its picture was evicted in the meantime.
    private val lru = object : LruCache<String, ImageBitmap>(budget) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().byteCount
    }

    /** A decoded banner/icon for [file], or null when it is not resident. */
    fun get(file: File): ImageBitmap? = lru.get(file.path)

    /**
     * Decodes [file] on the caller's thread and remembers it. Returns null when
     * the file is gone or holds no decodable image — the card then draws its
     * flat tile, exactly as it does for an app that ships no artwork.
     *
     * Evicted entries are NOT recycled: a composed card may still be drawing
     * one (it remembers the ImageBitmap), and recycling under it would crash.
     */
    fun decode(file: File): ImageBitmap? {
        lru.get(file.path)?.let { return it }
        val bmp = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                // Banners are already at their paint size on disk; icons are 128px.
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            },
        )
        if (bmp == null) {
            Log.w("Couchy", "artwork unreadable, dropping tile art: ${file.path}")
            return null
        }
        // Upload ahead of the first draw, so the GPU never stalls mid-frame.
        bmp.prepareToDraw()
        val image = bmp.asImageBitmap()
        lru.put(file.path, image)
        return image
    }

    /** Drop everything — the process is short on memory (see MainActivity). */
    fun clear() = lru.evictAll()
}
