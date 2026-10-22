package com.conreo.couchytv.util

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * The one writer of `files/wallpaper.jpg` — the file the home screen decodes.
 *
 * Writing the target in place raced with the decoder: a write that landed while the home
 * screen was reading the file produced banded, half-image output on screen. The bytes now
 * go to a temporary file in the same directory and are renamed over the target, which the
 * filesystem does in one step, so a reader sees either the old image or the new one.
 *
 * The temporary file is unique per call. A single shared `wallpaper.jpg.tmp` let two
 * overlapping writers race: the loser renamed the winner's bytes away, found nothing of
 * its own to rename, and deleted the wallpaper — leaving the launcher with no picture on
 * disk and the one already on screen frozen forever.
 */
object WallpaperFile {

    const val NAME = "wallpaper.jpg"

    /** The file the launcher reads the custom wallpaper from. */
    fun of(context: Context): File = File(context.filesDir, NAME)

    /**
     * Copies [input] over `wallpaper.jpg` inside [dir], atomically.
     *
     * [dir] rather than a Context so the write can be tested on the JVM with a temp dir.
     *
     * @return false when the copy failed — the previous wallpaper is then untouched.
     */
    fun write(dir: File, input: InputStream): Boolean = runCatching {
        val target = File(dir, NAME)
        val tmp = File.createTempFile("wallpaper", ".tmp", dir)
        try {
            tmp.outputStream().use { out -> input.use { it.copyTo(out) } }
            if (!tmp.renameTo(target)) error("could not replace ${target.name}")
        } catch (t: Throwable) {
            tmp.delete() // never leave a half-written temp file behind
            throw t
        }
        true
    }.getOrDefault(false)
}
