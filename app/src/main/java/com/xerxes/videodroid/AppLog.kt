package com.xerxes.videodroid

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory log of what the app has been doing.
 *
 * Its only job is to be attached to a crash report: the stack trace says where the process
 * died, this says what it was doing at the time. Bounded, so it can never grow into a
 * memory leak. Thread-safe because downloads run on several threads at once.
 */
object AppLog {

    private const val CAPACITY = 200
    private const val MAX_LINE = 400

    private val ring = ArrayDeque<String>(CAPACITY)
    private val lock = Any()

    fun add(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp ${message.take(MAX_LINE)}"
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
    }

    fun lines(): List<String> = synchronized(lock) { ring.toList() }

    fun clear() = synchronized(lock) { ring.clear() }
}
