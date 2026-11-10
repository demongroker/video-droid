package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The page fallback must pick the video, not the 5 MB ad next to it. */
class CandidatePickerTest {

    private fun c(url: String, size: Long = -1, dur: Double = -1.0, sameHost: Boolean = false) =
        CandidatePicker.Candidate(url, size, dur, sameHost)

    @Test
    fun ignoresAClipWhenARealVideoExists() {
        val ad = c("https://video.twimg.com/ad/clip.mp4", size = 5L * 1024 * 1024)
        val main = c("https://site.example/v/main.mp4", size = 240L * 1024 * 1024, sameHost = true)
        assertEquals(main.url, CandidatePicker.choose(listOf(ad, main))?.url)
    }

    @Test
    fun ignoresShortClipsToo() {
        val short = c("https://cdn.example/short.mp4", dur = 12.0)
        val main = c("https://site.example/main.mp4", dur = 640.0)
        assertEquals(main.url, CandidatePicker.choose(listOf(short, main))?.url)
    }

    @Test
    fun prefersThePageHostOverABiggerForeignFile() {
        val foreign = c("https://cdn-other.example/big.mp4", size = 900L * 1024 * 1024)
        val own = c("https://site.example/v.mp4", size = 120L * 1024 * 1024, sameHost = true)
        assertEquals(own.url, CandidatePicker.choose(listOf(foreign, own))?.url)
    }

    @Test
    fun takesTheLargestWhenHostDoesNotDecide() {
        val a = c("https://a.example/1.mp4", size = 40L * 1024 * 1024)
        val b = c("https://b.example/2.mp4", size = 80L * 1024 * 1024)
        assertEquals(b.url, CandidatePicker.choose(listOf(a, b))?.url)
    }

    @Test
    fun allClipsStillReturnsOne() {
        val short = c("https://cdn.example/short.mp4", dur = 9.0)
        assertEquals(short.url, CandidatePicker.choose(listOf(short))?.url)
    }

    @Test
    fun nothingToPick() {
        assertNull(CandidatePicker.choose(emptyList()))
    }
}
