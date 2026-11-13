package com.xerxes.videodroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.5.5 regression guards for the static audit (V-P0-1..6). These read the source/config files
 * that ship in the APK and fail if a fix is ever reverted, which is the only way to unit test a
 * manifest flag or an overridden framework callback on the JVM.
 */
class AuditGuardTest {

    private val root = File("src/main")

    private fun text(path: String): String = File(root, path).readText()

    @Test
    fun backupIsDisabled() {
        val manifest = text("AndroidManifest.xml")
        assertTrue("allowBackup must be false", manifest.contains("android:allowBackup=\"false\""))
        assertTrue("dataExtractionRules must be referenced",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(File(root, "res/xml/data_extraction_rules.xml").isFile)
    }

    @Test
    fun serviceHandlesTheForegroundServiceTimeLimit() {
        val svc = text("java/com/xerxes/videodroid/DownloadService.kt")
        assertTrue("onTimeout(startId) missing", svc.contains("override fun onTimeout(startId: Int)"))
        assertTrue("onTimeout(startId, fgsType) missing", svc.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
    }

    @Test
    fun workersNeverClearTheSharedCancelFlag() {
        val worker = text("java/com/xerxes/videodroid/FormatWorker.kt")
        assertFalse("a starting job must not reset the global cancel flag",
            worker.contains("cancelRequested.set(false)"))
        assertFalse("a starting job must not reset the python cancel flag",
            worker.contains("callAttr(\"set_cancelled\", false)"))
    }

    @Test
    fun clearLoginRemovesTheStaleCookieCopy() {
        val cookies = text("java/com/xerxes/videodroid/XCookies.kt")
        assertTrue("clear() must delete the .stale copy", cookies.contains(".stale"))
    }

    @Test
    fun noPrivateAddressesInShippedConfig() {
        val nsc = text("res/xml/network_security_config.xml")
        assertFalse("private tailnet address must not ship in the repo",
            Regex("""\b100\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").containsMatchIn(nsc))
    }

    @Test
    fun corruptQueueIsQuarantinedNotReBackedUp() {
        val queue = text("java/com/xerxes/videodroid/DownloadQueue.kt")
        assertTrue("corrupt queue file must be moved out of the way once",
            queue.contains(".quarantined"))
    }

    @Test
    fun unexpectedWorkerFailureKeepsTheJob() {
        val svc = text("java/com/xerxes/videodroid/DownloadService.kt")
        assertFalse("catch-all must not delete the queued job",
            svc.contains("} catch (_: Throwable) {\n            runCatching { DownloadQueue.finish("))
        assertTrue("failure path should mark the job failed", svc.contains("Internal error: "))
    }
}
