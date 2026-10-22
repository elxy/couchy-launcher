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

/**
 * Playback position of the wallpaper clip, in whole seconds. Written by the
 * player, read ONLY by the caption text when it is on screen — a stable holder,
 * so a per-second tick never recomposes the launcher itself (same trick as
 * [ShelfFocus]).
 */
@Stable
class CaptionClock {
    var seconds by mutableIntStateOf(0)
}

/** Seconds each picture stays before the shelf moves on. */
private const val SHELF_SECONDS = 8

/** How long the focus must rest before the shelf takes over the wallpaper —
 *  sliding along the dock must not strobe full-screen pictures. */
private const val FOCUS_SETTLE_MS = 350L

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
 * (e.g. 央视频) or its listing came back empty — the band above the dock is used
 * for the wallpaper's caption, instead of any empty shelf chrome: no dots, no
 * placeholder. [caption] is a fixed line (Bing's credit, the local video's
 * name); [timedCaption] is the Apple aerials' place label, which follows
 * playback through [captionClock].
 */
@Composable
fun ShelfLayer(
    focus: ShelfFocus,
    anchor: ShelfAnchor,
    enabled: Boolean,
    /** Full-screen takeover (move mode): the stills leave the screen entirely. */
    suppressed: Boolean,
    /**
     * The expanded dock grid. It covers the whole screen, so the stills stay —
     * as its backdrop — instead of dropping back to the wallpaper, which used
     * to flash a different picture the moment Down was pressed. Browse mode
     * still ends: the grid owns the D-pad.
     */
    drawerOpen: Boolean,
    captionSuppressed: Boolean,
    scrim: Brush?,
    mode: ShelfUi,
    caption: String?,
    timedCaption: List<Pair<Int, String>>?,
    captionClock: CaptionClock?,
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
    // band above the dock carries the wallpaper's caption instead. The shelf
    // text replaces it the moment there are stills to describe. [suppressed]
    // (move mode) hides the stills and ends browsing, but the caption is the
    // wallpaper's own description and lives in the empty band on the LEFT —
    // the settings sheet and the app menu only cover the right edge, so the
    // caption stays. The drawer keeps the stills, so this only matters when
    // there is none. Only a full-screen takeover ([captionSuppressed]) hides it.
    val noShelf = shownPkg == null || (mode.itemsPkg == shownPkg && mode.items.isEmpty())
    val shelfOn = !noShelf && !suppressed
    // Published for the launcher (the dock grid uses the still as its backdrop
    // and skips blurring the wallpaper underneath it). In a side effect, not in
    // the composition body: the reader is an ancestor.
    androidx.compose.runtime.SideEffect { if (mode.showing != shelfOn) mode.showing = shelfOn }
    val captionText = if (!noShelf || captionSuppressed) null else {
        val timeline = timedCaption
        val clock = captionClock
        if (timeline.isNullOrEmpty() || clock == null) caption
        // The clip's place label changes as the camera moves on (tvOS does the
        // same); before the first entry, show the first one.
        else timeline.lastOrNull { it.first <= clock.seconds }?.second
            ?: timeline.first().second
    }
    AnimatedVisibility(
        visible = !captionText.isNullOrEmpty(),
        enter = fadeIn(tween(450)),
        exit = fadeOut(tween(320)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                text = captionText.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    shadow = ShelfShadow,
                ),
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = anchor.start,
                        end = 24.dp,
                        bottom = anchor.bottom + CaptionLift,
                    )
                    // Wider than the shelf text column: the larger type needs
                    // the room to stay within two lines.
                    .fillMaxWidth(0.72f),
            )
        }
    }
}

@Composable
private fun ShelfContent(
    pkg: String,
    scrim: Brush?,
    anchor: ShelfAnchor,
    mode: ShelfUi,
    showInfo: Boolean,
    drawerOpen: Boolean,
    browseFocus: FocusRequester,
    onPlay: (Shelf.Item) -> Unit,
    onUp: () -> Unit,
    onExitToDock: () -> Unit,
) {
    val ctx = LocalContext.current
    var items by remember(pkg) { mutableStateOf<List<Shelf.Item>>(emptyList()) }
    var shown by remember(pkg) { mutableStateOf<Pair<Shelf.Item, Bitmap>?>(null) }

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

    // Draw whatever [index] points at; warm the next still while we're here.
    LaunchedEffect(index, items) {
        val item = items.getOrNull(index) ?: return@LaunchedEffect
        val bmp = Shelf.image(ctx, item) ?: return@LaunchedEffect
        shown = item to bmp
        items.getOrNull((index + 1) % items.size)?.let { Shelf.image(ctx, it) }
    }

    // Rotate — but never underneath the user's fingers, and never while the
    // expanded dock grid is open: the still is the grid's backdrop there, so
    // swapping it every 8 s would flip the wallpaper behind an open drawer.
    LaunchedEffect(items, browsing, drawerOpen) {
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
            Crossfade(targetState = shown?.first, animationSpec = tween(450), label = "shelfText") { item ->
                if (item == null) {
                    Box(Modifier)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Fixed sizes, not theme steps: tv-material's label scale
                        // tops out at 14 sp, which vanishes on a TV. The source
                        // line and the description line (14 → 22 sp) and the
                        // title (24 → 32 sp) each jump two steps up that ladder;
                        // the D-pad hint stays at 12 sp.
                        val source = Shelf.sourceLabel(pkg)
                        if (source != 0) {
                            Text(
                                text = stringResource(source),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 22.sp,
                                    lineHeight = 28.sp,
                                    shadow = ShelfShadow,
                                ),
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
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
                        Text(
                            text = item.meta,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 22.sp,
                                lineHeight = 28.sp,
                                shadow = ShelfShadow,
                            ),
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

/** Soft drop shadow so white text stays readable over a bright still. */
private val ShelfShadow = Shadow(
    color = Color.Black.copy(alpha = 0.7f),
    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
    blurRadius = 12f,
)

/** How far the wallpaper caption sits above the dock's text band — roughly one
 *  line of its own type, so it reads as a separate row from the dock instead of
 *  hugging the glass panel. */
private val CaptionLift = 26.dp
