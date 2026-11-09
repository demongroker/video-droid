package com.xerxes.videodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Stage 5: content identity, and the index that remembers it. */
class FileDigestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hashesFileContents() {
        val f = tmp.newFile("a.bin")
        f.writeBytes("hello".toByteArray())
        // Known SHA-256 of "hello".
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            FileDigest.sha256(f),
        )
    }

    @Test
    fun sameBytesGiveSameHash() {
        val a = tmp.newFile("a.mp4").apply { writeBytes(ByteArray(4096) { 7 }) }
        val b = tmp.newFile("different-name.mp4").apply { writeBytes(ByteArray(4096) { 7 }) }
        assertEquals(FileDigest.sha256(a), FileDigest.sha256(b))
    }

    @Test
    fun differentBytesDiffer() {
        val a = tmp.newFile("a.mp4").apply { writeBytes(ByteArray(2048) { 1 }) }
        val b = tmp.newFile("b.mp4").apply { writeBytes(ByteArray(2048) { 2 }) }
        assertEquals(false, FileDigest.sha256(a) == FileDigest.sha256(b))
    }

    @Test
    fun rejectsMissingAndEmptyFiles() {
        assertNull(FileDigest.sha256(File(tmp.root, "nope.mp4")))
        assertNull(FileDigest.sha256(tmp.newFile("empty.mp4")))
    }
}
