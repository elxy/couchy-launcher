package com.conreo.couchytv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.conreo.couchytv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Top Shelf: the content of the focused dock app, shown above the dock.
 *
 * Android TV's own channel API is empty in China — none of the installed apps
 * publish to it — so for a few apps the shelf is filled from the service's
 * public web API instead: Bilibili popular videos, Douyu live rooms, iQIYI /
 * Tencent Video / Youku hot titles.
 *
 * Everything below asks each service for the LARGEST still it publishes, so
 * the picture still looks sharp on a 4K panel (see [Item.image]):
 *   Bilibili 1920×1080, iQIYI 2560×1440, Tencent Video 1280×720,
 *   Youku 1920×1080, Douyu 899×500 (its source cap).
 *
 * Each card also carries a deep link built from the ids the listing exposes
 * ([Item.play]) — the URI scheme each app publishes for launchers — so pressing
 * OK opens the title in its own app. Douyu exposes no such scheme, so its shelf
 * stays display-only.
 *
 * Network is opt-in (`LauncherConfig.shelfEnabled`). Only public listings are
 * fetched — no account, no cookies, no playback history, nothing uploaded.
 */
object Shelf {

    /** One card of the shelf: a still with two lines of text. */
    data class Item(
        val title: String,
        val meta: String,
        val image: String,
        /** Live covers are often letterboxed; cut the bars so the fill looks right. */
        val trimBars: Boolean = false,
        /**
         * Deep link that opens (or starts playing) this title in its own app —
         * built at fetch time from the ids the public API returned. Null when
         * the service exposes nothing we can hand over (e.g. Douyu), which
         * keeps the shelf display-only for those apps.
         */
        val play: String? = null,
        // ----- fields the listing itself already carries (free, no extra call) -----
        /** Genre words the service tags the title with, e.g. ["悬疑", "犯罪"]. */
        val genres: List<String> = emptyList(),
        /** Full synopsis, as long as the service publishes it. */
        val intro: String = "",
        /** Cast names, in the service's own order. */
        val cast: List<String> = emptyList(),
        /**
         * Programme ids this card would hand to its app (`cid`/`aid`/`showid`).
         * Used to confirm which Douban subject the card is — see DoubanMeta.
         * Empty means "cannot be matched" (Bilibili UGC, Douyu live).
         */
        val programIds: List<String> = emptyList(),
    )

    /** [interactive]: the source yields [Item.play] links, so the shelf can be
     *  browsed with the D-pad and a click can launch playback. */
    private class Source(
        val labelRes: Int,
        val interactive: Boolean = true,
        val fetch: () -> List<Item>,
    )

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** How long a fetched list stays fresh — the shelf is decorative. */
    private const val TTL = 15 * 60_000L

    /**
     * Packages whose shelf we can fill. Bilibili's TV build gets one source;
     * the sideloaded HD (phone) APK shares the listing but not the scheme.
     */
    private val sources: Map<String, Source> = mapOf(
        "com.xiaodianshi.tv.yst" to Source(R.string.shelf_bilibili, fetch = ::bilibili),
        // Sideloaded phone build: same listing, but its scheme is bilibili:// —
        // no verified deep link, so it stays display-only.
        "tv.danmaku.bilibilihd" to Source(R.string.shelf_bilibili, false, ::bilibili),
        "com.douyu.xl.douyutv" to Source(R.string.shelf_douyu, false, ::douyu),
        // 银河奇异果 ships under more than one package (retail build vs. the
        // OPPO TV preinstall). The listing is the same, but each build only
        // answers to its OWN action namespace — see [iqiyiPlay].
        "com.gitvdemo.video" to Source(R.string.shelf_iqiyi, fetch = { iqiyi("com.gitvdemo.video") }),
        "com.gitvvideo.oppo" to Source(R.string.shelf_iqiyi, fetch = { iqiyi("com.gitvvideo.oppo") }),
        "com.ktcp.video" to Source(R.string.shelf_tencent, fetch = ::tencent), // 云视听极光
        "com.cibn.tv" to Source(R.string.shelf_youku, fetch = ::youku), // CIBN 酷喵
    )

