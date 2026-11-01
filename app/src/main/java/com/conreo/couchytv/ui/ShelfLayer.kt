package com.conreo.couchytv.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.DoubanMeta
import com.conreo.couchytv.data.Shelf
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

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

/**
 * The shelf's interactive state, shared by the dock (which enters it), the
 * shelf itself (which renders it) and the status bar (which is one press up).
 *
 * [active] means the user pressed Up on a playable dock app: the D-pad now
 * browses the stills (Left/Right), OK launches the selected title and the 8 s
 * auto-rotation is paused. Cleared by Down/Back (back to the dock card) or by
 * any overlay taking over the screen.
 */
@Stable
class ShelfUi {
    var active by mutableStateOf(false)

    /** Package whose shelf is open — stays set after leaving, so returning
     *  focus to the same dock card needs no extra bookkeeping. */
    var pkg by mutableStateOf<String?>(null)

    /** Selected still; also drives auto-rotation when [active] is false. */
    var index by mutableIntStateOf(0)

    /** Items of [itemsPkg] — the dock checks these before entering. */
    var items by mutableStateOf<List<Shelf.Item>>(emptyList())
    var itemsPkg by mutableStateOf<String?>(null)

    fun enter(p: String) {
        if (pkg != p) index = 0
        pkg = p
        active = true
    }

    fun exit() {
        active = false
    }

    /**
     * True while a still is actually drawn — the focus belongs to a shelf source
     * and its first picture has arrived. The launcher reads it to let the
     * expanded dock grid use that picture as its backdrop instead of the
     * wallpaper, and to skip blurring the wallpaper underneath it.
     */
    var showing by mutableStateOf(false)
}

val LocalShelfFocus = staticCompositionLocalOf<ShelfFocus?> { null }

/** Seconds each picture stays before the shelf moves on. */
private const val SHELF_SECONDS = 8

/** How long the focus must rest before the shelf takes over the wallpaper —
 *  sliding along the dock must not strobe full-screen pictures. */
private const val FOCUS_SETTLE_MS = 350L

/**
 * Longest the caption waits for Douban before being drawn with the streaming
 * service's own fields. One lookup measures ~0.9 s (suggest ~250 ms, 350 ms
 * pacing, detail ~600 ms); a disk-cache hit returns in ~0.
 */
private const val DOUBAN_BUDGET_MS = 2500L

/** The still's crossfade. A caption settled inside it is published invisibly. */
private const val SHELF_FADE_MS = 700L

/**
 * Top Shelf: while a dock app with a shelf source (Bilibili, Douyu, iQIYI,
 * Tencent Video, Youku) is focused, its hot titles replace the wallpaper behind
 * the dock, with the title above the icons. Ported from the Tigris launcher.
 *
 * Pressing Up on a playable dock app enters [ShelfUi.active]: the stills become
 * browsable (Left/Right, wrapping), OK launches the title through the app's own
 * schema URI, Down goes back to the dock and Up moves on to the status-bar
 * buttons.
 *
 * When there is no shelf content to show — the focused app has no source at all
 * (e.g. 央视频) or its listing came back empty — nothing is drawn over the
 * wallpaper: the band above the dock stays clean, with no caption and no dots.
 */
