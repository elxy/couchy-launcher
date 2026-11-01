package com.conreo.couchytv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Douban enrichment for one shelf card: 品类 / 简介 / 演员 / 豆瓣评分.
 *
 * Best effort by design — the shelf works without it. When the lookup cannot
 * confirm which Douban subject this card is, or Douban answers incompletely,
 * this returns null and the caption keeps whatever the streaming service itself
 * already provided.
 *
 * Two public endpoints, both anonymous (no account, no cookie, no token):
 *
 *  - `movie.douban.com/j/subject_suggest?q=` — title → subject id. Small JSON,
 *    no Referer required. Misses some titles, so it only proposes candidates.
 *  - `m.douban.com/rexxar/api/v2/subject/{id}?for_mobile=1` — the detail page
 *    `m.douban.com` itself calls. **Requires a Referer**, otherwise:
 *    `400 {"msg":"invalid_request_1284","code":1287}`.
 *
 * Which candidate is the right one is decided by the platform program id, not by
 * the title: Douban's `vendors[].uri` / `linewatches[].source_uri` carry the very
 * ids the shelf cards hold (`cid` / `aid` / `showid`). Title matching ("猎狐行动"
 * vs "猎狐·行动") is only the fallback.
 *
 * Douban rate-limits per IP. Requests are serialised, paced, and a 403/429 stops
 * all further attempts for [BLOCK_MS] — an unanswered card is simply unenriched.
 */
object DoubanMeta {

    private const val TAG = "DoubanMeta"

    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** Referer for the rexxar detail endpoint — without one it answers 400. */
    private const val DETAIL_REFERER = "https://m.douban.com/"

    private const val SUGGEST = "https://movie.douban.com/j/subject_suggest?q="
    private const val DETAIL = "https://m.douban.com/rexxar/api/v2/subject/"

    /** Gap between two HTTP calls. Douban throttles bursts from one IP. */
    private const val PACING_MS = 350L

    /** After a 403/429, stop asking for a while. */
    private const val BLOCK_MS = 10 * 60_000L

    private const val CACHE_TTL_MS = 30L * 24 * 3600_000L

    /**
     * Shorter TTL for a record Douban has not scored yet. Genres, cast and
     * synopsis never change once published, but a rating does appear later —
     * caching "暂无评分" for 30 days would hide the score for a month.
     */
    private const val UNRATED_TTL_MS = 7L * 24 * 3600_000L

    private const val CACHE_MAX = 60

    /**
     * In-memory misses, kept for this process only. A miss is NOT written to disk:
     * Douban answers a soft-limited `subject_suggest` with an empty array, which is
     * byte-for-byte identical to "no such title" — caching that for 30 days would
     * silently disable the feature for a month after a burst.
     */
    private const val MISS_MEMO_MS = 5 * 60_000L

    /**
     * How long a "Douban does not know this title" answer lasts on disk. Not 30
     * days: the answer can also mean the title is simply too new to be indexed.
     */
    private const val MISS_TTL_MS = 3L * 24 * 3600_000L

    /** How many candidates from one suggest answer may be probed. */
    private const val MAX_CANDIDATES = 3

    /**
     * Requests Douban tolerates before `subject_suggest` starts answering `[]`.
     * Measured on 2026-11: a 12 s cadence survived 16 consecutive lookups, while
     * 8 s lost 2 of 10 — so it is a cumulative count over a sliding minute, not a
     * per-second rate. 12 per minute (6 lookups) sits under the observed ceiling.
     */
    private const val BUDGET_REQUESTS = 12
    private const val BUDGET_WINDOW_MS = 60_000L

    /**
     * Douban's record for one programme. Every field is optional; a null
     * [DoubanMeta] means "nothing usable" and callers must keep their own data.
     */
    data class Meta(
        val subjectId: String,
        val genres: List<String> = emptyList(),
        val region: String = "",
        val year: String = "",
        /** 0.0 = not rated (Douban's 暂无评分), which is NOT a score of zero. */
        val rating: Double = 0.0,
        val cast: List<String> = emptyList(),
        val intro: String = "",
        /** True when the platform program id matched; false for a title match. */
        val idMatched: Boolean = false,
        /** 2 = platform id confirmed it, 1 = title (+year) did. */
        val confidence: Int = 0,
    ) {
        /** Anything worth widening the caption for? */
        val isEmpty: Boolean
            get() = genres.isEmpty() && region.isEmpty() && year.isEmpty() &&
                rating <= 0.0 && cast.isEmpty() && intro.isEmpty()
    }