    /** pkg -> (fetched-at, items) */
    private val itemsCache = HashMap<String, Pair<Long, List<Item>>>()

    /**
     * Widest still any source publishes (iQIYI's 2560×1440). A bigger panel
     * cannot get more pixels than this, and a smaller one gets what it can
     * show — see [stillWidth].
     */
    private const val MAX_STILL_WIDTH = 2560

    /**
     * Decoded stills, kept in RAM so a cross-fade never re-downloads. Budgeted
     * in bytes, not entries: three screen-filling stills — the one on screen,
     * the cross-fade partner, and the one [image] warms up ahead of the D-pad.
     */
    private val images = object : LruCache<String, Bitmap>(
        minOf(
            3L * MAX_STILL_WIDTH * (MAX_STILL_WIDTH * 9 / 16) * 4,
            Runtime.getRuntime().maxMemory() / 8,
        ).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Drop the decoded stills — the process is short on memory (see MainActivity). */
    fun trimMemory() = images.evictAll()

    /**
     * Width a still is decoded to: the panel's, never above what the sources
     * publish. A 1080p set therefore throws away iQIYI's extra 1.33×
     * (14 MB of texture for a picture the GPU would minify back to 1920 anyway).
     */
    private fun stillWidth(ctx: Context): Int = minOf(
        ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1280),
        MAX_STILL_WIDTH,
    )

    fun has(pkg: String) = pkg in sources

    /** True when this app's shelf items carry deep links (see [Item.play]). */
    fun interactive(pkg: String) = sources[pkg]?.interactive == true

    fun sourceLabel(pkg: String) = sources[pkg]?.labelRes ?: 0

