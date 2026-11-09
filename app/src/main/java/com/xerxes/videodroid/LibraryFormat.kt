package com.xerxes.videodroid

import java.util.Locale

/** Stage 4 display helpers, pure so they can be unit tested off-device. */
object LibraryFormat {

    /** 0 → "", 75_000 → "1:15", 3_725_000 → "1:02:05". */
    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 0 → "", 512 KB → "0 MB", 1.5 GiB → "1.5 GB". */
    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }
}
