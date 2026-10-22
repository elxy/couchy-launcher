package com.conreo.couchytv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bing's picture of the day as a wallpaper source — the same frame the Bing
 * homepage shows. Opt-in: one small JSON request plus one JPEG per day, no
 * account, no cookies, nothing uploaded.
 *
 * The archive lists every rendition of the day's frame under one id, so the
 * panel itself picks what gets downloaded: a 4K TV gets the raw 3840×2160
 * `_UHD.jpg` frame, a 1080p TV asks Bing's image service (`/th?…&w=&h=`) to
 * downscale that same frame server-side — ~550 KB instead of ~3.7 MB.
 *
 * Downloads are kept in `filesDir/bing/<startdate>.jpg`, the newest [KEEP] days
 * only (the file name is the day, so the folder prunes itself and there is no
 * separate index to keep in sync). That is the feature's whole disk budget:
 * ~8 MB on a 1080p set, ~55 MB on a 4K one. Power-on and offline starts show
 * the newest cached picture instead of the gradient, and the launcher rotates
 * through the cached days like it does with the aerial videos.
 */
object Bing {

    /**
     * The frame on disk, the day it belongs to (Bing's `startdate`) and Bing's
     * own description of it — the copyright line, shown as the wallpaper
     * caption (e.g. "Autumn in Naejangsan National Park (© …)").
     */
    data class Photo(val file: File, val date: String, val caption: String = "")

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** cn.bing.com first: most of this launcher's TVs are in China, where the
     *  www host redirects (and is slower). Both answer the same archive API. */
    private val HOSTS = listOf("https://cn.bing.com", "https://www.bing.com")

    private const val API = "/HPImageArchive.aspx?format=js&idx=0&n=8"

    /**
     * Bing's localized editions. Only the markets that actually translate the
     * copyright line are listed — the rest answer in English anyway, so they
     * keep the international edition ([marketFor] returns null).
     */
    private val MARKETS = mapOf(
        "zh" to "zh-CN",
        "de" to "de-DE",
        "fr" to "fr-FR",
        "es" to "es-ES",
        "it" to "it-IT",
        "ja" to "ja-JP",
        "pt" to "pt-BR",
    )

    /** Bing market for a launcher language code (`"zh"` → `"zh-CN"`), or null. */
    fun marketFor(language: String): String? = MARKETS[language]

    /** Panels at least this wide are treated as 4K and get the raw UHD frame. */
    private const val UHD_MIN_WIDTH = 2400

    /** How many days of pictures stay on disk (15 × one JPEG). */
    private const val KEEP = 15

    private fun dir(ctx: Context) = File(ctx.filesDir, "bing")
    private fun fileFor(ctx: Context, date: String) = File(dir(ctx), "$date.jpg")
    private fun captionFor(ctx: Context, date: String) = File(dir(ctx), "$date.txt")

    /** Cached days, oldest first — the file name IS the date (yyyymmdd). */
    fun cachedDays(ctx: Context): List<Photo> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            ?.map {
                val date = it.name.removeSuffix(".jpg")
                // The caption is a tiny sidecar; missing on days downloaded by
                // a build that did not record it yet.
                val caption = runCatching { captionFor(ctx, date).readText() }.getOrDefault("")
                Photo(it, date, caption.trim())
            }
            .orEmpty()

    /** The newest cached frame, without touching the network. */
    fun cached(ctx: Context): Photo? = runCatching {
        cachedDays(ctx).asReversed().firstOrNull { looksLikeImage(it.file) }
    }.getOrNull()

    /**
     * Today's frame, downloaded only when Bing has published a day that is not
     * on disk yet. Any network/parse failure returns the cached frame, so a TV
     * that boots before its network is up keeps the newest picture instead of
     * flashing a gradient (the caller retries a few minutes later).
     *
     * [market] (e.g. "zh-CN") localizes both the picture Bing serves for that
     * day and its copyright line; null = the international English edition.
     */
    suspend fun daily(ctx: Context, market: String? = null): Photo? = withContext(Dispatchers.IO) {
        adoptLegacy(ctx) // the first build kept a single bing.jpg
        prune(ctx)       // bounded even if a previous run was killed
        val meta = runCatching { fetchMeta(market) }.getOrElse {
            Log.w("Bing", "archive request failed", it)
            return@withContext cached(ctx)
        }
        // Refresh the captions of every day still on disk — this is how a UI
        // language switch re-labels the pictures already downloaded (the
        // archive returns the last 8 days, which covers most of the rotation).
        meta.captions.forEach { (date, text) ->
            if (date == meta.date || fileFor(ctx, date).isFile) writeCaption(ctx, date, text)
        }
        val dest = fileFor(ctx, meta.date)
        // Re-check rather than trusting the file name: the process may have
        // died mid-rename on a previous run.
        if (dest.isFile && looksLikeImage(dest)) return@withContext Photo(dest, meta.date, meta.caption)
        try {
            download(rendition(ctx, meta.urlBase), dest)
        } catch (e: Exception) {
            // A market that publishes no UHD rendition still has the plain
            // 1920×1080 file the archive itself links to.
            Log.w("Bing", "rendition failed, falling back to the archive url", e)
            if (meta.fallback.isEmpty()) return@withContext cached(ctx)
            try {
                download(meta.fallback, dest)
            } catch (e2: Exception) {
                Log.w("Bing", "download failed", e2)
                return@withContext cached(ctx)
            }
        }
        prune(ctx)
        Photo(dest, meta.date, meta.caption)
    }

