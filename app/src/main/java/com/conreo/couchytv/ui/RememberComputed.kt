package com.conreo.couchytv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class ComputedCache<T> {
    var key: List<Any?>? = null
    var value: Any? = null
}

/**
 * Caches the result of a @Composable computation.
 *
 * Some of the tv-material defaults (ClickableSurfaceDefaults.shape/colors) are
 * @Composable — they read the theme — so they cannot be wrapped in remember{}
 * directly, and they hand back a fresh object on every composition. This keeps
 * the SAME instance for as long as the keys are unchanged, so the value is not
 * re-allocated (and can be compared by identity) downstream.
 *
 * tv-material's outline cache keys on the shape INSTANCE, so identity is what
 * matters here — not the allocation.
 *
 * [compute] is ALWAYS called, so the theme read it performs still happens on
 * every composition (a conditional composable call would change the shape of
 * the composition and is not safe). The fresh result is simply discarded while
 * the keys are unchanged.
 *
 * The keys must cover EVERYTHING [compute] reads — a theme colour, the corner
 * radius — otherwise a change to it would not be picked up.
 */
@Composable
internal fun <T> rememberComputed(vararg keys: Any?, compute: @Composable () -> T): T {
    val cache = remember { ComputedCache<T>() }
    val wanted = keys.toList()
    val fresh = compute()
    if (cache.key == null || cache.key != wanted) {
        cache.value = fresh
        cache.key = wanted
    }
    @Suppress("UNCHECKED_CAST")
    return (cache.value as T)
}