@Composable
fun ShelfLayer(
    focus: ShelfFocus,
    anchor: ShelfAnchor,
    enabled: Boolean,
    /** Opt-in Douban enrichment of the caption (see LauncherConfig). */
    doubanEnabled: Boolean,
    /** Full-screen takeover (move mode): the stills leave the screen entirely. */
    suppressed: Boolean,
    /**
     * The expanded dock grid. It covers the whole screen, so the stills stay —
     * as its backdrop — instead of dropping back to the wallpaper, which used
     * to flash a different picture the moment Down was pressed. Browse mode
     * still ends: the grid owns the D-pad.
     */
    drawerOpen: Boolean,
    scrim: Brush?,
    mode: ShelfUi,
    browseFocus: FocusRequester,
    onPlay: (Shelf.Item) -> Unit,
    onUp: () -> Unit,
    onExitToDock: () -> Unit,
) {
    var shownPkg by remember { mutableStateOf<String?>(null) }
    // Kept after [shownPkg] clears so the exit fade still has content to draw.
    var lastPkg by remember { mutableStateOf<String?>(null) }

    val pkg = if (mode.active) mode.pkg else focus.pkg
    val want = if (enabled && !suppressed && pkg != null && Shelf.has(pkg)) pkg else null

    LaunchedEffect(want, mode.active, drawerOpen) {
        // The expanded dock grid is opaque, and while it is up the focused
        // package is gone (focus lives in the grid) — so the picture that was
        // already on screen is FROZEN as its backdrop rather than dissolved
        // back to the wallpaper and re-fetched on the way out.
        if (drawerOpen) return@LaunchedEffect
        if (want == null) shownPkg = null
        else {
            // Entering browse mode happens on an already-visible shelf — no wait.
            if (!mode.active) delay(FOCUS_SETTLE_MS)
            shownPkg = want
        }
    }
    shownPkg?.let { lastPkg = it }

    // Settings, the context menu, move mode and the expanded grid all own the
    // screen — drop browse mode rather than leaving it focused underneath, and
    // hand focus back to the dock card once the overlay is gone (the browse box
    // taking focus with it would otherwise leave the launcher with none).
    var refocusAfterOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, suppressed, drawerOpen) {
        if (!enabled) {
            mode.exit()
            return@LaunchedEffect
        }
        if (suppressed || drawerOpen) {
            if (mode.active) {
                mode.exit()
                refocusAfterOverlay = true
            }
        } else if (refocusAfterOverlay) {
            refocusAfterOverlay = false
            // The overlay is a different window; it keeps the input focus for a
            // few frames after it leaves the composition, so retry briefly.
            repeat(6) {
                delay(100)
                onExitToDock()
            }
        }
    }
    // Leaving the launcher (a title was launched, HOME, settings…) ends browse
    // mode and puts focus back on the dock card, so returning home never lands
    // on a focused node that no longer exists. ON_PAUSE, not ON_STOP: a target
    // app that dies on launch never lets us reach STOPPED.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && mode.active) {
                onExitToDock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = mode.active) { onExitToDock() }

    AnimatedVisibility(
        visible = shownPkg != null,
        enter = fadeIn(tween(450)),
        exit = fadeOut(tween(320)),
    ) {
        lastPkg?.let {
            ShelfContent(
                pkg = it,
                scrim = scrim,
                anchor = anchor,
                mode = mode,
                doubanEnabled = doubanEnabled,
                // Behind the drawer only the picture remains: its text belongs
                // to the dock band, which the grid covers.
                showInfo = !drawerOpen,
                drawerOpen = drawerOpen,
                browseFocus = browseFocus,
                onPlay = onPlay,
                onUp = onUp,
                onExitToDock = onExitToDock,
            )
        }
    }

    // No shelf content (no source for this app, or its listing failed) → the
    // wallpaper stays clean, with nothing drawn over it.
    val noShelf = shownPkg == null || (mode.itemsPkg == shownPkg && mode.items.isEmpty())
    val shelfOn = !noShelf && !suppressed
    // Published for the launcher (the dock grid uses the still as its backdrop
    // and skips blurring the wallpaper underneath it). In a side effect, not in
    // the composition body: the reader is an ancestor.
    androidx.compose.runtime.SideEffect { if (mode.showing != shelfOn) mode.showing = shelfOn }
}

