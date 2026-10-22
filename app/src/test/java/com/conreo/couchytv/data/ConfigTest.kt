package com.conreo.couchytv.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config file is the whole value of a self-hosted launcher: sections, pinned order,
 * wallpaper choice, hidden apps. A backup that silently loses a field is only discovered
 * when it is restored, i.e. when it is too late.
 *
 * These are the two properties that matter when fields are added to the schema:
 * a round trip preserves everything, and a file written by a NEWER build (extra keys)
 * still loads in an older one instead of throwing and falling back to defaults.
 */
class ConfigTest {

    // Same settings the settings sheet writes its backup with.
    private val format = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun `a config survives a round trip`() {
        val original = LauncherConfig(
            categories = listOf(CategoryCfg("streaming", "Streaming"), CategoryCfg("games", "Games")),
            sections = mapOf("com.a" to setOf("games"), "com.b" to emptySet()),
            order = mapOf("games" to listOf("com.a", "com.b")),
            hidden = setOf("com.c"),
            knownApps = setOf("com.a", "com.b", "com.c"),
            wallpaper = 3,
            useCustomWallpaper = true,
            useBingWallpaper = true,
            layout = 2,
            showStatusBar = false,
        )
        val restored = format.decodeFromString(
            LauncherConfig.serializer(),
            format.encodeToString(LauncherConfig.serializer(), original),
        )
        assertEquals(original, restored)
    }

    @Test
    fun `a key this build does not know is ignored, not fatal`() {
        val json = """
            {
              "categories": [ { "id": "streaming", "name": "Streaming" } ],
              "aFieldFromAFutureVersion": { "nested": [1, 2, 3] },
              "wallpaper": 4
            }
        """.trimIndent()
        val restored = format.decodeFromString(LauncherConfig.serializer(), json)
        assertEquals(1, restored.categories.size)
        assertEquals(4, restored.wallpaper)
    }

    @Test
    fun `a missing key falls back to its default`() {
        val restored = format.decodeFromString(LauncherConfig.serializer(), "{}")
        assertEquals(LauncherConfig(), restored)
        assertTrue(restored.categories.isNotEmpty())
    }

    @Test
    fun `an empty stored section set is preserved, not repaired`() {
        // "removed from every section" is a real state: the app must appear nowhere.
        val original = LauncherConfig(sections = mapOf("com.a" to emptySet()))
        val restored = format.decodeFromString(
            LauncherConfig.serializer(),
            format.encodeToString(LauncherConfig.serializer(), original),
        )
        assertEquals(setOf<String>(), restored.sections["com.a"])
    }
}
