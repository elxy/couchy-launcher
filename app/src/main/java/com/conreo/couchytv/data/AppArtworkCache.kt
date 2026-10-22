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
 * This is the ONLY place a launcher icon lives in RAM. [AppEntry.art] points at
 * the WebP on disk and the card decodes it the first time it is actually
 * composed, so an app on the far side of a section costs a file reference, not
 * 0.6 MB of pixels. It used to be the other way round — [AppRepository.scan]
 * attached a decoded bitmap to every installed package — which decoded ~25
 * banners for the 8 the dock shows, and (after a cold start that drew from the
 * on-disk snapshot) kept a SECOND copy here of the very same pictures.
 *
 * Budgeted in bytes rather than entries: 540×303 banners are 0.62 MB each on a
 * 1080p TV, so an entry count that fits one density overshoots on another.
 */
object AppArtworkCache {

    /**
     * Decoded pixels are kept to a slice of the heap: enough for the dock plus
     * the rows of an open drawer (≈20 banners), small enough that a trim or an
     * eviction only re-decodes a file that is already on disk.
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

    /** A decoded banner/icon for [file], or null if it is not there / not an image. */
    fun get(file: File): ImageBitmap? = lru.get(file.path)

    /**
     * Decodes [file] off the caller's thread and remembers the result.
     * Returns null when the file is gone or holds no decodable image — the card
     * then draws its flat tile, exactly as it does for an app with no artwork.
     */
    fun decode(file: File): ImageBitmap? {
        lru.get(file.path)?.let { return it }
        val opts = BitmapFactory.Options().apply {
            // Banners are already at their paint size on disk; icons are 128px.
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts)
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
