package com.conreo.couchytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.conreo.couchytv.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.data.NetStatus

/* ---- geometry ----
 * The bar's height is arithmetic, not a guess. The tallest thing in it is a 40 dp
 * status-icon target inside a cluster with 4 dp of its own padding: 48 dp. It used
 * to be an 80 dp box with 18 dp of vertical padding around that 48 dp of content —
 * 84 dp of content in an 80 dp box, so `clipToBounds` shaved 2 dp off the top and
 * the bottom of the glass. Deriving the height from its parts is what stops the
 * two from drifting apart again.
 */

/** Content height of the bar: the 40 dp icon target plus the cluster's own padding. */
internal val STATUS_CHIP_HEIGHT = 48.dp

/** Vertical padding of the bar, inside [STATUS_BAR_HEIGHT]. */
internal val STATUS_BAR_PAD_V = 16.dp

/** Distance from the screen edge to the bar's contents, left and right. */
internal val STATUS_BAR_MARGIN_H = 40.dp

/** Height of the status bar — what the collapsed strip expands to. */
internal val STATUS_BAR_HEIGHT = STATUS_CHIP_HEIGHT + STATUS_BAR_PAD_V * 2

/** Height of the strip that the bar collapses to, and that reveals it on focus. */
internal val STATUS_BAR_COLLAPSED = 4.dp

@Composable
fun StatusBar(
    net: NetStatus,
    time: String,
    date: String,
    showVpn: Boolean,
    glass: Boolean,
    onVpnClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onSettingsClick: () -> Unit,
    /** Where Up-from-the-shelf lands: the network icon, the left end of the button
     *  cluster in the top-right corner. Null in contexts that never focus the bar. */
    focusRequester: FocusRequester? = null,
) {
    // Optional glass panel behind the BUTTON cluster only — same look as the dock
    // panel, corners follow the roundness setting. The date and the clock sit on the
    // opposite corner with no panel at all: two glass rectangles on one 80 dp line read
    // as a toolbar, and the launcher already dims the top band of the wallpaper.
    val iconPanel = if (glass) {
        Modifier
            .clip(SmoothCornerShape(LocalCornerRadius.current))
            .background(Color(0xB3121418))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    } else Modifier
    // One style for both texts, remembered: `.copy()` allocates, and this line re-reads it
    // on every clock tick.
    //
    // titleLarge (22 sp) rather than the titleMedium (18 sp) this used to be. It is the size
    // that balances the button chip: the icons are 22 dp glyphs in 40 dp targets, so 18 sp
    // digits read as a caption next to them. Tabular figures stay — the clock must not
    // jitter as its digits change.
    val typography = MaterialTheme.typography
    val barTextStyle = remember(typography) {
        typography.titleLarge.copy(fontFeatureSettings = "tnum")
    }
    // Built once: a fresh Modifier chain is never equal to the previous one, so an inline
    // one would defeat the skip of the icon it is handed to.
    val networkModifier = remember(focusRequester) {
        if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = STATUS_BAR_MARGIN_H, vertical = STATUS_BAR_PAD_V),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ----- left: date · clock, on the wallpaper, no panel -----
        Row(
            // weight(fill = false): the text yields the width the buttons need rather than
            // pushing them off the edge. Inside, the date gives up room before the clock does.
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (date.isNotEmpty()) {
                Text(
                    text = date,
                    style = barTextStyle,
                    // Same weight as the clock: they are one line, separated by the gap.
                    color = Color.White,
                    // The date is the first thing to give up room: one line, tail ellipsis.
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                // Tabular figures: digits keep a fixed width so the clock never jitters.
                text = time,
                style = barTextStyle,
                color = Color.White,
                // Never wrap and never clip: wrapping split the clock into "10:" / "57".
                maxLines = 1,
                softWrap = false,
            )
        }

        // ----- right: VPN · network · settings, on the glass chip -----
        Row(
            modifier = iconPanel,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showVpn) {
                StatusIcon(
                    icon = AppIcons.Vpn,
                    active = net.vpn,
                    contentDescription = stringResource(R.string.cd_vpn),
                    onClick = onVpnClick,
                )
            }
            StatusIcon(
                icon = when {
                    net.ethernet -> AppIcons.Ethernet
                    net.wifi -> AppIcons.Wifi
                    else -> AppIcons.WifiOff
                },
                active = net.connected,
                contentDescription = stringResource(R.string.cd_network),
                onClick = onNetworkClick,
                modifier = networkModifier,
            )
            StatusIcon(
                icon = AppIcons.Gear,
                active = true,
                contentDescription = stringResource(R.string.cd_settings),
                onClick = onSettingsClick,
            )
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.22f),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.18f),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = if (active) 0.95f else 0.35f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
