package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Queue row and the job error must give the same sign-in advice for the same link.
 *
 * Both now go through FormatWorker's host classifiers, so this test pins the pairing: an X link
 * is told to log in to X, a YouTube link is not.
 */
class ErrorTextHostTest {

  private val rawSignIn = "ERROR: Sign in to confirm you're not a bot. Use --cookies-from-browser"

  @Test
  fun youtubeSignInIsNotAnXLoginProblem() {
    assertEquals(
      "YouTube is asking for a sign-in — the network looks automated. Try again later.",
      ErrorText.human(rawSignIn, "https://youtu.be/dQw4w9WgXcQ"),
    )
    assertEquals(
      "YouTube is asking for a sign-in — the network looks automated. Try again later.",
      ErrorText.human("ERROR: login required", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
    )
  }

  @Test
  fun xSignInStillSaysLogInToX() {
    assertEquals(
      "Needs an X login — Settings → Log in to X",
      ErrorText.human("ERROR: login required", "https://x.com/someone/status/123"),
    )
    assertEquals(
      "Needs an X login — Settings → Log in to X",
      ErrorText.human(rawSignIn, "https://twitter.com/someone/status/123"),
    )
  }

  @Test
  fun otherSitesAndUnknownHostsGetTheGenericLine() {
    assertEquals("Site needs sign-in.", ErrorText.human(rawSignIn, "https://vimeo.com/1"))
    // No URL at all: never guess in favour of X.
    assertEquals("Site needs sign-in.", ErrorText.human(rawSignIn))
  }

  @Test
  fun unrelatedErrorsAreUnchanged() {
    assertEquals("Not enough free space", ErrorText.human("No space left on device"))
    assertEquals("Timed out", ErrorText.human("read timed out", "https://youtu.be/abc"))
  }
}
