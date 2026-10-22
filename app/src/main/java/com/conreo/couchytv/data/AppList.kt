package com.conreo.couchytv.data

import androidx.compose.runtime.Immutable

/**
 * A stable handle on a list of apps.
 *
 * A plain [List] is an UNSTABLE type in Compose: handing one to a composable
 * makes that composable unskippable, so the whole row re-runs on every state
 * change above it (the clock ticking, the network status flipping, the focus
 * moving). Wrapping the list in an @Immutable class gives the composables a
 * parameter they can compare cheaply, so they skip instead of recomposing.
 *
 * The wrapper is only correct if it is replaced whenever the contents change —
 * the call sites build it inside a `remember` keyed on the list it holds.
 */
@Immutable
class AppList(val items: List<AppEntry>)
