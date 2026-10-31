package com.conreo.couchytv.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App -> section resolution.
 *
 * The regression this guards is upstream issue #7: tapping a tile inside the auto-filled
 * "All apps" section persisted `__all__` into that app's section set, and sectionsOf()
 * then filtered the unusable id out — leaving the app in NO section at all. That is the
 * "I selected it and it never appears" report, and the filter is what repairs configs
 * that were already saved that way.
 */
class SectionsOfTest {

    private fun app(pkg: String, autoCategory: String = "streaming") = AppEntry(
        pkg = pkg,
        label = pkg,
        art = null,
        autoCategory = autoCategory,
        tile = Color(0xFF37474F),
        stamp = 0L,
        firstInstall = 0L,
    )

    private val config = LauncherConfig(
        categories = listOf(
            CategoryCfg("streaming", "Streaming"),
            CategoryCfg("games", "Games"),
            CategoryCfg(AppRepository.ALL_APPS_ID, "All apps"),
        ),
    )

    @Test
    fun `the auto-filled all-apps id is not a section membership`() {
        val saved = config.copy(sections = mapOf("a" to setOf(AppRepository.ALL_APPS_ID)))
        assertEquals(setOf("streaming"), AppRepository.sectionsOf(app("a"), saved))
    }

    @Test
    fun `a stored membership is used as-is`() {
        val saved = config.copy(sections = mapOf("a" to setOf("games")))
        assertEquals(setOf("games"), AppRepository.sectionsOf(app("a"), saved))
    }

    @Test
    fun `a stored empty set means no section`() {
        val saved = config.copy(sections = mapOf("a" to emptySet()))
        assertEquals(emptySet<String>(), AppRepository.sectionsOf(app("a"), saved))
    }

    @Test
    fun `a section that no longer exists is dropped`() {
        val saved = config.copy(sections = mapOf("a" to setOf("deleted-section")))
        assertEquals(emptySet<String>(), AppRepository.sectionsOf(app("a"), saved))
    }

    @Test
    fun `no stored entry falls back to the auto category`() {
        assertEquals(setOf("games"), AppRepository.sectionsOf(app("a", "games"), config))
    }

    @Test
    fun `an unknown auto category falls back to the last real section`() {
        // "apps" is not in this config and the All-apps section is never a target,
        // so the last usable section — games — takes it.
        assertEquals(setOf("games"), AppRepository.sectionsOf(app("a", "apps"), config))
    }

    @Test
    fun `the all-apps section is never a valid target`() {
        assertFalse(AppRepository.ALL_APPS_ID in AppRepository.validSectionIds(config))
        assertTrue(AppRepository.validSectionIds(config).containsAll(setOf("streaming", "games")))
    }

    @Test
    fun `the hoisted overload agrees with the per-app one`() {
        val validIds = AppRepository.validSectionIds(config)
        val fallbackId = AppRepository.fallbackSectionId(config)
        val stored = mapOf(
            "a" to setOf(AppRepository.ALL_APPS_ID),
            "b" to setOf("games"),
            "c" to emptySet(),
            "d" to setOf("gone"),
        )
        val saved = config.copy(sections = stored)
        var compared = 0
        for (pkg in stored.keys) {
            for (auto in listOf("streaming", "games", "music", AppRepository.ALL_APPS_ID)) {
                val entry = app(pkg, auto)
                assertEquals(
                    "sectionsOf disagreed for $pkg/$auto",
                    AppRepository.sectionsOf(entry, saved),
                    AppRepository.sectionsOf(entry, saved, validIds, fallbackId),
                )
                compared++
            }
        }
        assertEquals(16, compared)
    }
}