    /** One hit or miss, cached across launches. */
    private class Entry(val meta: Meta?, val at: Long = System.currentTimeMillis())

    /** Timestamps of the requests issued in the last [BUDGET_WINDOW_MS]. */
    private val sent = ArrayDeque<Long>()

    /**
     * Drop timestamps that have left the window, then report how many requests
     * are still available. Pure enough to test: the caller owns [sent].
     */
    internal fun available(sent: ArrayDeque<Long>, now: Long): Int {
        while (sent.isNotEmpty() && now - sent.first() > BUDGET_WINDOW_MS) sent.removeFirst()
        return (BUDGET_REQUESTS - sent.size).coerceAtLeast(0)
    }

    /**
     * Reserve [n] requests from the sliding-window budget, or return false when
     * the window is full. Cache hits never reach this, so a card the user has
     * seen before can always be looked up again for free.
     */
    private fun reserve(n: Int): Boolean = synchronized(sent) {
        val now = System.currentTimeMillis()
        if (available(sent, now) < n) return false
        repeat(n) { sent.addLast(now) }
        true
    }

    /** True when the window has no room for another lookup (2 requests) now. */
    fun budgetExhausted(): Boolean = synchronized(sent) {
        available(sent, System.currentTimeMillis()) < 2
    }

    private val lock = Mutex()
    private var blockedUntil = 0L
    private var lastRequestAt = 0L
    private var cacheDir: File? = null

    /**
     * Look up [title] on Douban and keep the result only if [programId] (the id
     * the shelf card would launch) appears in Douban's own vendor list.
     *
     * @param platform `qq` / `iqiyi` / `youku` / `mgtv` — see [platformOfPackage]
     * @param programId the `cid` / `aid` / `showid` the card holds, or null
     */
    suspend fun lookup(
        ctx: Context,
        platform: String?,
        programId: String?,
        title: String,
    ): Meta? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = cacheKey(platform, programId, title)

        // 1. A verified record from a previous launch. Genres, cast and synopsis do
        //    not change once published, so these are good for CACHE_TTL_MS.
        cacheDir(ctx)?.let { dir ->
            readCache(dir, key)?.let { return@withContext it.meta }
            if (readMiss(dir, key)) return@withContext null
        }
        // 2. A miss seen earlier in THIS process (covers a soft-limited burst
        //    without persisting it — see MISS_MEMO_MS).
        synchronized(misses) {
            misses[key]?.let { at ->
                if (System.currentTimeMillis() - at < MISS_MEMO_MS) return@withContext null
                misses.remove(key)
            }
        }

