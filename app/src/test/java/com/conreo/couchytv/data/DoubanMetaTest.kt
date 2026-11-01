package com.conreo.couchytv.data

import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching a shelf card to the right Douban subject.
 *
 * These are the rules that decide whether a caption shows Douban data at all, so
 * they are pure and tested here instead of on a TV: the platform program id (the
 * card's own `cid`/`aid`/`showid`) beats a title match, a title match needs the
 * year, and an unverified candidate is never used.
 */
class DoubanMetaTest {

    /* ------------------------ program id extraction ------------------------ */

    @Test
    fun `tencent cid is read from the query and from the cover path`() {
        assertTrue(
            DoubanMeta.idInUri(
                "qq",
                "txvideo://v.qq.com/VideoDetailActivity?cid=mzc002001w361jz&vid=n410233uw3o",
                "mzc002001w361jz",
            )
        )
        assertTrue(
            DoubanMeta.idInUri(
                "qq",
                "https://v.qq.com/x/cover/mzc002001w361jz.html",
                "mzc002001w361jz",
            )
        )
        // The episode id is not the programme id.
        assertFalse(
            DoubanMeta.idInUri(
                "qq",
                "txvideo://v.qq.com/VideoDetailActivity?cid=mzc002001w361jz&vid=n410233uw3o",
                "n410233uw3o",
            )
        )
    }

    @Test
    fun `iqiyi album and youku show ids are read`() {
        assertTrue(
            DoubanMeta.idInUri(
                "iqiyi",
                "iqiyi://mobile/player?aid=6127444098499701&tvid=6144289583774200&ftype=27",
                "6127444098499701",
            )
        )
        assertTrue(
            DoubanMeta.idInUri(
                "youku",
                "youku://play?source=00003036&showid=bcaa87c7834c46f99e7b&refer=esfhz",
                "bcaa87c7834c46f99e7b",
            )
        )
    }

    @Test
    fun `a douban link2 redirect is unwrapped before matching`() {
        val wrapped = "https://www.douban.com/link2/?url=https%3A%2F%2Fv.qq.com%2Fx%2Fcover" +
            "%2Fmzc002001w361jz.html"
        assertTrue(DoubanMeta.idInUri("qq", wrapped, "mzc002001w361jz"))
    }

    @Test
    fun `a foreign host carrying our id in its query does not match`() {
        // Only the id is compared here, so this documents the limit of the check:
        // the vendor entry's own `id` field must say "qq" first (see vendorsContain).
        val detail = JSONObject(
            """{"vendors":[{"id":"youku","uri":"txvideo://x?cid=mzc002001w361jz"}]}"""
        )
        assertFalse(DoubanMeta.vendorsContain(detail, "qq", "mzc002001w361jz"))
        assertFalse(DoubanMeta.idInUri("bilibili", "bilibili://pgc/season/93127", "93127"))
    }

    @Test
    fun `linewatches are matched through source literal`() {
        val detail = JSONObject(
            """{"linewatches":[{"source":{"literal":"qq"},
               "source_uri":"txvideo://v.qq.com/VideoDetailActivity?cid=mzc00200p1ilkw3"}]}"""
        )
        assertTrue(DoubanMeta.vendorsContain(detail, "qq", "mzc00200p1ilkw3"))
        assertFalse(DoubanMeta.vendorsContain(detail, "iqiyi", "mzc00200p1ilkw3"))
    }

    /* ---------------------------- title normalising ---------------------------- */

    @Test
    fun `title comparison ignores punctuation, width and case`() {
        // The real mismatch that title matching has to survive.
        assertEquals(DoubanMeta.norm("猎狐行动"), DoubanMeta.norm("猎狐·行动"))
        assertEquals(DoubanMeta.norm("Wonder Woman"), DoubanMeta.norm("wonder  woman"))
        assertEquals(DoubanMeta.norm("ＯＮＥ"), DoubanMeta.norm("one"))
        assertFalse(DoubanMeta.norm("北上") == DoubanMeta.norm("一路北上"))
    }

    /* ------------------------------ empty results ------------------------------ */

    @Test
    fun `a meta with nothing in it is reported as empty`() {
        assertTrue(DoubanMeta.Meta(subjectId = "1").isEmpty)
        assertFalse(DoubanMeta.Meta(subjectId = "1", rating = 8.1).isEmpty)
        assertFalse(DoubanMeta.Meta(subjectId = "1", genres = listOf("剧情")).isEmpty)
    }

    /* -------------------------- which apps can match -------------------------- */

    @Test
    fun `only the video apps with a Douban subject are queried`() {
        assertEquals("qq", DoubanMeta.platformOfPackage("com.ktcp.video"))
        assertEquals("iqiyi", DoubanMeta.platformOfPackage("com.gitvdemo.video"))
        assertEquals("iqiyi", DoubanMeta.platformOfPackage("com.gitvvideo.oppo"))
        assertEquals("youku", DoubanMeta.platformOfPackage("com.cibn.tv"))
        // Bilibili UGC and Douyu live rooms are not Douban subjects.
        assertEquals(null, DoubanMeta.platformOfPackage("com.xiaodianshi.tv.yst"))
        assertEquals(null, DoubanMeta.platformOfPackage("com.douyu.xl.douyutv"))
    }

    /* ------------------------- config compatibility ------------------------- */

    @Test
    fun `the Douban switch is opt-in, and an older config still loads`() {
        // Privacy default: a config file written before this feature existed has
        // no `shelfDoubanMeta` key, and must come back OFF — not error, not ON.
        assertFalse(LauncherConfig().shelfDoubanMeta)
        val json = Json { ignoreUnknownKeys = true }
        val older = json.decodeFromString<LauncherConfig>("""{"shelfEnabled":true,"layout":2}""")
        assertTrue(older.shelfEnabled)
        assertFalse(older.shelfDoubanMeta)
    }

    /* --------------------------- request budgeting --------------------------- */

    @Test
    fun `the window allows six lookups a minute, then refuses`() {
        // Measured: Douban's `subject_suggest` starts answering `[]` somewhere
        // between 6 and 8 lookups per minute (2 requests each). The budget must
        // sit under that, and must recover as entries leave the window.
        val sent = ArrayDeque<Long>()
        val now = 1_000_000L
        assertEquals(12, DoubanMeta.available(sent, now)) // 6 lookups, 2 requests each
        // Four request slots consumed = two lookups → two more lookups fit.
        repeat(4) { sent.addLast(now) }
        assertEquals(8, DoubanMeta.available(sent, now))
        // Eight consumed = four lookups → two left, i.e. exactly one more.
        repeat(4) { sent.addLast(now) }
        assertEquals(4, DoubanMeta.available(sent, now))
        // Ten consumed = five lookups → one lookup's worth (2) remains, and after
        // it the window is empty.
        repeat(2) { sent.addLast(now + 30_000) }
        assertEquals(2, DoubanMeta.available(sent, now + 30_000))
        repeat(2) { sent.addLast(now + 30_000) }
        assertEquals(0, DoubanMeta.available(sent, now + 30_000))
    }

    @Test
    fun `requests leave the window after a minute`() {
        val sent = ArrayDeque<Long>()
        val t0 = 1_000_000L
        repeat(12) { sent.addLast(t0) }
        assertEquals(0, DoubanMeta.available(sent, t0 + 1_000))
        // Just inside the window: still blocked.
        assertEquals(0, DoubanMeta.available(sent, t0 + 60_000))
        // Past it: the whole burst expires together.
        assertEquals(12, DoubanMeta.available(sent, t0 + 60_001))
    }

    /* ------------------- every video source can be matched ------------------- */

    @Test
    fun `the video sources that have a Douban subject attach a program id`() {
        // Regression: the shipped 1.0.7 build left Tencent's cards without
        // programIds, so the lookup was skipped silently and the caption stayed
        // platform-only for the one app whose listing carries no synopsis or cast.
        //
        // A source that CAN be matched must hand over its id; one that cannot must
        // not, or every card would spend two requests only to fail.
        val src = java.io.File("src/main/java/com/conreo/couchytv/data/Shelf.kt").readText()

        /** Body of `private fun <name>(` up to the next top-level function. */
        fun body(name: String): String {
            val marker = "private fun $name("
            val start = src.indexOf(marker)
            require(start >= 0) { "$name not found" }
            val next = Regex("\\n    private (fun|val) ").find(src, start + marker.length)
            return src.substring(start, next?.range?.first ?: src.length)
        }

        // Comments mention programIds too ("programIds stays empty"), so strip
        // them before looking for the assignment.
        fun code(name: String): String =
            body(name).lineSequence()
                .map { it.substringBefore("//") }
                .joinToString("\n")
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

        listOf("iqiyi", "tencent", "youku").forEach { fn ->
            assertTrue("$fn must set programIds on its Items", code(fn).contains("programIds ="))
        }
        listOf("bilibili", "douyu").forEach { fn ->
            assertFalse("$fn must not set programIds", code(fn).contains("programIds"))
        }
    }

    /* -------------------- iQIYI stills must be full-size -------------------- */

    @Test
    fun `iqiyi covers are upgraded to the 2560 rendition`() {
        // Regression: `img_size=_2560_1440` in the request is only honoured for
        // part of iQIYI's catalogue. The rest answer with a 120×160 portrait
        // preview, which Shelf.decode() rejects (min width 480) — the card then
        // cannot be drawn while still owning a page dot, so the dots stopped
        // matching the pictures. Measured on 11 entries: 2 usable before, 10 after.
        val plain = "https://pic5.iqiyipic.com/image/20261009/f0/68/a_100688277_m_601_m6.avif"
        assertEquals(
            "https://pic5.iqiyipic.com/image/20261009/f0/68/a_100688277_m_601_m6_2560_1440.jpg",
            Shelf.landscapeIqiyi(plain),
        )
        // Already full-size: left alone, and .avif 换成 .jpg（Android 12 以下解不了 AVIF）。
        val big = "https://pic3.iqiyipic.com/image/20251010/9d/64/v_184030615_m_601_m5_2560_1440.avif"
        assertEquals(
            "https://pic3.iqiyipic.com/image/20251010/9d/64/v_184030615_m_601_m5_2560_1440.jpg",
            Shelf.landscapeIqiyi(big),
        )
        // http → https, since the app only loads https images.
        assertTrue(
            Shelf.landscapeIqiyi("http://pic5.iqiyipic.com/x/a_m_601_m6.avif")
                .startsWith("https://"),
        )
    }
}
