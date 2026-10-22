package com.conreo.couchytv.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The atomic wallpaper write.
 *
 * The failure this exists for is not hypothetical: writing files/wallpaper.jpg in place
 * while the home screen decoded it showed banded half-image output. So the property under
 * test is "a reader sees either the old file or the new one", plus "a failed write leaves
 * the old picture alone" — never a truncated target.
 */
class WallpaperFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(text: String) = text.toByteArray()

    @Test
    fun `replaces the wallpaper and leaves no temp files behind`() {
        val dir = tmp.newFolder("files")
        val target = File(dir, WallpaperFile.NAME)
        target.writeText("old picture")

        assertTrue(WallpaperFile.write(dir, ByteArrayInputStream(bytes("new picture"))))

        assertArrayEquals(bytes("new picture"), target.readBytes())
        assertEquals(listOf(WallpaperFile.NAME), dir.list()!!.sorted())
    }

    @Test
    fun `a failed copy leaves the previous wallpaper untouched`() {
        val dir = tmp.newFolder("files")
        val target = File(dir, WallpaperFile.NAME)
        target.writeText("old picture")

        val broken = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served++ < 4) return 'x'.code
                throw IOException("network died mid-copy")
            }
        }
        assertFalse(WallpaperFile.write(dir, broken))

        assertArrayEquals(bytes("old picture"), target.readBytes())
        // The half-written temp file must not survive: it would sit in filesDir forever.
        assertEquals(listOf(WallpaperFile.NAME), dir.list()!!.sorted())
    }

    @Test
    fun `writes into an empty directory`() {
        val dir = tmp.newFolder("fresh")
        assertTrue(WallpaperFile.write(dir, ByteArrayInputStream(bytes("first ever"))))
        assertArrayEquals(bytes("first ever"), File(dir, WallpaperFile.NAME).readBytes())
    }

    @Test
    fun `overwrites repeatedly without accumulating files`() {
        val dir = tmp.newFolder("files")
        repeat(5) { i ->
            assertTrue(WallpaperFile.write(dir, ByteArrayInputStream(bytes("picture $i"))))
        }
        assertEquals(listOf(WallpaperFile.NAME), dir.list()!!.sorted())
        assertArrayEquals(bytes("picture 4"), File(dir, WallpaperFile.NAME).readBytes())
    }
}
