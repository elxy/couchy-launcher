package com.conreo.couchytv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Built-in aerial-videos wallpaper source — the video manifests ship WITH the
 * launcher, so no companion app or plugin needs to be installed or configured.
 * 289 videos, 1080p H.264, streamed: Apple (139), Amazon Fire TV (112),
 * community collections (38).
 *
 * The Apple clips also carry tvOS' own timed place captions (Chinese + English,
 * from the Aerial/Tigris catalogue in assets/aerials.json) — the launcher shows
 * them as the wallpaper caption while the clip plays.
 */
object BuiltinAerials {

    /** Source filter values (config.builtinSource). */
    val SOURCES = listOf("", "A", "Z", "C1", "C2") // "" = all

    /**
     * One timed caption: the place the camera is over at [time] seconds, in
     * Chinese ([zh]) and English ([en]). Apple's films change the label as the
     * camera moves, e.g. "希腊，两侧是爱奥尼亚海和爱琴海" at 100 s.
     */
    @Serializable
    data class Caption(val time: Int, val zh: String = "", val en: String = "")

    /**
     * One aerial clip: the stream URI, its short title ("East Asia", "Caribbean
     * Sea", …) used when there are no timed captions, and those captions.
     */
    data class Clip(
        val title: String,
        val uri: String,
        val captions: List<Caption> = emptyList(),
    )

    @Serializable
    private data class Entry(
        val t: String = "",
        val u: String,
        val s: String = "A",
        val c: List<Caption> = emptyList(),
    )

    @Volatile
    private var cache: List<Entry>? = null

    private fun loadAll(context: Context): List<Entry> {
        cache?.let { return it }
        val list = runCatching {
            val json = context.assets.open("aerials.json")
                .bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<List<Entry>>(json)
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    /** Clips of a collection. [sourceIndex] indexes SOURCES; 0 = every collection. */
    fun loadClips(context: Context, sourceIndex: Int = 0): List<Clip> {
        val key = SOURCES.getOrElse(sourceIndex) { "" }
        return loadAll(context)
            .filter { key.isEmpty() || it.s == key }
            .map { Clip(it.t, it.u, it.c) }
    }

    /** Video URIs for a collection — same order as [loadClips]. */
    fun load(context: Context, sourceIndex: Int = 0): List<String> =
        loadClips(context, sourceIndex).map { it.uri }
}
