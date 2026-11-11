package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.5.1: job headers must show a real host, never "(unknown)". */
class JobLogHostTest {

    @Test
    fun fullUrlKeepsOnlyTheHost() {
        assertEquals("example.com", JobLog.hostOf("https://example.com/watch/1?t=abc"))
    }

    @Test
    fun wwwIsStripped() {
        assertEquals("example.com", JobLog.hostOf("https://www.example.com/a"))
    }

    @Test
    fun bareDomainWithoutSchemeStillResolves() {
        assertEquals("example.com", JobLog.hostOf("example.com/watch/1"))
    }

    @Test
    fun domainInsideSharedTextIsFound() {
        assertEquals("example.com", JobLog.hostOf("look at this example.com/clip it is wild"))
    }

    @Test
    fun noLinkAtAllIsHonest() {
        assertEquals("(no link)", JobLog.hostOf("   "))
        assertEquals("(no host)", JobLog.hostOf("no link here just words"))
    }

    @Test
    fun headerNeverSaysUnknown() {
        val text = JobLog.format(
            listOf(
                JobLog.Job(
                    id = "abcdef123",
                    url = "example.com/watch/1",
                    started = "10:00:00",
                    result = "Saved",
                ),
            ),
        )
        assertEquals(false, text.contains("(unknown)"))
        assertEquals(true, text.contains("example.com"))
    }
}
