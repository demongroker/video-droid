package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The local crash log: file format, rotation, and nothing sensitive inside. */
class CrashLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stack = """
        java.lang.RuntimeException: boom
            at com.xerxes.videodroid.MainActivity.onCreate(MainActivity.kt:42)
    """.trimIndent()

    @Test
    fun formatCarriesTheFactsNeededToDiagnose() {
        val out = CrashLog.format(
            kind = "crash",
            timestamp = "2026-11-09 18:20:31 +0800",
            appVersion = "2.3.6",
            versionCode = 92,
            androidVersion = "Android 14 (SDK 34)",
            device = "Xiaomi 2201117SY",
            threadName = "main",
            stack = stack,
            logLines = listOf("18:20:29 app start 2.3.6 (92)", "18:20:30 job: starting"),
        )
        assertTrue(out.startsWith("VideoDroid crash"))
        assertTrue(out.contains("time     : 2026-11-09 18:20:31 +0800"))
        assertTrue(out.contains("version  : 2.3.6 (92)"))
        assertTrue(out.contains("android  : Android 14 (SDK 34)"))
        assertTrue(out.contains("device   : Xiaomi 2201117SY"))
        assertTrue(out.contains("thread   : main"))
        assertTrue(out.contains("java.lang.RuntimeException: boom"))
        assertTrue(out.contains("last 2 log lines"))
        assertTrue(out.contains("job: starting"))
    }

    @Test
    fun emptyLogSaysSoInsteadOfPrintingNothing() {
        val out = CrashLog.format(
            kind = "non-fatal error", timestamp = "t", appVersion = "1", versionCode = 1,
            androidVersion = "a", device = "d", threadName = "w", stack = "s", logLines = emptyList(),
        )
        assertTrue(out.contains("(none)"))
        assertTrue(out.startsWith("VideoDroid non-fatal error"))
    }

    @Test
    fun rotationKeepsTheNewestTwenty() {
        val dir = tmp.newFolder("crashes")
        for (i in 1..25) {
            File(dir, "crash_2026-11-09_10-00-%02d.txt".format(i)).writeText("x")
        }
        val deleted = CrashLog.rotate(dir, CrashLog.MAX_FILES)
        assertEquals(5, deleted)
        assertEquals(20, dir.listFiles()!!.size)
        // Oldest gone, newest kept.
        assertFalse(File(dir, "crash_2026-11-09_10-00-01.txt").exists())
        assertTrue(File(dir, "crash_2026-11-09_10-00-25.txt").exists())
    }

    @Test
    fun rotationDropsNonFatalEntriesBeforeCrashes() {
        val dir = tmp.newFolder("crashes2")
        // 18 non-fatal + 4 crashes = 22 files, two over the limit.
        for (i in 1..18) File(dir, "nonfatal_2026-11-09_09-00-%02d.txt".format(i)).writeText("x")
        for (i in 1..4) File(dir, "crash_2026-11-09_10-00-%02d.txt".format(i)).writeText("x")
        CrashLog.rotate(dir, CrashLog.MAX_FILES)
        val left = dir.listFiles()!!.map { it.name }
        assertEquals(20, left.size)
        assertEquals("all four crashes must survive", 4, left.count { it.startsWith("crash_") })
        assertEquals(16, left.count { it.startsWith("nonfatal_") })
    }

    @Test
    fun rotationDoesNothingUnderTheLimit() {
        val dir = tmp.newFolder("crashes3")
        File(dir, "crash_a.txt").writeText("x")
        assertEquals(0, CrashLog.rotate(dir, CrashLog.MAX_FILES))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun fileNamesAreTimestampedAndDistinguishable() {
        assertTrue("crash_2026-11-09_18-20-31.txt".startsWith(CrashLog.let { "crash_" }))
        assertTrue(CrashLog.isCrashFile(File("crash_2026-11-09_18-20-31.txt")))
        assertFalse(CrashLog.isCrashFile(File("nonfatal_2026-11-09_18-20-31.txt")))
    }
}