        val fetched = lock.withLock {
            if (System.currentTimeMillis() < blockedUntil) {
                Log.i(TAG, "skipped (blocked): $title")
                return@withLock Outcome.Skipped
            }
            // Sliding-window budget: one lookup costs up to 2 requests (suggest +
            // detail). A refusal is NOT a miss — it leaves no trace, so the same
            // card can be looked up once the window rolls.
            if (!reserve(2)) {
                Log.i(TAG, "skipped (budget): $title")
                return@withLock Outcome.Skipped
            }
            try {
                fetch(platform, programId, title)
            } catch (cancel: CancellationException) {
                // A cancelled coroutine is not "Douban has no such title".
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, "lookup failed: $title", e)
                Outcome.Ambiguous
            }
        }

        val dir = cacheDir(ctx)
        when (fetched) {
            is Outcome.Found ->
                // Reduced = the match rests on the title alone (Douban cropped
                // `vendors`); usable now, but not worth freezing for a month.
                if (!fetched.reduced) dir?.let { writeCache(it, key, Entry(fetched.meta)) }

            // The only case that may be remembered on disk: Douban answered, and
            // this title is not among its candidates.
            Outcome.NotFound -> dir?.let { writeMiss(it, key) }

            // An unreadable answer says nothing about the title. Keep it in memory
            // only, so a burst cannot hide the caption for days.
            Outcome.Ambiguous -> synchronized(misses) { misses[key] = System.currentTimeMillis() }

            // Skipped leaves no trace at all: the card is expected to succeed on a
            // later rotation, once the sliding window has rolled.
            Outcome.Skipped -> Unit
        }
        val meta = (fetched as? Outcome.Found)?.meta
        if (meta == null) Log.i(TAG, "no caption enrichment (${fetched.logName}): $title")
        meta
    }

    /**
     * True when this card's answer is already known, so a prefetch can skip it
     * without spending any of the per-minute budget.
     */
    fun isCached(ctx: Context, platform: String, programId: String, title: String): Boolean {
        val key = cacheKey(platform, programId, title)
        cacheDir(ctx)?.let { dir ->
            if (readCache(dir, key) != null || readMiss(dir, key)) return true
        }
        return synchronized(misses) { misses.containsKey(key) }
    }

    /** Titles looked up and found nothing, for this process only. */
    private val misses = HashMap<String, Long>()

    /**
     * Result of one attempt. The distinction that matters is durability: only
     * [NotFound] may be written to disk. Treating [Skipped] or [Ambiguous] as a
     * miss is what made a rate-limited burst blank the caption for days.
     */
    private sealed class Outcome {
        class Found(val meta: Meta, val reduced: Boolean) : Outcome()
        /** Douban answered, and this title is not among its candidates. */
        object NotFound : Outcome()
        /** Could not ask properly: HTTP error, timeout, cropped payload. */
        object Ambiguous : Outcome()
        /** Budget/blocker refused the request. Leaves no trace at all. */
        object Skipped : Outcome()

        val logName: String
            get() = when (this) {
                is Found -> if (reduced) "reduced" else "found"
                NotFound -> "not found"
                Ambiguous -> "ambiguous"
                Skipped -> "skipped"
            }
    }

    /** True when the last [fetch] only produced an unverifiable title match. */
    private var lastFetchReduced = false

    /* --------------------------- the two requests --------------------------- */

    private suspend fun fetch(platform: String?, programId: String?, title: String): Outcome {
        lastFetchReduced = false
        // suggest() distinguishes the two cases the caller cares about: an empty
        // answer may be a soft limit (Ambiguous), while a non-empty answer that
        // matches nothing is a real NotFound.
        val candidates = suggest(title) ?: return Outcome.Ambiguous
        if (candidates.isEmpty()) {
            Log.i(TAG, "no candidates: $title")
            return Outcome.NotFound
        }
        var best: Meta? = null
        var probed = false
        for ((rank, candidate) in candidates.take(MAX_CANDIDATES).withIndex()) {
            // A body we could not read says nothing about the title.
            val detail = detail(candidate.id) ?: continue
            probed = true
            // Douban reduces `vendors`/`linewatches` on some responses (200, but no
            // list at all). That is "cannot verify", not "does not match" — the
            // title/year path below still applies.
            val verified = programId != null && platform != null &&
                vendorsContain(detail, platform, programId)
            val sameName = sameTitle(detail.optString("title"), title)
            val sameYear = candidate.year.isNotEmpty() && candidate.year == detail.optString("year")
            val confidence = when {
                verified -> 2
                sameName && sameYear -> 1
                // suggest() is Douban's own relevance-ranked search and its first
                // hit was the right subject in every case measured (北上 / 嫁金钗 /
                // 猎狐行动→猎狐·行动 / 三体 / 庆余年 第一季). Accept the first
                // candidate when the year cannot be compared: a series often has
                // no year on one side, and refusing would lose the whole caption.
                //
                // The name must still match EXACTLY once punctuation is stripped:
                // 开心锤锤世界 → 开心锤锤 and 名侦探柯南 → 名侦探柯南：30号杀人事件
                // (a film, not the series) are both rejected here.
                sameName && rank == 0 && (candidate.year.isEmpty() || sameYear) -> 1
                else -> 0
            }
            if (confidence == 0) continue
            val parsed = parse(detail, candidate, idMatched = verified)
            if (parsed.isEmpty) continue
            if (!verified) lastFetchReduced = true
            if (best == null || (best.confidence < confidence)) best = parsed
            if (verified) break // exact program id: no need to look further
        }
        return when {
            best != null -> Outcome.Found(best, lastFetchReduced)
            // Nothing readable at all: treat as "could not ask", never as a miss.
            !probed -> Outcome.Ambiguous
            else -> Outcome.NotFound
        }
    }

    private class Candidate(val id: String, val title: String, val year: String)

    /**
     * Title → candidate subjects.
     *
     * Null means "the question could not be asked" (HTTP error, unreadable body).
     * An empty list means Douban answered and knows no such title. Collapsing the
     * two — which an earlier version did — lets one rate-limited burst mark real
     * titles as永久 missing.
     */
    private suspend fun suggest(title: String): List<Candidate>? {
        val body = get(SUGGEST + encode(title), referer = null) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Candidate(id, o.optString("title"), o.optString("year"))
        }
    }

    private suspend fun detail(id: String): JSONObject? {
        // `/subject/{id}` answers for both films and series, and needs no guess
        // at the type ( `/movie/{id}` 301s to `/tv/{id}` for a series ).
        val body = get(DETAIL + id + "?for_mobile=1", DETAIL_REFERER) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private suspend fun get(url: String, referer: String?): String? {
        val now = System.currentTimeMillis()
        val wait = lastRequestAt + PACING_MS - now
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastRequestAt = System.currentTimeMillis()

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json, */*")
        if (referer != null) c.setRequestProperty("Referer", referer)
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) {
                blockedUntil = System.currentTimeMillis() + BLOCK_MS
                Log.w(TAG, "HTTP $code — pausing Douban lookups for ${BLOCK_MS / 60_000} min")
                return null
            }
            if (code != 200) {
                Log.w(TAG, "HTTP $code $url")
                return null
            }
            return c.inputStream.use { it.readBytes() }.decodeToString()
        } finally {
            c.disconnect()
        }
    }

    /* ------------------------------ parsing ------------------------------ */

    private fun parse(detail: JSONObject, candidate: Candidate, idMatched: Boolean): Meta {
        val rating = detail.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0
        return Meta(
            subjectId = candidate.id,
            genres = strings(detail.optJSONArray("genres")),
            region = strings(detail.optJSONArray("countries")).firstOrNull().orEmpty(),
            year = detail.optString("year").ifEmpty { candidate.year },
            // Only a real score: Douban reports value 0 alongside a
            // null_rating_reason ("暂无评分"), which must not read as 0.0/10.
            rating = if (rating in 0.1..10.0) rating else 0.0,
            cast = names(detail.optJSONArray("actors")),
            intro = detail.optString("intro").trim(),
            idMatched = idMatched,
            confidence = if (idMatched) 2 else 1,
        )
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .map { array.optString(it, "").trim() }
            .filter { it.isNotEmpty() && it != "null" }
    }

    private fun names(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun sameTitle(a: String, b: String): Boolean = norm(a) == norm(b)

    /**
     * Title comparison that ignores punctuation, full-width forms and case.
     *
     * Douban and the streaming services spell the same title differently
     * (`猎狐行动` vs `猎狐·行动`, `ＯＮＥ` vs `one`), and a mismatch here means the
     * caption silently loses its genres, cast and synopsis.
     */
    fun norm(s: String): String = s.lowercase().map { c ->
        // Full-width ASCII (ＦＦ０１-ＦＦ５Ｅ) → its half-width twin.
        if (c in '\uFF01'..'\uFF5E') (c.code - 0xFEE0).toChar() else c
    }.filter { it.isLetterOrDigit() }.joinToString("")

    /* ------------------------ platform program ids ------------------------ */

    /** Which vendor key Douban uses for a shelf source. */
    fun platformOfPackage(pkg: String): String? = when (pkg) {
        "com.ktcp.video" -> "qq"
        "com.gitvdemo.video", "com.gitvvideo.oppo" -> "iqiyi"
        "com.cibn.tv" -> "youku"
        else -> null // Bilibili UGC / Douyu live: no Douban subject to match
    }

    /**
     * True when Douban's own vendor/linewatch list carries [programId].
     *
     * Reuses the same URI grammar CinePath uses: the id lives in the query
     * string (`cid` / `cover_id` / `aid` / `albumId` / `showid` / `clipId`) or in
     * the path (`/x/cover/{id}`, `/show_page/id_z{id}.html`). A `link2/?url=`
     * redirect is unwrapped first.
     */
    fun vendorsContain(detail: JSONObject, platform: String, programId: String): Boolean {
        detail.optJSONArray("vendors")?.let { array ->
            for (i in 0 until array.length()) {
                val v = array.optJSONObject(i) ?: continue
                if (v.optString("id") != platform) continue
                if (idInUri(platform, v.optString("uri"), programId)) return true
                if (idInUri(platform, v.optString("url"), programId)) return true
            }
        }
        detail.optJSONArray("linewatches")?.let { array ->
            for (i in 0 until array.length()) {
                val w = array.optJSONObject(i) ?: continue
                val source = w.optJSONObject("source")?.optString("literal").orEmpty()
                if (source != platform && source != "mango") continue
                if (idInUri(platform, w.optString("source_uri"), programId)) return true
                if (idInUri(platform, w.optString("url"), programId)) return true
            }
        }
        return false
    }

    /**
     * Does this link carry [programId] for [platform]?
     *
     * Same grammar CinePath uses: the id is a query parameter (`cid`/`cover_id`,
     * `aid`/`albumId`, `showid`, `clipId`) or part of the path
     * (`/x/cover/{id}`, `/show_page/id_z{id}.html`, `/b/{id}/`), and a
     * `douban.com/link2/?url=` redirect is unwrapped first.
     */
    fun idInUri(platform: String, raw: String?, programId: String): Boolean {
        if (raw.isNullOrEmpty() || programId.isEmpty()) return false
        val keys = when (platform) {
            "qq" -> listOf("cid", "cover_id", "coverid")
            "iqiyi" -> listOf("aid", "albumId", "albumid")
            "youku" -> listOf("showid", "showId", "show_id")
            "mgtv" -> listOf("clipId", "clipid", "cid")
            else -> return false
        }
        val path = when (platform) {
            "qq" -> Regex("/x/cover/([A-Za-z0-9_-]+)")
            "youku" -> Regex("/show_page/id_z([A-Za-z0-9_-]+)\\.html")
            "mgtv" -> Regex("/b/([0-9]+)/")
            else -> null
        }
        var value = raw.replace("&amp;", "&")
        repeat(3) {
            val nested = param(value, "url") ?: return@repeat
            if (nested == value) return@repeat
            value = nested
        }
        if (keys.any { param(value, it) == programId }) return true
        return path?.find(value)?.groupValues?.getOrNull(1) == programId
    }

    private fun param(s: String, key: String): String? {
        val m = Regex("[?&]" + Regex.escape(key) + "=([^&#]+)").find(s) ?: return null
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /* ------------------------------- cache ------------------------------- */

    private fun cacheKey(platform: String?, programId: String?, title: String): String =
        "${platform.orEmpty()}:${programId.orEmpty()}:${norm(title)}"

    private fun cacheDir(ctx: Context): File? = runCatching {
        cacheDir ?: File(ctx.cacheDir, "douban").apply { mkdirs() }.also { cacheDir = it }
    }.getOrNull()

    /**
     * A hit from an earlier launch. Misses are never stored on disk (see
     * [MISS_MEMO_MS]) so only positive entries are read back here.
     *
     * An unrated record expires sooner than a rated one: its genres/cast/intro are
     * as stable as any, but 暂无评分 becomes a real score within days.
     */
    private fun readCache(dir: File, key: String): Entry? {
        val file = File(dir, fileName(key))
        if (!file.exists()) return null
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (o == null || !o.optBoolean("hit")) {
            file.delete()
            return null
        }
        val age = System.currentTimeMillis() - file.lastModified()
        val unrated = o.optDouble("rating", 0.0) <= 0.0
        if (age > if (unrated) UNRATED_TTL_MS else CACHE_TTL_MS) {
            file.delete()
            return null
        }
        return Entry(
            Meta(
                subjectId = o.optString("subjectId"),
                genres = strings(o.optJSONArray("genres")),
                region = o.optString("region"),
                year = o.optString("year"),
                rating = o.optDouble("rating", 0.0),
                cast = strings(o.optJSONArray("cast")),
                intro = o.optString("intro"),
                idMatched = o.optBoolean("idMatched"),
                confidence = o.optInt("confidence"),
            )
        )
    }

    /** True when [key] was written off as "no such title" in a previous launch. */
    private fun readMiss(dir: File, key: String): Boolean {
        val file = File(dir, missName(key))
        if (!file.exists()) return false
        if (System.currentTimeMillis() - file.lastModified() > MISS_TTL_MS) {
            file.delete()
            return false
        }
        return true
    }

    private fun writeCache(dir: File, key: String, entry: Entry) {
        runCatching {
            val m = entry.meta
            val o = JSONObject().put("hit", m != null)
            if (m != null) {
                o.put("subjectId", m.subjectId)
                    .put("genres", JSONArray(m.genres))
                    .put("region", m.region)
                    .put("year", m.year)
                    .put("rating", m.rating)
                    .put("cast", JSONArray(m.cast))
                    .put("intro", m.intro)
                    .put("idMatched", m.idMatched)
                    .put("confidence", m.confidence)
            }
            File(dir, fileName(key)).writeText(o.toString())
            // Misses are cached too (negative caching), so a title Douban does
            // not know is not asked again on every launch.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(CACHE_MAX)?.forEach { it.delete() }
        }
    }

    /** Records "Douban answered, and has no such title" for [MISS_TTL_MS]. */
    private fun writeMiss(dir: File, key: String) {
        runCatching {
            File(dir, missName(key)).writeText("{}")
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(CACHE_MAX * 2)
                ?.forEach { it.delete() }
        }
    }

    private fun fileName(key: String) = Integer.toHexString(key.hashCode()) + ".json"

    private fun missName(key: String) = Integer.toHexString(key.hashCode()) + ".miss"
}
