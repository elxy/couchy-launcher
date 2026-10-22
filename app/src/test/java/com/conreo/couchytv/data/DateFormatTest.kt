package com.conreo.couchytv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The date formats the status bar offers.
 *
 * The first entry after "off" is ISO on purpose: it is all digits, so the date renders in
 * exactly the same face as the clock. A localized month ("10月") brings in a CJK fallback
 * whose taller glyphs move the date off the clock's baseline — which is what a Chinese
 * "d MMM yyyy" looked like on the TV.
 */
class DateFormatTest {

    private val the22nd = Calendar.getInstance(Locale.CHINA).apply {
        clear()
        set(2026, Calendar.OCTOBER, 22)
    }.time

    @Test
    fun `index 0 means no date`() {
        assertEquals("", DATE_FORMATS[0])
    }

    @Test
    fun `turning the date on lands on ISO`() {
        assertEquals("yyyy-MM-dd", DATE_FORMATS[1])
        assertEquals(
            "2026-10-22",
            SimpleDateFormat(DATE_FORMATS[1], Locale.CHINA).format(the22nd),
        )
    }

    @Test
    fun `no duplicate formats`() {
        assertEquals(DATE_FORMATS.size, DATE_FORMATS.toSet().size)
    }

    @Test
    fun `every format renders something`() {
        for (i in 1 until DATE_FORMATS.size) {
            val rendered = SimpleDateFormat(DATE_FORMATS[i], Locale.CHINA).format(the22nd)
            assertEquals("format $i (${DATE_FORMATS[i]}) rendered nothing", true, rendered.isNotBlank())
        }
    }
}