    /**
     * Items for [pkg], cached for [TTL]. Empty when the app has no shelf or the
     * network failed — the caller simply shows nothing.
     */
    suspend fun items(pkg: String): List<Item> = withContext(Dispatchers.IO) {
        val src = sources[pkg] ?: return@withContext emptyList()
        synchronized(itemsCache) {
            itemsCache[pkg]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < TTL) return@withContext list
            }
        }
        val list = try {
            src.fetch()
        } catch (e: Exception) {
            Log.w("Shelf", "$pkg failed", e)
            return@withContext itemsCache[pkg]?.second.orEmpty()
        }
        if (list.isNotEmpty()) {
            synchronized(itemsCache) {
                itemsCache[pkg] = System.currentTimeMillis() to list
            }
        }
        list
    }

    /**
     * The still for [item] — from RAM, the disk cache, or the network, in that
     * order. Decoded at the panel's own width (see [stillWidth]) — the sharpest
     * picture the service publishes, minus any pixels the screen cannot show.
     */
    suspend fun image(ctx: Context, item: Item): Bitmap? = withContext(Dispatchers.IO) {
        val url = item.image
        images.get(url)?.let { return@withContext it }
        val maxWidth = stillWidth(ctx)
        val dir = File(ctx.cacheDir, "shelf").apply { mkdirs() }
        val file = File(dir, Integer.toHexString(url.hashCode()) + ".img")
        var bmp: Bitmap? = if (file.exists()) decode(file, maxWidth, item.trimBars) else null
        if (bmp == null) {
            if (file.exists()) file.delete() // truncated / not an image
            bmp = download(dir, file, url, maxWidth, item.trimBars)
        }
        bmp?.let {
            images.put(url, it)
            return@withContext it
        }
        null
    }

    /** Streams [url] into [file] (via a .part file, so a half download never decodes). */
    private fun download(
        dir: File,
        file: File,
        url: String,
        maxWidth: Int,
        trim: Boolean,
    ): Bitmap? = try {
        val tmp = File(dir, file.name + ".part")
        tmp.writeBytes(get(url))
        if (tmp.length() > 0 && tmp.renameTo(file)) {
            // Keep the on-disk cache small: the newest 40 pictures.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
            decode(file, maxWidth, trim) ?: run {
                file.delete() // not an image after all — don't keep retrying it
                null
            }
        } else {
            tmp.delete()
            null
        }
    } catch (e: Exception) {
        Log.w("Shelf", "image $url failed", e)
        null
    }

    /** Live covers are often letterboxed; cut the black bars so the fill looks right. */
    private fun trimBars(b: Bitmap): Bitmap {
        fun dark(y: Int): Boolean {
            var max = 0
            for (k in 0 until 32) {
                val c = b.getPixel(k * (b.width - 1) / 31, y)
                max = maxOf(max, (c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
            return max < 28
        }
        var top = 0
        while (top < b.height / 4 && dark(top)) top++
        var bottom = b.height
        while (bottom > b.height * 3 / 4 && dark(bottom - 1)) bottom--
        if (top < 4 && b.height - bottom < 4) return b
        val cut = Bitmap.createBitmap(b, 0, top, b.width, bottom - top)
        if (cut !== b) b.recycle()
        return cut
    }

    private fun decode(file: File, maxWidth: Int, trim: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        // A full-screen backdrop needs real pixels; anything under 480px wide is
        // a placeholder/thumbnail and would look like a blurry mess on a TV.
        if (bounds.outWidth < 480) return null
        // inSampleSize only halves, so it lands on the last power of two that
        // still covers the panel (2560 stays 2560 on a 1920 screen).
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888, not 565: on a large dark still the 16-bit dither is
            // clearly visible, and sharpness is the whole point of this feature.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        // Take the rest of the oversampling out on the CPU: what is left is a
        // texture bigger than the screen, which only costs upload time, RAM and
        // cache misses in the sampler. 25% slack keeps the source's own detail.
        if (bmp.width > maxWidth * 5 / 4) {
            val scaled = Bitmap.createScaledBitmap(
                bmp, maxWidth, (bmp.height.toLong() * maxWidth / bmp.width).toInt(), true,
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return (if (trim) trimBars(bmp) else bmp).also { it.prepareToDraw() }
    }

    /* ----------------------------- sources ----------------------------- */

    private fun bilibili(): List<Item> {
        val list = JSONObject(get("https://api.bilibili.com/x/web-interface/popular?ps=12&pn=1").decodeToString())
            .getJSONObject("data").getJSONArray("list")
        return (0 until list.length()).map { list.getJSONObject(it) }.map { v ->
            Item(
                title = v.optString("title"),
                meta = "${v.getJSONObject("owner").optString("name")} · " +
                    "${count(v.getJSONObject("stat").optLong("view"))}播放",
                // Largest rendition Bilibili keeps for a cover: 1920×1080.
                image = v.optString("pic").replace("http://", "https://") + "@1920w_1080h_1c.jpg",
                play = bilibiliPlay(v),
                // UGC: a partition name and the uploader's own blurb. No cast, and
                // no Douban subject to match — programIds stays empty.
                genres = listOf(v.optString("tname")).filter { it.isNotEmpty() },
                intro = v.optString("desc").trim(),
            )
        }
    }

    private fun douyu(): List<Item> {
        val list = JSONObject(get("https://www.douyu.com/japi/weblist/apinc/allpage/6/1").decodeToString())
            .getJSONObject("data").getJSONArray("rl")
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("rs16").isNotEmpty() }
            .take(12)
            .map { r ->
                Item(
                    title = r.optString("rn"),
                    meta = "${r.optString("nn")} · ${r.optString("c2name")} · ${count(r.optLong("ol"))}热度",
                    // "/dy4" is the 899×500 JPEG rendition of the AVIF cover.
                    image = r.optString("rs16").substringBeforeLast('/') + "/dy4",
                    trimBars = true,
                )
            }
    }

    /**
     * iQIYI's PC recommendation rail. `image_url_normal` comes back at the size
     * asked for in `img_size` (2560×1440 here, the largest it serves), as AVIF —
     * Android below 12 can't decode AVIF, so the CDN's JPEG twin is requested.
     */
    private fun iqiyi(pkg: String): List<Item> {
        val url = "https://mesh.if.iqiyi.com/portal/lw/v7/channel/card/videoUpdate" +
            "?channelName=recommend&ext_data_count=11&play_record=false" +
            "&data_source=rec_focus_v2_suggest&tempId=62&count=11&block_id=focus" +
            "&games=focus&recoms=focus&img_size=_2560_1440&showAd=1"
        val list = JSONObject(get(url).decodeToString()).getJSONArray("data")
        return (0 until list.length()).map { list.getJSONObject(it) }
            .mapNotNull { v ->
                val title = v.optString("title").ifEmpty { return@mapNotNull null }
                // Landscape renditions only: image_cover / back_image fall back to a
                // portrait 120×160 still, which looks terrible blown up to 4K.
                //
                // `img_size=_2560_1440` is only honoured for SOME entries: the rest
                // come back as the default 120×160 portrait preview, which
                // Shelf.decode() rejects (min width 480) and the card then cannot be
                // drawn at all. Appending the rendition suffix explicitly fixes it —
                // measured on 11 entries: 2 usable before, 10 after.
                val img = sequenceOf("image_url_normal", "banner_image_url")
                    .map { v.optString(it) }
                    .firstOrNull { it.isNotEmpty() }
                    ?.let(::landscapeIqiyi)
                    ?: return@mapNotNull null
                val tags = v.optString("tag").split(';').filter { it.isNotEmpty() }.take(2)
                val score = v.optDouble("sns_score", 0.0)
                Item(
                    title = title,
                    meta = (tags + if (score > 0) String.format(Locale.US, "%.1f分", score) else "")
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    image = img.replace("http://", "https://"),
                    play = iqiyiPlay(v, pkg),
                    // Everything below is already in this response — no extra call.
                    // iQIYI's `tag` is a long tail (悬疑;犯罪;罪案;警匪;网剧;内地;普通话;…):
                    // the leading words are the genres, the rest is audience/topic noise.
                    genres = v.optString("tag").split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && it !in IQIYI_NOISE }
                        .distinct()
                        .take(3),
                    intro = v.optString("description").ifEmpty { v.optString("desc") }.trim(),
                    cast = actorNames(v),
                    // iQIYI may list the album (series) id, the single video id, or
                    // both; Douban's `aid` is the album, so the album id comes first.
                    programIds = listOf(v.optString("album_id"), v.optString("tv_id"))
                        .filter { it.isNotEmpty() },
                )
            }
            .distinctBy { it.title }
            .filter { it.meta.isNotEmpty() }
    }

    /**
     * Tencent Video's channel page (the same trpc endpoint v.qq.com calls).
     * Cards carry per-rendition URLs; `ready_image_url` is the 1280×720 still —
     * Tencent publishes no wider 16:9 art, so this is its sharpest.
     */
    private fun tencent(): List<Item> {
        val guid = "nne1" + Integer.toHexString((System.nanoTime() and 0xFFFFFFFFL).toInt())
        val pageParams = JSONObject()
            .put("page_type", "channel")
            .put("page_id", "100113") // 电视剧/电影 channel: the hot wall
            .put("scene", "channel")
            .put("new_mark_label_enabled", "1")
        val body = JSONObject()
            .put(
                "page_params",
                JSONObject(pageParams.toString())
                    .put("vl_to_mvl", "")
                    .put(
                        "free_watch_trans_info",
                        JSONObject().put("ad_frequency_control_time_list", JSONObject()).toString(),
                    ),
            )
            .put(
                "page_bypass_params",
                JSONObject()
                    .put("params", JSONObject(pageParams.toString()).apply {
                        put("platform_id", 2)
                        put("caller_id", "3000010")
                        put("data_mode", "default")
                        put("user_mode", "default")
                        put("specified_strategy", "")
                    })
                    .put("scene", "channel")
                    .put("app_version", "8.2.0")
                    .put("abtest_bypass_id", guid),
            )
            .toString()
        val resp = JSONObject(
            post(
                "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage" +
                    "?video_appid=3000010&vversion_platform=2&vdevice_guid=$guid",
                body,
            ).decodeToString()
        )
        if (resp.optInt("ret") != 0) throw java.io.IOException("ret ${resp.optInt("ret")}")
        val cards = ArrayList<Item>()
        val seen = HashSet<String>()
        fun walk(cardsJson: JSONArray) {
            for (i in 0 until cardsJson.length()) {
                val card = cardsJson.optJSONObject(i) ?: continue
                val p = card.optJSONObject("params")
                if (p != null) {
                    val title = p.optString("title")
                    val img = sequenceOf("ready_image_url", "pic_1280x720", "image_url")
                        .map { p.optString(it) }
                        .firstOrNull { it.isNotEmpty() }
                    if (title.isNotEmpty() && img != null && seen.add(title)) {
                        cards.add(
                            Item(
                                title = title,
                                meta = listOf(p.optString("main_genre"), p.optString("area_name"))
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" · "),
                                image = img.replace("http://", "https://"),
                                play = tencentPlay(p),
                                // Tencent's channel cards carry one genre word and a
                                // region — no synopsis, no cast. This is exactly the
                                // case the Douban lookup exists for.
                                genres = listOf(p.optString("main_genre"))
                                    .filter { it.isNotEmpty() },
                                // The `cid` the card launches with; Douban's
                                // vendors[].uri carries the same one.
                                programIds = listOf(p.optString("cid"))
                                    .filter { it.isNotEmpty() },
                            )
                        )
                    }
                }
                val children = card.optJSONObject("children_list") ?: continue
                for (key in children.keys()) {
                    val group = children.optJSONObject(key) ?: continue
                    group.optJSONArray("cards")?.let { walk(it) }
                }
            }
        }
        resp.optJSONObject("data")?.optJSONArray("CardList")?.let { walk(it) }
        return cards.take(12)
    }

    /**
     * Youku's PC home. There is no JSON endpoint (the page is server-rendered
     * with a JS payload that isn't valid JSON as a whole), but every video list
     * on it is a standalone, well-formed `"itemList":[…]` array — those are
     * lifted out and parsed one by one. `hImg` is the 1920×1080 still.
     */
    private fun youku(): List<Item> {
        val html = get("https://www.youku.com/ku/webhome").decodeToString()
        val items = ArrayList<JSONObject>()
        var pos = 0
        while (true) {
            val key = html.indexOf("\"itemList\":[", pos)
            if (key < 0) break
            val start = html.indexOf('[', key)
            val end = jsonArrayEnd(html, start) ?: break
            runCatching { JSONArray(html.substring(start, end)) }
                .getOrNull()
                ?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
                }
            pos = end
        }
        return items
            .asSequence()
            .filter { it.optString("title").isNotEmpty() && it.optString("hImg").isNotEmpty() }
            .distinctBy { it.optString("title") }
            .take(12)
            .map { v ->
                Item(
                    title = v.optString("title"),
                    // Youku ships a full synopsis; one line of it is plenty.
                    meta = v.optString("desc").let { if (it.length > 42) it.take(42) + "…" else it },
                    image = v.optString("hImg").replace("http://", "https://"),
                    play = youkuPlay(v),
                    // `tags[].text.title` leads with the content type (电影/电视剧/
                    // 少儿/动漫) and then badges (首播/VIP); `desc` is the synopsis.
                    genres = youkuGenres(v),
                    intro = v.optString("desc").trim(),
                    // Youku's own show id — the id its deep link uses.
                    programIds = youkuProgramIds(v),
                )
            }
            .toList()
    }

    /**
     * The 2560×1440 rendition of an iQIYI still.
     *
     * iQIYI serves `.avif` (undecodable below Android 12, hence the `.jpg` twin)
     * and ignores `img_size` for part of its catalogue, answering with a 120×160
     * portrait preview instead. The rendition is addressable by suffix, so ask for
     * it directly rather than hoping the parameter took effect.
     */
    internal fun landscapeIqiyi(raw: String): String {
        val jpg = raw.replaceFirst(Regex("\\.avif$"), ".jpg").replace("http://", "https://")
        if (IQIYI_SIZE in jpg) return jpg
        val dot = jpg.lastIndexOf('.')
        if (dot <= 0) return jpg
        return jpg.substring(0, dot) + IQIYI_SIZE + jpg.substring(dot)
    }

    private const val IQIYI_SIZE = "_2560_1440"

    /** iQIYI `tag` is a semicolon list; the first words are genres, the rest is
     *  audience/topic noise (内地, 普通话, 群像, 院线…) that means nothing at 10 ft. */
    private val IQIYI_NOISE = setOf(
        "内地", "普通话", "群像", "院线", "网络电影", "网剧", "未来", "社会",
        "正能量", "自由", "震撼", "勇气", "感动", "燃", "热血", "真实人物改编",
    )

    /** Cast names from iQIYI's `starring` (leads) and `actor` (supporting). */
    private fun actorNames(v: JSONObject): List<String> {
        val out = ArrayList<String>()
        for (key in listOf("starring", "actor")) {
            val array = v.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty() && name !in out) out.add(name)
            }
        }
        return out
    }

    /** Content type from Youku's tag pills (电影 / 电视剧 / 少儿 / 动漫…). */
    private fun youkuGenres(v: JSONObject): List<String> {
        val tags = v.optJSONArray("tags") ?: return emptyList()
        return (0 until tags.length())
            .mapNotNull { i ->
                tags.optJSONObject(i)?.optJSONObject("text")?.optString("title")?.trim()
                    ?.takeIf { it.isNotEmpty() && it in YOUKU_TYPES }
            }
            .distinct()
    }

    private val YOUKU_TYPES = setOf(
        "电影", "电视剧", "综艺", "动漫", "少儿", "纪录片", "体育", "游戏", "资讯",
    )

    /** The show id Youku's deep link is built from (see [youkuPlay]). */
    private fun youkuProgramIds(v: JSONObject): List<String> {
        val action = v.optJSONObject("action")
        val showId = action?.optJSONObject("extra")?.optLong("showId", 0L) ?: 0L
        val itemId = v.optLong("id", 0L)
        return listOf(showId, itemId)
            .filter { it > 0L }
            .map { it.toString() }
            .distinct()
    }

    /** Index just past the `]` that closes the array starting at [start]. */
    private fun jsonArrayEnd(s: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return null
    }

    /* ----------------------------- deep links ----------------------------- */

    /** Appends [params] (null / empty values are skipped) to [base], encoded. */
    private fun uri(base: String, vararg params: Pair<String, String?>): String {
        val sb = StringBuilder(base)
        var first = !base.contains('?')
        for ((k, v) in params) {
            if (v.isNullOrEmpty()) continue
            sb.append(if (first) '?' else '&')
            first = false
            sb.append(Uri.encode(k)).append('=').append(Uri.encode(v))
        }
        return sb.toString()
    }

    /**
     * Vendor name for iQIYI's open API (`customer`, required by its spec).
     * Only affects their telemetry — the app stores it lowercased.
     */
    private val customer: String by lazy {
        Build.MANUFACTURER.trim().takeIf { it.isNotEmpty() } ?: "couchy"
    }

    /**
     * Bilibili TV's own "continue watching" grammar (seen in its MainActivity):
     * type=3 + isBangumi=0 + a numeric avId goes straight to the player.
     */
    private fun bilibiliPlay(v: JSONObject): String? {
        val aid = v.optLong("aid", 0L)
        if (aid <= 0L) return null
        val cid = v.optLong("cid", 0L).takeIf { it > 0L }?.toString()
        return uri(
            "yst://com.xiaodianshi.tv.yst",
            "type" to "3",
            "from" to "androidlauncher",
            "isBangumi" to "0",
            "avId" to aid.toString(),
            "cId" to cid,
        )
    }

    /**
     * iQIYI's documented open API: an ACTION_DETAIL intent whose `playInfo` JSON
     * carries videoId (album), episodeId (single video) and chnId. Its URI form
     * (`gala://page?...`) is resolved by the app into exactly that intent, so
     * the query keys are the JSON keys. Titles that never had a detail page are
     * sent to the player instead when the vendor has external-play rights.
     *
     * The action is namespaced per package: the retail build registers
     * `com.gitvdemo.video.action.*`, the OPPO preinstall (same version, same
     * UI) registers `com.gitvvideo.oppo.action.*`. A URI carrying the other
     * build's action matches nothing, and with both APKs installed a shared
     * action would leave the launcher to pick between two apps.
     */
    private fun iqiyiPlay(v: JSONObject, pkg: String): String? {
        val album = v.optString("album_id")
        val video = v.optString("tv_id")
        if (album.isEmpty() && video.isEmpty()) return null
        return uri(
            "gala://page",
            "action" to "$pkg.action.ACTION_DETAIL",
            "videoId" to album.ifEmpty { null },
            "episodeId" to video.ifEmpty { null },
            "chnId" to v.optString("channel_id").ifEmpty { null },
            "playType" to "recommend",
            "backstrategy" to "launcher",
            "customer" to customer,
        )
    }

    /**
     * Tencent's documented jump: action=7 plays a single video by id, action=1
     * opens the title's detail page (used when the card carries no episode id).
     * The URI must keep the `tvrecommendation/video` host+path — that is the
     * only `tenvideo2` filter the TV app exposes to other packages (a bare
     * `tenvideo2://?action=7…` does not resolve).
     */
    private fun tencentPlay(p: JSONObject): String? {
        val vid = p.optString("window_vid").ifEmpty { p.optString("vid") }
        if (vid.isNotEmpty()) {
            return uri(
                "tenvideo2://tvrecommendation/video",
                "action" to "7",
                "video_id" to vid,
                "stay_flag" to "1",
                "pull_from" to "androidTV",
            )
        }
        val cid = p.optString("cid")
        if (cid.isNotEmpty()) {
            return uri(
                "tenvideo2://tvrecommendation/video",
                "action" to "1",
                "cover_id" to cid,
                "stay_flag" to "1",
                "pull_from" to "androidTV",
            )
        }
        return null
    }

    /**
     * Youku/CIBN's detail-jump URI: `id` is the show, `video_id` the episode.
     * Nothing about full screen or Back: `isfull`/`fullback` jump straight into
     * the full-screen player, which skips the detail page a viewer expects from
     * a title card, and `isBackYingHome` belongs to the app's own home flow —
     * both are left out (their defaults apply).
     */
    private fun youkuPlay(v: JSONObject): String? {
        val action = v.optJSONObject("action")
        val type = action?.optString("type").orEmpty().ifEmpty { v.optString("action_type") }
        val value = action?.optString("value").orEmpty().ifEmpty { v.optString("action_value") }
        val showId = action?.optJSONObject("extra")?.optLong("showId", 0L) ?: 0L
        val itemId = v.optLong("id", 0L)
        val playing = type == "JUMP_TO_VIDEO" // a single video: id + video_id
        val id = if (playing) showId.takeIf { it > 0L } ?: itemId else itemId.takeIf { it > 0L } ?: showId
        val vid = value.takeIf { playing && it.isNotEmpty() }
        if (id <= 0L && vid == null) return null
        return uri(
            "cibntv_yingshi://yingshi_detail",
            "id" to id.takeIf { it > 0L }?.toString(),
            "mediaSource" to "9",
            "video_id" to vid,
        )
    }

    private fun count(n: Long) = when {
        n >= 100_000_000 -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format(Locale.US, "%.1f万", n / 10_000.0)
        else -> n.toString()
    }

    private fun get(url: String): ByteArray = request(url, null)

    private fun post(url: String, body: String): ByteArray = request(url, body)

    private fun request(url: String, body: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        // Bilibili's API answers with an empty body when sent a Referer from
        // outside its pages; Douyu's and the video sites' demand one. Only the
        // API hosts get one — the image CDNs serve plain requests.
        refererFor(url)?.let { c.setRequestProperty("Referer", it) }
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun refererFor(url: String): String? = when {
        "api.bilibili.com" in url -> "https://www.bilibili.com/"
        "www.douyu.com" in url -> "https://www.douyu.com/"
        "douyucdn.cn" in url -> "https://www.douyu.com/"
        "www.youku.com" in url -> "https://www.youku.com/"
        "mesh.if.iqiyi.com" in url -> "https://www.iqiyi.com/"
        "pbaccess.video.qq.com" in url -> "https://v.qq.com/"
        else -> null
    }
}
