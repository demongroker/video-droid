package com.xerxes.videodroid

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Stage 5: the download engine (yt-dlp) can be refreshed without shipping a new APK.
 *
 * The bundled copy lives inside the APK and is frozen at build time. This fetches the latest
 * pure-Python wheel from PyPI, unpacks `yt_dlp/` into `filesDir/engine`, and [dl.py] puts that
 * directory ahead of the bundled one on `sys.path`. Uninstalling, or deleting the folder,
 * falls straight back to the bundled engine — there is nothing to roll back by hand.
 */
object EngineUpdater {

    private const val PYPI_JSON = "https://pypi.org/pypi/yt-dlp/json"
    const val ENGINE_DIR = "engine"
    private const val VERSION_FILE = "version.txt"
    private const val STAMP_FILE = "last_check.txt"

    fun engineDir(ctx: Context): File = File(ctx.filesDir, ENGINE_DIR)

    /** Version of the on-disk engine, or "bundled" when nothing has been unpacked yet. */
    fun currentVersion(ctx: Context): String {
        val f = File(engineDir(ctx), VERSION_FILE)
        return runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "bundled"
    }

    fun lastChecked(ctx: Context): String {
        val f = File(engineDir(ctx), STAMP_FILE)
        return runCatching { f.readText().trim() }.getOrNull().orEmpty()
    }

    /** @return a short human message describing what happened. */
    suspend fun update(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val meta = fetchJson(PYPI_JSON) ?: return@runCatching Result.failure<String>(
                IllegalStateException("PyPI did not answer"),
            )
            val version = meta.optJSONObject("info")?.optString("version").orEmpty()
            if (version.isEmpty()) return@runCatching Result.failure<String>(
                IllegalStateException("PyPI JSON had no version"),
            )
            if (version == currentVersion(ctx)) {
                stamp(ctx)
                return@runCatching Result.success("Engine already up to date ($version)")
            }
            val wheel = pickWheel(meta) ?: return@runCatching Result.failure<String>(
                IllegalStateException("No pure-Python wheel published for $version"),
            )
            val zip = File(ctx.cacheDir, "yt_dlp-$version.zip")
            if (!download(wheel.url, zip)) return@runCatching Result.failure<String>(
                IllegalStateException("Download failed"),
            )

            // Stage 5b: never unpack bytes we cannot tie to PyPI's own digest. A truncated or
            // tampered wheel would replace a working engine with a broken one, and every
            // download would fail with an import error.
            val got = FileDigest.sha256(zip)
            if (got == null || !got.equals(wheel.sha256, ignoreCase = true)) {
                zip.delete()
                JobDiag.noteStatus(
                    "engine: digest mismatch for $version (got ${got ?: "unreadable"}, " +
                        "expected ${wheel.sha256}) — keeping ${currentVersion(ctx)}",
                )
                return@runCatching Result.failure<String>(
                    IllegalStateException(
                        "Wheel checksum did not match PyPI — kept the current engine",
                    ),
                )
            }
            JobDiag.noteStatus("engine: digest ok for $version")

            val target = engineDir(ctx)
            val staging = File(ctx.cacheDir, "engine-staging")
            staging.deleteRecursively()
            staging.mkdirs()
            val unpacked = unzipPackage(zip, staging)
            zip.delete()
            if (unpacked <= 0) return@runCatching Result.failure<String>(
                IllegalStateException("Wheel contained no yt_dlp package"),
            )

            // Swap in only after a complete unpack: a half-written engine would break every
            // download, so the old one stays until the new one is whole.
            target.deleteRecursively()
            target.mkdirs()
            if (!File(staging, "yt_dlp").renameTo(File(target, "yt_dlp"))) {
                File(staging, "yt_dlp").copyRecursively(File(target, "yt_dlp"), overwrite = true)
            }
            staging.deleteRecursively()
            File(target, VERSION_FILE).writeText(version)
            stamp(ctx)
            Result.success("Engine updated to $version — takes effect on the next download")
        }.getOrElse { Result.failure(it) }
    }

    private fun stamp(ctx: Context) {
        runCatching {
            engineDir(ctx).mkdirs()
            File(engineDir(ctx), STAMP_FILE).writeText(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date()),
            )
        }
    }

    private fun fetchJson(url: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private data class Wheel(val url: String, val sha256: String)

    /** Only `py3-none-any` wheels: this runs on Chaquopy's Python with no compiled parts. */
    private fun pickWheel(meta: JSONObject): Wheel? {
        val urls = meta.optJSONArray("urls") ?: return null
        for (i in 0 until urls.length()) {
            val u = urls.optJSONObject(i) ?: continue
            val name = u.optString("filename")
            if (!name.endsWith(".whl")) continue
            if (!name.contains("py3-none-any")) continue
            val url = u.optString("url")
            val digest = u.optJSONObject("digests")?.optString("sha256").orEmpty()
            if (!url.startsWith("https://")) continue
            if (digest.length != 64) continue
            return Wheel(url, digest)
        }
        return null
    }

    private fun download(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VideoDroid-EngineUpdater")
            }
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.length() > 0
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun unzipPackage(zip: File, into: File): Int {
        var count = 0
        return try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (!name.startsWith("yt_dlp/")) {
                        zis.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        File(into, name).mkdirs()
                        zis.closeEntry()
                        continue
                    }
                    val out = File(into, name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    count++
                    zis.closeEntry()
                }
            }
            count
        } catch (t: Throwable) {
            count
        }
    }
}
