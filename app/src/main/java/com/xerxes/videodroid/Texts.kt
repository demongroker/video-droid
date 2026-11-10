package com.xerxes.videodroid

/**
 * Count strings, in one place so they are always correct.
 *
 * The app used to build "$n file${if (n == 1) "" else "s"}" by hand in a dozen places, which is
 * how "1 videos" ends up in a dialog nobody re-read.
 */
object Texts {

    fun videos(n: Int): String = "$n ${if (n == 1) "video" else "videos"}"

    fun files(n: Int): String = "$n ${if (n == 1) "file" else "files"}"

    fun duplicates(n: Int): String = "$n ${if (n == 1) "duplicate" else "duplicates"}"

    fun logs(n: Int): String = "$n ${if (n == 1) "log" else "logs"}"

    fun jobs(n: Int): String = "$n ${if (n == 1) "job" else "jobs"}"

    /** "1 selected" / "3 selected" — the selected count never takes a plural noun. */
    fun selected(n: Int): String = "$n selected"
}