@Composable
private fun ShelfContent(
    pkg: String,
    scrim: Brush?,
    anchor: ShelfAnchor,
    mode: ShelfUi,
    doubanEnabled: Boolean,
    showInfo: Boolean,
    drawerOpen: Boolean,
    browseFocus: FocusRequester,
    onPlay: (Shelf.Item) -> Unit,
    onUp: () -> Unit,
    onExitToDock: () -> Unit,
) {
    val ctx = LocalContext.current
    var items by remember(pkg) { mutableStateOf<List<Shelf.Item>>(emptyList()) }
    // The still AND its caption, as one unit. They used to be published
    // separately (picture in `shown`, caption in `mode.meta`), which is exactly why
    // a card could appear with the platform caption and then widen: the two had
    // independent lives. Fusing them makes that impossible — the Crossfade below
    // swaps a whole card at once.
    var shown by remember(pkg) { mutableStateOf<Card?>(null) }

    val browsing = mode.active
    val index = if (items.isEmpty()) 0 else mode.index.coerceIn(0, items.lastIndex)

    // The list: fetched once per package, published to [mode] so the dock can
    // decide whether Up should enter browse mode.
    LaunchedEffect(pkg) {
        mode.itemsPkg = null
        mode.items = emptyList()
        mode.index = 0
        val list = Shelf.items(pkg)
        items = list
        mode.items = list
        mode.itemsPkg = pkg
    }
    // An index left over from a shorter list (or an earlier app) must not stick.
    LaunchedEffect(items) {
        if (items.isNotEmpty() && mode.index > items.lastIndex) mode.index = 0
    }

    // Picture and caption are settled separately, on purpose:
    //
    //  • the still is published as soon as it is decoded — the user is watching
    //    the picture, and holding it back would show the wallpaper instead;
    // Both parts are settled BEFORE the card is published, and in parallel:
    // the still (0.2 s cached / 2.4 s cold) and the Douban answer (0 s cached /
    // 0.9 s cold). Publishing them as one [Card] is what removes the flicker —
    // the caption and the picture enter the Crossfade together, so a card can
    // never be drawn platform-only and widened a moment later.
    //
    /**
     * Remove a card that cannot be drawn from the rotation, so the page dots count
     * only pictures that actually exist. [key] is the failed card's identity; the
     * index is clamped, which naturally lands on the neighbour.
     */
    fun dropUndrawable(key: String) {
        // Never empty the shelf: with every still undrawable the launcher is better
        // off showing nothing over the wallpaper (which `shown == null` already
        // does) than cycling through removals one dot at a time.
        if (items.size <= 1) return
        val filtered = items.filterNot { itemKey(it) == key }
        if (filtered.size == items.size) return
        items = filtered
        mode.items = filtered
        mode.index = if (filtered.isEmpty()) 0 else mode.index.coerceIn(0, filtered.lastIndex)
    }

    // Keyed on the card's identity, NOT on the `items` list: that list is a fresh
    // instance on every fetch, so including it re-ran this effect for the same card
    // and asked Douban twice — which is what exhausted the per-minute budget on
    // the first rotation.
    val cardKey = items.getOrNull(index)?.let { itemKey(it) }
    LaunchedEffect(cardKey, pkg, doubanEnabled) {
        val item = items.getOrNull(index) ?: return@LaunchedEffect
        val key = cardKey ?: return@LaunchedEffect

        val platform = DoubanMeta.platformOfPackage(pkg)
        val wanted = doubanEnabled && platform != null && item.programIds.isNotEmpty()

        // Concurrent, not sequential: waiting for Douban and then the image would
        // add the two together (~3.3 s on a cold card) where the real cost is the
        // slower of the two.
        val metaDeferred: Deferred<DoubanMeta.Meta?>? =
            if (wanted) async { lookupInto(platform!!, item, key, ctx) } else null
        val bmp = Shelf.image(ctx, item) ?: run {
            metaDeferred?.cancel()
            // This card cannot be drawn (the service published only a thumbnail,
            // or the CDN is gone). Leaving it in the list would keep its page dot
            // while `shown` still holds the previous picture — the shelf then
            // shows N dots for fewer distinct stills, and the same still appears
            // to own two dots. Drop it and let the shelf settle on its neighbour.
            dropUndrawable(key)
            return@LaunchedEffect
        }
        // The caption is the cheaper half, so by now it is usually already done;
        // when it is not, this bounds the D-pad latency to one lookup.
        val meta: DoubanMeta.Meta? =
            metaDeferred?.let { d -> withTimeoutOrNull(DOUBAN_BUDGET_MS) { d.await() } }

        shown = Card(item, bmp, meta)

        // Warm the NEXT card while this one is on screen — including its Douban
        // answer. The rotation gives us SHELF_SECONDS (8 s) of headroom, far more
        // than the ~0.9 s a lookup costs, so the next switch finds everything
        // ready and never has to wait or flush the caption in late.
        //
        // Guarded by the same per-minute budget as the visible card: when it is
        // exhausted this silently does nothing and the next card simply shows its
        // platform caption until a later rotation picks it up.
        val next = items.getOrNull((index + 1) % items.size)
        if (next != null) {
            Shelf.image(ctx, next)
            if (doubanEnabled && !browsing) {
                val nextKey = itemKey(next)
                val nextPlatform = DoubanMeta.platformOfPackage(pkg)
                if (nextPlatform != null && next.programIds.isNotEmpty() &&
                    !DoubanMeta.isCached(ctx, nextPlatform, next.programIds.first(), next.title)
                ) {
                    lookupInto(nextPlatform, next, nextKey, ctx)
                }
            }
        }
    }

    // Rotate — but never underneath the user's fingers, and never while the
    // expanded dock grid is open: the still is the grid's backdrop there, so
    // swapping it every 8 s would flip the wallpaper behind an open drawer.
    LaunchedEffect(mode.itemsPkg, items.size, browsing, drawerOpen) {
        if (browsing || drawerOpen || items.size < 2) return@LaunchedEffect
        while (true) {
            delay(SHELF_SECONDS * 1000L)
            mode.index = (mode.index + 1) % items.size
        }
    }

    // Browsing: a full-screen (invisible) focus target owns the D-pad, so the
    // shelf needs no extra chrome — the still itself is the selection.
    LaunchedEffect(browsing) {
        if (!browsing) return@LaunchedEffect
        repeat(3) {
            withFrameNanos {}
            runCatching { browseFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Behind the expanded dock grid the picture is a backdrop: a shade
        // deeper than the grid's own (which used to sit over a blurred
        // wallpaper) keeps the icons readable over a bright still.
        val dim by animateFloatAsState(
            if (drawerOpen) 0.45f else 0f,
            animationSpec = tween(400),
            label = "shelfDrawerDim",
        )
        // The title block belongs to the dock band, which the grid covers — it
        // fades out with the panel instead of blinking off.
        val infoAlpha by animateFloatAsState(
            if (showInfo) 1f else 0f,
            animationSpec = tween(200),
            label = "shelfInfoAlpha",
        )
        // The still crossfades under the dock. Nothing is drawn until the first
        // picture is ready, so a failed fetch leaves the wallpaper untouched.
        Crossfade(targetState = shown, animationSpec = tween(SHELF_FADE_MS.toInt()), label = "shelfPic") { state ->
            if (state != null) {
                // Settle from a slight zoom, like a camera coming to rest —
                // but only when the USER changed the picture (Left/Right while
                // browsing). The 8 s auto-rotation used to play it too: 2.2 s of
                // scaling a full-screen layer, i.e. the one thing still moving
                // in an otherwise idle launcher.
                var settled by remember(state) { mutableStateOf(!browsing) }
                LaunchedEffect(state) { settled = true }
                val zoom by animateFloatAsState(
                    targetValue = if (settled) 1f else 1.045f,
                    animationSpec = tween(2200, easing = FastOutSlowInEasing),
                    label = "shelfZoom",
                )
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
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
                if (dim > 0.01f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
                }
            }
        }

        if (browsing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(browseFocus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> { onUp(); true }
                            Key.DirectionDown -> { onExitToDock(); true }
                            // Wrap: the first still has nothing to its left and
                            // the last nothing to its right, so take those keys.
                            Key.DirectionLeft -> if (items.size > 1) {
                                mode.index = (index - 1 + items.size) % items.size
                                true
                            } else false
                            Key.DirectionRight -> if (items.size > 1) {
                                mode.index = (index + 1) % items.size
                                true
                            } else false
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                items.getOrNull(index)?.let(onPlay)
                                true
                            }
                            else -> false
                        }
                    }
            )
        }

        Column(
            Modifier
                // Faded out while the dock grid is open (see [showInfo]).
                .graphicsLayer { alpha = infoAlpha }
                .align(Alignment.BottomStart)
                // Clear of the dock row + its 2nd-row peek, so the text sits in
                // the empty band above the icons, like the Top Shelf on tvOS.
                .padding(start = anchor.start, end = 24.dp, bottom = anchor.bottom)
                // Wider than before: the larger source line needs the room to
                // stay on one line.
                .fillMaxWidth(0.72f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val card = shown
            run {
                if (card == null) {
                    Box(Modifier)
                } else {
                    val item = card.item
                    // Every line has a fixed slot. A line with nothing to say keeps
                    // its height (empty text / INVISIBLE), so the block never
                    // changes size while the stills rotate — same reason CinePath
                    // positions its caption on absolute coordinates.
                    //
                    // Fixed sizes, not theme steps: tv-material's label scale
                    // tops out at 14 sp, which vanishes on a TV.
                    val meta = card.meta
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val source = Shelf.sourceLabel(pkg)
                        Text(
                            text = if (source != 0) stringResource(source) else "",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 22.sp,
                                lineHeight = 28.sp,
                                shadow = ShelfShadow,
                            ),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 32.sp,
                                lineHeight = 40.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = ShelfShadow,
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ShelfDetailLine(
                            text = detailLine(item, meta),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 20.sp,
                                lineHeight = 28.sp,
                                shadow = ShelfShadow,
                            ),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        ShelfDetailLine(
                            text = castLine(meta?.cast.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 20.sp,
                                lineHeight = 28.sp,
                                shadow = ShelfShadow,
                            ),
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        // Two lines are always reserved: the synopsis is the line
                        // most likely to arrive late (Douban answers after the
                        // picture), and letting it grow in would shift the dots.
                        ShelfDetailLine(
                            text = meta?.intro.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 19.sp,
                                lineHeight = 26.sp,
                                shadow = ShelfShadow,
                            ),
                            color = Color.White.copy(alpha = 0.72f),
                            lines = 2,
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
            if (browsing) {
                // The only place the D-pad grammar is spelled out.
                Text(
                    text = stringResource(R.string.shelf_hint),
                    style = MaterialTheme.typography.labelMedium.copy(shadow = ShelfShadow),
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Ask Douban about one card and publish the answer, unless it is too late.
 *
 * Called on every displayed card (rotation and D-pad alike) and deduplicated by
 * [ShelfUi.metaKey]; a card already answered costs nothing. The answer is dropped
 * when the user has moved on — the next card's effect resets [ShelfUi.metaKey], so
 * a late answer for the previous card can never overwrite the current caption.
 */
/**
 * One card as drawn: the still and the caption that belongs to it.
 *
 * They travel together so the shelf can never show a picture and a caption from
 * different moments — the failure that made the text "flash" on every switch.
 */
internal data class Card(
    val item: Shelf.Item,
    val bitmap: Bitmap,
    /** Null = Douban had nothing usable; the caption stays platform-only. */
    val meta: DoubanMeta.Meta?,
)

/**
 * At most one lookup per card, with every caller sharing the answer.
 *
 * Needed because card N+1 is prefetched while card N is on screen: when the user
 * reaches N+1, its display and its prefetch are the same question. Handing the
 * second caller `null` (as an earlier "is it in flight?" check did) dropped the
 * answer and left that card on its platform caption.
 */
internal class LookupGate {
    private val running = HashMap<String, CompletableDeferred<DoubanMeta.Meta?>>()

    /** Returns the deferred to await; [mine] marks the caller that must fill it. */
    fun join(key: String): Pair<CompletableDeferred<DoubanMeta.Meta?>, Boolean> = synchronized(running) {
        running[key]?.let { return it to false }
        val fresh = CompletableDeferred<DoubanMeta.Meta?>()
        running[key] = fresh
        fresh to true
    }

    fun leave(key: String) = synchronized(running) { running.remove(key) }

    internal fun size() = synchronized(running) { running.size }
}

private suspend fun lookupInto(
    platform: String,
    item: Shelf.Item,
    key: String,
    ctx: android.content.Context,
): DoubanMeta.Meta? {
    val (result, mine) = gate.join(key)
    if (!mine) return result.await()
    try {
        var found: DoubanMeta.Meta? = null
        // Each id gets its OWN budget. Wrapping both in one timeout meant the
        // second attempt inherited whatever the first had already spent, and a
        // cancel there surfaced as a bogus "no match".
        for (id in item.programIds) {
            val meta = withTimeoutOrNull(DOUBAN_BUDGET_MS) {
                DoubanMeta.lookup(ctx, platform, id, item.title)
            }
            if (meta != null) {
                found = meta
                break
            }
        }
        result.complete(found)
        return found
    } catch (t: Throwable) {
        result.complete(null)
        throw t
    } finally {
        gate.leave(key)
    }
}

/** Shared by the visible card and the prefetch of the next one. */
private val gate = LookupGate()

/**
 * One card as drawn: the still and the caption that belongs to it.
 *
 * They travel together so the shelf can never show a picture and a caption from
 * different moments — the failure that made the text "flash" on every switch.
 */
/** Soft drop shadow so white text stays readable over a bright still. *//** Soft drop shadow so white text stays readable over a bright still. */
private val ShelfShadow = Shadow(
    color = Color.Black.copy(alpha = 0.7f),
    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
    blurRadius = 12f,
)

/**
 * Identity of a card, used to pair a late Douban answer with the card that asked
 * for it: the title plus the still, both of which the sources always fill in.
 */
internal fun itemKey(item: Shelf.Item): String =
    DoubanMeta.norm(item.title) + "|" + item.image

/**
 * The genre line — Douban's when the lookup answered, the service's own
 * otherwise. Douban brings the films/series' real genres, region, year and
 * score; the service's tag is a fallback, not an equal.
 */
internal fun detailLine(item: Shelf.Item, meta: DoubanMeta.Meta?): String {
    if (meta != null) {
        val parts = ArrayList<String>()
        if (meta.genres.isNotEmpty()) parts.add(meta.genres.joinToString(" · "))
        if (meta.region.isNotEmpty()) parts.add(meta.region)
        if (meta.year.isNotEmpty()) parts.add(meta.year)
        // Only Douban's score is shown (never the platform's): it is the one the
        // label "豆瓣" can honestly name.
        if (meta.rating > 0) parts.add(String.format(Locale.US, "豆瓣 %.1f", meta.rating))
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
    }
    return item.meta
}

/**
 * "演员：甲 · 乙 · 丙 · 丁 等" — the first four names, then 等. Empty when the
 * lookup brought no cast, which keeps the line blank (see [ShelfDetailLine]).
 */
internal fun castLine(names: List<String>): String {
    val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (clean.isEmpty()) return ""
    return "演员：" + clean.take(4).joinToString(" · ") + if (clean.size > 4) " 等" else ""
}

/**
 * One fixed caption line. When [text] is empty the line keeps its height
 * (invisible, not removed), so a missing field never shifts the lines below it —
 * and the block does not change size when two cards with different amounts of
 * metadata cross-fade.
 */
@Composable
private fun ShelfDetailLine(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    lines: Int = 1,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        // alpha, not zero-size: the slot keeps exactly the height this text
        // WOULD have taken, at every font scale the launcher offers.
        modifier = if (text.isEmpty()) Modifier.alpha(0f) else Modifier,
    )
}
