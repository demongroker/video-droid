package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sign in" errors must name the right site.
 *
 * yt-dlp reports the same class of failure for X ("log in to see this post") and for YouTube
 * ("Sign in to confirm you're not a bot"). Before this, every one of them was reported as
 * "Need X login" — including youtu.be links, which sent people to a setting that could not
 * possibly help.
 */
class SignInHostTest {

  private val rawSignIn = "ERROR: Sign in to confirm you're not a bot. Use --cookies-from-browser"
  private val xSignIn = "ERROR: Login required to view this post"

  private val youTubeSignIn = "YouTube is asking for a sign-in — the network looks automated. Try again later."
  private val xLogin = "Need X login"
  private val generic = "Site needs sign-in."

  @Test
  fun youtuBeGetsYouTubeAdvice() {
    val msg = FormatWorker.mapError(rawSignIn, "https://youtu.be/dQw4w9WgXcQ")
    assertEquals(youTubeSignIn, msg)
  }

  @Test
  fun youtubeWatchGetsYouTubeAdvice() {
    val msg = FormatWorker.mapError(rawSignIn, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    assertEquals(youTubeSignIn, msg)
  }

  @Test
  fun youtubeShortsGetsYouTubeAdvice() {
    val msg = FormatWorker.mapError(rawSignIn, "https://youtube.com/shorts/abc123")
    assertEquals(youTubeSignIn, msg)
  }

  @Test
  fun youtubeIsNeverToldToLogInToX() {
    listOf(
      "https://youtu.be/abc",
      "https://youtube.com/watch?v=abc",
      "https://m.youtube.com/watch?v=abc",
      "https://youtube.com/shorts/abc",
    ).forEach { url ->
      assertFalse("$url must not say Need X login", FormatWorker.mapError(rawSignIn, url) == xLogin)
      assertFalse("$url must not say Need X login", FormatWorker.mapError(xSignIn, url) == xLogin)
    }
  }

  @Test
  fun xComKeepsTheXLoginAdvice() {
    assertEquals(xLogin, FormatWorker.mapError(xSignIn, "https://x.com/someone/status/123"))
    assertEquals(xLogin, FormatWorker.mapError(rawSignIn, "https://x.com/someone/status/123"))
  }

  @Test
  fun twitterAndSubdomainsKeepTheXLoginAdvice() {
    listOf(
      "https://twitter.com/someone/status/123",
      "https://www.x.com/someone/status/123",
      "https://mobile.twitter.com/someone/status/123",
    ).forEach { url ->
      assertEquals(url, xLogin, FormatWorker.mapError(xSignIn, url))
    }
  }

  @Test
  fun otherSitesSayGenericSignIn() {
    assertEquals(generic, FormatWorker.mapError(xSignIn, "https://vimeo.com/12345"))
    assertEquals(generic, FormatWorker.mapError(rawSignIn, "https://instagram.com/reel/abc"))
    assertEquals(generic, FormatWorker.mapError(xSignIn, null))
  }

  @Test
  fun hostParsingHandlesTheShapesTheShareSheetSends() {
    assertEquals("youtu.be", FormatWorker.hostOf("youtu.be/abc?si=1"))
    // hostOf keeps the subdomain exactly as sent; the classifiers are what widen the match.
    assertEquals("www.youtube.com", FormatWorker.hostOf("  https://www.youtube.com/watch?v=x  "))
    assertEquals("x.com", FormatWorker.hostOf("https://x.com/a/status/1"))
    assertEquals("", FormatWorker.hostOf(null))
    assertEquals("", FormatWorker.hostOf(""))
  }

  @Test
  fun hostClassifiersDoNotOverreach() {
    // A lookalike domain must not be treated as X, or its "sign in" would send people to X.
    assertFalse(FormatWorker.isXHost("notx.com"))
    assertFalse(FormatWorker.isXHost("x.com.evil.example"))
    assertTrue(FormatWorker.isXHost("x.com"))
    assertTrue(FormatWorker.isXHost("api.x.com"))
    assertFalse(FormatWorker.isYouTubeHost("notyoutube.com"))
    assertTrue(FormatWorker.isYouTubeHost("youtu.be"))
  }

  @Test
  fun unrelatedErrorsAreUnaffected() {
    // The host logic must not swallow other messages.
    assertEquals(
      "Download incomplete — the connection dropped. Nothing was saved.",
      FormatWorker.mapError("ERROR: incomplete download", "https://youtu.be/abc"),
    )
    assertEquals("Cancelled", FormatWorker.mapError("cancelled by user", "https://x.com/a/status/1"))
  }
}
