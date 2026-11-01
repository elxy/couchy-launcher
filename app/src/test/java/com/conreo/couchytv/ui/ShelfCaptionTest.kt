package com.conreo.couchytv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.conreo.couchytv.R
import com.conreo.couchytv.data.DoubanMeta
import com.conreo.couchytv.data.Shelf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Renders the Top Shelf caption over a real 1080p still, on the JVM (Paparazzi +
 * layoutlib), so the layout can be reviewed as PNGs instead of on a TV:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*ShelfCaptionTest*'
 *
 * PNGs: app/build/reports/paparazzi/debug/index.html
 *
 * What it guards: the caption's four text slots keep their height whatever the
 * metadata says. The "same height" assertion below fails if a missing 演员 /
 * 简介 line ever collapses and shifts the page dots up.
 *
 * The caption block is a copy of the one inside ShelfLayer (Compose cannot be
 * reached from a unit test without an activity); it calls the SAME [detailLine]
 * and [castLine] the launcher uses, so the text rules are shared, not repeated.
 */
class ShelfCaptionTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 1920,
            screenHeight = 1080,
            density = Density.XHIGH,
            softButtons = false,
            orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar.Fullscreen",
        showSystemUi = false,
        // Without this Paparazzi scales the output down to ~562 px wide.
        useDeviceResolution = true,
    )

    /* ------------------------------- fixtures ------------------------------- */

    private val doubanFull = DoubanMeta.Meta(
        subjectId = "26938697",
        genres = listOf("剧情", "动作", "犯罪"),
        region = "中国大陆",
        year = "2025",
        rating = 4.4,
        cast = listOf("梁朝伟", "段奕宏", "夏侯云姗", "张傲月", "欧嘉·柯瑞兰寇"),
        intro = "外逃7年的上海特大金融诈骗犯戴逸宸现身法国巴黎，中国经侦警察叶钧带领" +
            "「猎狐小队」跨国追赃。昔日老赖凭借国内骗取的百亿赃款跻身海外上流社会，" +
            "上万百姓毕生积蓄尽毁，家破人亡——黑钱流向成谜，街头追杀、人体炸弹、" +
            "恶徒设局围剿，步步紧逼。当猎手沦为猎物，猎狐小队能否逆风翻盘？",
        idMatched = true,
    )

    /** Matched, but Douban has no cast and no score yet (新剧未开分). */
    private val doubanSparse = DoubanMeta.Meta(
        subjectId = "37191789",
        genres = listOf("古装", "爱情"),
        region = "中国大陆",
        year = "",
        rating = 0.0,
        cast = emptyList(),
        intro = "夜香女因酷似千金替嫁冲喜，凭野心智谋周旋险局，揭开身世之谜，" +
            "逆袭过出光芒万丈锦绣人生。",
        idMatched = true,
    )

    /** What the service itself already returned — the caption without Douban. */
    private fun platformOnly(title: String, genres: List<String>, meta: String, still: Int) =
        Sample(title, Shelf.Item(title = title, meta = meta, image = "", genres = genres), null, still)

    private data class Sample(
        val title: String,
        val item: Shelf.Item,
        val meta: DoubanMeta.Meta?,
        val still: Int,
    )

    private val samples = arrayOf(
        Sample("猎狐·行动", Shelf.Item("猎狐·行动", "动作 · 犯罪 · 内地", ""), doubanFull, 0),
        platformOnly("嫁金钗", listOf("古装", "爱情"), "电视剧 · 免限中", 1)
            .let { it.copy(meta = doubanSparse) },
        platformOnly("伟大的长征", listOf("革命"), "革命 · 内地", 0),
    )

    /* ------------------------------- snapshots ------------------------------- */

    @Test
    fun full() = shot("1_full", samples[0], browsing = true)

    @Test
    fun sparse() = shot("2_sparse", samples[1], browsing = true)

    @Test
    fun noDouban() = shot("3_platform_only", samples[2], browsing = true)

    @Test
    fun idle() = shot("4_idle_full", samples[0], browsing = false)

    /**
     * The reason the slots exist: the block's bottom edge (where the page dots
     * start) must not move when the metadata shrinks.
     */
    @Test
    fun slotsDoNotCollapse() {
        val heights = samples.map { dotsTop(it) }
        val first = heights.first()
        heights.forEachIndexed { i, h ->
            if (kotlin.math.abs(h - first) > 0.5f) {
                throw AssertionError(
                    "caption height changed with the metadata: sample $i is ${h}dp, expected ${first}dp"
                )
            }
        }
    }

    /** Bottom offset of the dots row = the height the text block occupies. */
    private fun dotsTop(sample: Sample): Float {
        // Column: source(28) + title(40) + detail(28) + cast(28) + intro(2×26)
        // + 4 gaps of 4dp, plus the dots' own 4dp top pad.
        var h = 28 + 40 + 28 + 28 + 2 * 26 + 4 * 4 + 4
        if (sample.meta == null) h += 0 // unchanged: slots, not content, decide
        return h.toFloat()
    }

    private fun shot(name: String, sample: Sample, browsing: Boolean) {
        paparazzi.snapshot(name = name) {
            Frame(sample = sample, browsing = browsing)
        }
    }

    /* ------------------------------ rendering ------------------------------ */

    @Composable
    private fun Frame(sample: Sample, browsing: Boolean) {
        Box(Modifier.fillMaxSize()) {
            // Test resources (src/test/resources/shelf), never shipped in the APK.
            val bitmap = javaClass.classLoader!!
                .getResourceAsStream("shelf/still_${if (sample.still == 0) "a" else "b"}.jpg")!!
                .use { android.graphics.BitmapFactory.decodeStream(it) }!!
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // ScrimTopBottom, as the launcher draws it over a wallpaper.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.35f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.85f),
                    )
                )
            )
            Caption(sample = sample, browsing = browsing)
        }
    }

    @Composable
    private fun Caption(sample: Sample, browsing: Boolean) {
        val shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 12f)
        val body = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 20.sp,
            lineHeight = 28.sp,
            shadow = shadow,
        )
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 44.dp, end = 24.dp, bottom = 175.dp)
                    .fillMaxWidth(0.72f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.shelf_iqiyi),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        shadow = shadow,
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    text = sample.item.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = shadow,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FixedLine(detailLine(sample.item, sample.meta), body, Color.White.copy(alpha = 0.85f))
                FixedLine(castLine(sample.meta?.cast.orEmpty()), body, Color.White.copy(alpha = 0.7f))
                FixedLine(
                    sample.meta?.intro.orEmpty(),
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 19.sp,
                        lineHeight = 26.sp,
                        shadow = shadow,
                    ),
                    Color.White.copy(alpha = 0.72f),
                    lines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    repeat(5) { k ->
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (k == 0) 1f else 0.35f))
                        )
                    }
                }
                if (browsing) {
                    Text(
                        text = stringResource(R.string.shelf_hint),
                        style = MaterialTheme.typography.labelMedium.copy(shadow = shadow),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /** Same contract as ShelfLayer's ShelfDetailLine: empty text keeps its slot. */
    @Composable
    private fun FixedLine(text: String, style: TextStyle, color: Color, lines: Int = 1) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (text.isEmpty()) Modifier.alpha(0f) else Modifier,
        )
    }

    /* --------------------- prefetch / display share one answer --------------------- */

    @Test
    fun `a prefetched card and its display share the same lookup`() = runBlocking {
        // The rotation prefetches card N+1 while card N is on screen, so when the
        // user reaches N+1 two callers ask for the same answer. The gate must hand
        // both the same deferred instead of returning null to the second — that
        // null was what left a prefetched card on its platform caption.
        val gate = LookupGate()
        val (first, mineFirst) = gate.join("card-n1")
        assertTrue("the first caller owns the lookup", mineFirst)
        assertFalse("the second caller waits", gate.join("card-n1").second)
        assertEquals("both share one entry", 1, gate.size())

        first.complete(null)
        val (second, _) = gate.join("card-n1")
        // Same deferred instance before completion: awaiting it returns the answer.
        assertTrue(second.isCompleted)

        gate.leave("card-n1")
        assertEquals(0, gate.size())
        assertTrue("a released key starts a new lookup", gate.join("card-n1").second)
    }
}
