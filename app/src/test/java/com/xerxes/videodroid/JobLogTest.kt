package com.xerxes.videodroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-job log sections: one block per job, host only, redacted. */
class JobLogTest {

    @Test
    fun hostOnlyFromAnyUrl() {
        assertEqualsHost("x.com", "https://x.com/user/status/123?a=1&s=19")
        assertEqualsHost("porneec.com", "https://www.porneec.com/watch/1")
        assertEqualsHost("(unknown)", "not a url at all")
    }

    private fun assertEqualsHost(expected: String, url: String) {
        org.junit.Assert.assertEquals(expected, JobLog.hostOf(url))
    }

    @Test
    fun oneBlockPerJobWithHeaderAndLines() {
        val text = JobLog.format(
            listOf(
                JobLog.Job("abcdef123", "https://x.com/a/status/1", "10:00:01", "10:00:30", "ok",
                    listOf("10:00:02 downloading 12%", "10:00:10 saved")),
                JobLog.Job("fedcba456", "https://site.example/v", "10:00:05", "10:00:20", "failed: HTTP 404",
                    listOf("10:00:06 probe failed")),
            ),
        )
        assertTrue(text.contains("=== job abcdef · x.com · started 10:00:01 · ended 10:00:30 · ok ==="))
        assertTrue(text.contains("=== job fedcba · site.example · started 10:00:05"))
        assertTrue(text.contains("downloading 12%"))
        assertTrue(text.contains("probe failed"))
        // Lines stay under their own header.
        assertTrue(text.indexOf("downloading 12%") < text.indexOf("=== job fedcba"))
    }

    @Test
    fun redactsQueriesAndTokens() {
        val text = JobLog.format(
            listOf(
                JobLog.Job("id1", "https://x.com/a/status/1?s=SECRETPARAM", "10:00", lines =
                    listOf("fetch https://site/v?token=SECRETVALUE auth_token=abcdef123456")),
            ),
        )
        assertFalse(text.contains("SECRETVALUE"))
        assertFalse(text.contains("abcdef123456"))
        assertFalse(text.contains("SECRETPARAM"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun emptyJobListIsStatedNotBlank() {
        assertTrue(JobLog.format(emptyList()).contains("no jobs recorded"))
    }
}
