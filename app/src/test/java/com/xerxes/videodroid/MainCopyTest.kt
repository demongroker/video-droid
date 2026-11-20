package com.xerxes.videodroid

import com.xerxes.videodroid.ui.DownloadSettings
import com.xerxes.videodroid.ui.MainCopy
import com.xerxes.videodroid.ui.SocialPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Download screen's wording and rules are a contract (state pill truthfulness, the
 * aspect tokens FormatWorker parses, the recommended selection), pinned here rather than
 * left inside widget listeners.
 */
class MainCopyTest {

    @Test
    fun statePillSaysWhatIsActuallyTrue() {
        assertEquals("ready", MainCopy.statePill(busy = false, queueCount = 0, waiting = 0, progressVisible = false))
        assertEquals("working", MainCopy.statePill(busy = true, queueCount = 1, waiting = 0, progressVisible = false))
        assertEquals(
            "working · 2 waiting",
            MainCopy.statePill(busy = true, queueCount = 3, waiting = 2, progressVisible = false),
        )
        assertEquals(
            "working · 2 waiting",
            MainCopy.statePill(busy = false, queueCount = 3, waiting = 2, progressVisible = true),
        )
        assertEquals(
            "3 in queue — tap Queue",
            MainCopy.statePill(busy = false, queueCount = 3, waiting = 0, progressVisible = false),
        )
    }

    @Test
    fun drainedQueueNeverClaimsWorking() {
        // The 2.6.0-era bug: an empty queue kept the "working" pill and a lit Cancel button.
        val pill = MainCopy.statePill(busy = false, queueCount = 0, waiting = 0, progressVisible = false)
        assertEquals("ready", pill)
        assertFalse(MainCopy.showCancel(busy = false))
    }

    @Test
    fun goLabelFollowsBusyAndExport() {
        assertEquals("Add to queue", MainCopy.goLabel(busy = true, exportOn = true))
        assertEquals("Add to queue", MainCopy.goLabel(busy = true, exportOn = false))
        assertEquals("Download & Convert", MainCopy.goLabel(busy = false, exportOn = true))
        assertEquals("Download", MainCopy.goLabel(busy = false, exportOn = false))
    }

    @Test
    fun cancelOnlyWhileBusy() {
        assertTrue(MainCopy.showCancel(busy = true))
        assertFalse(MainCopy.showCancel(busy = false))
    }

    @Test
    fun jobCardShowsForStatusOrProgress() {
        assertTrue(MainCopy.showJobCard("Saved. Tap to open", progressVisible = false))
        assertTrue(MainCopy.showJobCard("", progressVisible = true))
        assertFalse(MainCopy.showJobCard("", progressVisible = false))
    }

    @Test
    fun aspectTokensMatchFormatWorkerContract() {
        assertEquals("original", MainCopy.aspectToken("Original", "Portrait 9:16"))
        assertEquals("original", MainCopy.aspectToken("original", "Square 1:1"))
        assertEquals("crop:9:16", MainCopy.aspectToken("Fill", "Portrait 9:16"))
        assertEquals("pad:9:16", MainCopy.aspectToken("Fit", "Portrait 9:16"))
        assertEquals("crop:16:9", MainCopy.aspectToken("Fill", "Landscape 16:9"))
        assertEquals("pad:4:5", MainCopy.aspectToken("Fit", "Portrait 4:5"))
        assertEquals("crop:1:1", MainCopy.aspectToken("Fill", "Square 1:1"))
    }

    @Test
    fun exportOffAlwaysMeansOriginal() {
        val s = DownloadSettings(quality = "720p", exportOn = false, mode = "Fill", ratio = "Square 1:1")
        assertEquals("original", s.aspectToken())
        val on = s.copy(exportOn = true)
        assertEquals("crop:1:1", on.aspectToken())
    }

    @Test
    fun recommendedIs1080OriginalTrimOff() {
        assertTrue(MainCopy.isRecommended("1080p", "Original", trimOn = false))
        assertTrue(MainCopy.isRecommended("1080p", "original", trimOn = false))
        assertFalse(MainCopy.isRecommended("720p", "Original", trimOn = false))
        assertFalse(MainCopy.isRecommended("1080p", "Fill", trimOn = false))
        assertFalse(MainCopy.isRecommended("1080p", "Original", trimOn = true))
        assertTrue(DownloadSettings().isRecommended())
    }

    @Test
    fun presetsKeepTheirExactSettings() {
        val byName = MainCopy.PRESETS.associateBy { it.name }
        assertEquals(7, byName.size)
        assertEquals(SocialPreset("X", "1080p", "Fit", "Portrait 9:16"), byName["X"])
        assertEquals(SocialPreset("TikTok", "1080p", "Fill", "Portrait 9:16"), byName["TikTok"])
        assertEquals(SocialPreset("Shorts", "1080p", "Fill", "Portrait 9:16"), byName["Shorts"])
        assertEquals(SocialPreset("Reels", "1080p", "Fill", "Portrait 9:16"), byName["Reels"])
        assertEquals(SocialPreset("YouTube", "1080p", "Original", "Portrait 9:16"), byName["YouTube"])
        assertEquals(SocialPreset("Instagram", "1080p", "Fill", "Portrait 4:5"), byName["Instagram"])
        assertEquals(SocialPreset("Facebook", "1080p", "Fit", "Landscape 16:9"), byName["Facebook"])
    }

    @Test
    fun presetNoticeOmitsRatioForOriginal() {
        assertEquals(
            "YouTube: 1080p / Original / trim off",
            MainCopy.presetNotice(SocialPreset("YouTube", "1080p", "Original", "Portrait 9:16")),
        )
        assertEquals(
            "X: 1080p / Fit Portrait 9:16 / trim off",
            MainCopy.presetNotice(SocialPreset("X", "1080p", "Fit", "Portrait 9:16")),
        )
    }

    @Test
    fun emptyUrlNoticeIsExact() {
        assertEquals("Paste a link or pick a local video", MainCopy.EMPTY_URL_NOTICE)
    }

    @Test
    fun qualityAndModeOptionsAreStable() {
        assertEquals(listOf("4K", "1080p", "720p", "480p"), MainCopy.QUALITIES)
        assertEquals(listOf("Original", "Fill", "Fit"), MainCopy.MODES)
        assertEquals(7, MainCopy.RATIOS.size)
        assertTrue(MainCopy.RATIOS.contains("Portrait 9:16"))
        assertTrue(MainCopy.RATIOS.contains("Square 1:1"))
    }
}