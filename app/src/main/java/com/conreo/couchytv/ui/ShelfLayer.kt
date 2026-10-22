package com.conreo.couchytv.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.data.Shelf
import kotlinx.coroutines.delay

/**
 * Which app is currently focused on the home screen. Written by [AppCard] on
 * focus, read only by the shelf — keeping it out of LauncherApp's own
 * composition scope means moving the D-pad never recomposes the grid.
 */
@Stable
class ShelfFocus {
    var pkg by mutableStateOf<String?>(null)
}

/**
 * Where the dock's glass panel actually ended up, reported by the dock itself
 * (layout, not duplicated arithmetic). The shelf's text column uses it so the
 * copy sits IN the empty band above the icons and shares their left edge,
 * instead of hugging the bottom-left corner of the screen.
 */
@Stable
class ShelfAnchor {
    /** Left edge of the first dock icon. */
    var start by mutableStateOf(44.dp)

    /** Distance from the screen's bottom to the spot just above the panel. */
    var bottom by mutableStateOf(150.dp)
}

val LocalShelfFocus = staticCompositionLocalOf<ShelfFocus?> { null }

/** Seconds each picture stays before the shelf moves on. */
private const val SHELF_SECONDS = 8

/** How long the focus must rest before the shelf takes over the wallpaper —
 *  sliding along the dock must not strobe full-screen pictures. */
private const val FOCUS_SETTLE_MS = 350L

/**
 * Top Shelf: while a dock app with a shelf source (Bilibili, Douyu, iQIYI,
 * Tencent Video, Youku) is focused, its hot titles replace the wallpaper behind
 * the dock, with the title above the icons. Ported from the Tigris launcher.
 */
@Composable
fun ShelfLayer(
    focus: ShelfFocus,
    anchor: ShelfAnchor,
    enabled: Boolean,
    suppressed: Boolean,
    scrim: Brush?,
) {
    val pkg = focus.pkg
    val want = if (enabled && !suppressed && pkg != null && Shelf.has(pkg)) pkg else null

    var shownPkg by remember { mutableStateOf<String?>(null) }
    // Kept after [shownPkg] clears so the exit fade still has content to draw.
    var lastPkg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(want) {
        if (want == null) shownPkg = null
        else {
            delay(FOCUS_SETTLE_MS)
            shownPkg = want
        }
    }
    shownPkg?.let { lastPkg = it }

    AnimatedVisibility(
        visible = shownPkg != null,
        enter = fadeIn(tween(450)),
        exit = fadeOut(tween(320)),
    ) {
        lastPkg?.let { ShelfContent(it, scrim, anchor) }
    }
}

@Composable
private fun ShelfContent(pkg: String, scrim: Brush?, anchor: ShelfAnchor) {
    val ctx = LocalContext.current
    var items by remember(pkg) { mutableStateOf<List<Shelf.Item>>(emptyList()) }
    var shown by remember(pkg) { mutableStateOf<Pair<Shelf.Item, Bitmap>?>(null) }
    var index by remember(pkg) { mutableIntStateOf(0) }

    // First picture: fetch the list, decode the cover, warm up the next one.
    LaunchedEffect(pkg) {
        val list = Shelf.items(pkg)
        items = list
        val first = list.firstOrNull() ?: return@LaunchedEffect
        val bmp = Shelf.image(ctx, first) ?: return@LaunchedEffect
        shown = first to bmp
        list.getOrNull(1)?.let { Shelf.image(ctx, it) }
    }
    // Rotate. A picture that fails to load is skipped, not fatal.
    LaunchedEffect(pkg, items) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(SHELF_SECONDS * 1000L)
            val next = (index + 1) % items.size
            val item = items[next]
            val bmp = Shelf.image(ctx, item) ?: continue
            index = next
            shown = item to bmp
            Shelf.image(ctx, items[(next + 1) % items.size])
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The still crossfades under the dock. Nothing is drawn until the first
        // picture is ready, so a failed fetch leaves the wallpaper untouched.
        Crossfade(targetState = shown, animationSpec = tween(700), label = "shelfPic") { state ->
            if (state != null) {
                // Settle from a slight zoom, like a camera coming to rest.
                var settled by remember(state) { mutableStateOf(false) }
                LaunchedEffect(state) { settled = true }
                val zoom by animateFloatAsState(
                    targetValue = if (settled) 1f else 1.045f,
                    animationSpec = tween(2200, easing = FastOutSlowInEasing),
                    label = "shelfZoom",
                )
                Image(
                    bitmap = state.second.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
                // Same scrim the wallpaper gets, applied over the picture instead
                // of under it — the shelf replaces the wallpaper, so it takes over
                // its readability duty too.
                if (scrim != null) Box(Modifier.fillMaxSize().background(scrim))
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                // Clear of the dock row + its 2nd-row peek, so the text sits in
                // the empty band above the icons, like the Top Shelf on tvOS.
                .padding(start = anchor.start, end = 24.dp, bottom = anchor.bottom)
                .fillMaxWidth(0.62f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Crossfade(targetState = shown?.first, animationSpec = tween(450), label = "shelfText") { item ->
                if (item == null) {
                    Box(Modifier)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val source = Shelf.sourceLabel(pkg)
                        if (source != 0) {
                            Text(
                                text = stringResource(source),
                                style = MaterialTheme.typography.labelLarge.copy(shadow = ShelfShadow),
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                shadow = ShelfShadow,
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.meta,
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = ShelfShadow),
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (items.size > 1) {
                // A translucent pill behind the dots: inactive dots at 35% white
                // vanish over a bright still, and these covers get very bright.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    repeat(items.size) { k ->
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (k == index) 1f else 0.35f))
                        )
                    }
                }
            }
        }
    }
}

/** Soft drop shadow so white text stays readable over a bright still. */
private val ShelfShadow = Shadow(
    color = Color.Black.copy(alpha = 0.7f),
    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
    blurRadius = 12f,
)