    /** Writes [text] only when it differs — an unchanged caption costs no I/O. */
    private fun writeCaption(ctx: Context, date: String, text: String) {
        if (text.isEmpty()) return
        val f = captionFor(ctx, date)
        if (runCatching { f.readText().trim() }.getOrDefault("") == text) return
        runCatching {
            f.parentFile?.mkdirs() // first run: the folder is made by the download
            f.writeText(text)
        }
    }

    /**
     * Keeps the newest [KEEP] days with their captions and drops leftover
     * `.part` files, so the folder can never grow past one picture per day.
     */
    private fun prune(ctx: Context) {
        val folder = dir(ctx)
        val days = cachedDays(ctx)
        days.dropLast(KEEP).forEach { it.file.delete() }
        val keep = days.takeLast(KEEP).map { it.date }.toSet()
        folder.listFiles()?.forEach { f ->
            val base = f.name.substringBeforeLast('.')
            when {
                f.name.endsWith(".part") -> f.delete()
                // Orphaned captions (no picture left) go with their day.
                f.name.endsWith(".txt") && base !in keep -> f.delete()
            }
        }
    }

    /**
     * The first build stored a single `filesDir/bing.jpg` (+ `bing.date`).
     * Fold it into the date-keyed folder instead of leaving it orphaned.
     */
    private fun adoptLegacy(ctx: Context) {
        val old = File(ctx.filesDir, "bing.jpg")
        if (!old.isFile) return
        val date = File(ctx.filesDir, "bing.date")
            .takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val target = fileFor(ctx, date.ifEmpty { "00000000" }) // sorts oldest
        val adopted = target.exists() || old.renameTo(target) ||
            runCatching { old.copyTo(target, overwrite = true) }.isSuccess
        if (adopted) {
            old.delete()
            File(ctx.filesDir, "bing.date").delete()
        }
    }

    private class Meta(
        val date: String,
        val urlBase: String,
        val fallback: String,
        val caption: String,
        /** date -> copyright line, for every day the archive returned. */
        val captions: Map<String, String>,
    )

    private fun fetchMeta(market: String?): Meta {
        val query = API + if (market.isNullOrEmpty()) "" else "&mkt=$market"
        var last: Exception? = null
        for (host in HOSTS) {
            try {
                val images = JSONObject(get(host + query).decodeToString())
                    .getJSONArray("images")
                val img = images.getJSONObject(0)
                val date = img.optString("startdate")
                if (date.isEmpty()) throw IOException("no startdate")
                // urlbase is "/th?id=OHR.XXX"; some payloads only ship "url"
                // ("/th?id=OHR.XXX_1920x1080.jpg&rf=…&pid=hp") — trim it back
                // to the rendition-independent id.
                val base = img.optString("urlbase").ifEmpty {
                    img.optString("url").substringBefore("_1920x1080")
                }
                if (base.isEmpty()) throw IOException("no image url")
                val fallback = img.optString("url").let {
                    if (it.isEmpty()) "" else "https://www.bing.com$it"
                }
                // Bing's credit line, in the market's language, e.g. "内藏山国立公
                // 园的秋景，韩国 (© Twenty47studio/Getty Images)".
                val caption = img.optString("copyright")
                val captions = (0 until images.length())
                    .map { images.getJSONObject(it) }
                    .mapNotNull { o ->
                        val d = o.optString("startdate")
                        val c = o.optString("copyright")
                        if (d.isEmpty() || c.isEmpty()) null else d to c
                    }
                    .toMap()
                return Meta(date, base, fallback, caption, captions)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("no bing host reachable")
    }

    /** UHD for 4K panels; the same frame scaled by Bing for anything smaller. */
    private fun rendition(ctx: Context, urlBase: String): String {
        val dm = ctx.resources.displayMetrics
        val uhd = "https://www.bing.com$urlBase" + "_UHD.jpg&pid=hp"
        if (dm.widthPixels >= UHD_MIN_WIDTH) return uhd
        // A 16:9 target no wider than 1080p: the panel is 16:9, and staying at
        // its own size keeps Bing from sending pixels the TV can't show.
        val w = dm.widthPixels.coerceIn(960, 1920)
        val h = w * 9 / 16
        return "$uhd&w=$w&h=$h"
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * A JPEG whose header still parses — one cheap read that keeps a truncated
     * file (killed mid-download, full disk) from being treated as the photo.
     */
    private fun looksLikeImage(f: File): Boolean {
        if (f.length() <= 0L) return false
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /**
     * Streams the JPEG into [dest] via a `.part` file, so a half download never
     * lands as a day: rename is atomic within the same FS.
     */
    private fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        // A previous run killed mid-download leaves a partial file behind.
        tmp.delete()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "image/*")
        try {
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
            } finally {
                c.disconnect()
            }
            // Never store a truncated image: it would be shown for a whole day.
            if (!looksLikeImage(tmp)) throw IOException("incomplete image (${tmp.length()} bytes)")
            if (!tmp.renameTo(dest)) throw IOException("rename failed")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
