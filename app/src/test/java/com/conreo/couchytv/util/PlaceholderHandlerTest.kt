package com.conreo.couchytv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which file-intent handlers are real.
 *
 * This rule decides whether the launcher opens ACTION_OPEN_DOCUMENT or falls back to
 * ACTION_GET_CONTENT, so getting it wrong is user-visible: the picker "opens", closes
 * immediately, and no file can be chosen.
 *
 * Found on an Android TV 14 emulator image, which answered OPEN_DOCUMENT with the stub —
 * under the package `com.android.tv.frameworkpackagestubs`, while the filter only knew
 * `com.google.android.tv.frameworkpackagestubs`. The list is therefore matched structurally
 * too, and these tests pin both spellings.
 */
class PlaceholderHandlerTest {

    @Test
    fun `the google-named framework stub is a placeholder`() {
        assertTrue(
            isPlaceholderHandler(
                "com.google.android.tv.frameworkpackagestubs",
                "com.google.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub",
            )
        )
    }

    @Test
    fun `the android-named framework stub is a placeholder too`() {
        // Seen on sdk_google_atv64_arm64 API 34: this one resolved OPEN_DOCUMENT as a
        // usable handler before the structural match was added.
        assertTrue(
            isPlaceholderHandler(
                "com.android.tv.frameworkpackagestubs",
                "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub",
            )
        )
    }

    @Test
    fun `a stub class in an unknown package is still a placeholder`() {
        assertTrue(isPlaceholderHandler("com.vendor.gallery", "com.vendor.gallery.StubPicker"))
    }

    @Test
    fun `real pickers are not placeholders`() {
        assertFalse(
            isPlaceholderHandler(
                "com.google.android.providers.media.module",
                "com.android.providers.media.photopicker.PhotoPickerGetContentActivity",
            )
        )
        assertFalse(
            isPlaceholderHandler(
                "com.android.documentsui",
                "com.android.documentsui.picker.PickActivity",
            )
        )
    }
}
