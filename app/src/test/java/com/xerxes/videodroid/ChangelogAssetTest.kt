package com.xerxes.videodroid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The in-app Changelog is generated from CHANGELOG.md at build time. This test fails loudly if
 * the asset ever drifts behind the repo file again (the field report: the viewer stopped at
 * 2.3.6 while the app was on 2.4.x).
 */
class ChangelogAssetTest {

    @Test
    fun bundledChangelogStartsWithTheCurrentVersion() {
        val repo = File("CHANGELOG.md")
        val asset = File("src/main/assets/CHANGELOG.md")
        // The Gradle sync task runs before preBuild, so the asset must exist by test time.
        assertTrue("assets changelog missing", asset.isFile)
        val current = BuildConfig.VERSION_NAME
        val assetHead = asset.readLines().firstOrNull { it.startsWith("## ") }.orEmpty()
        assertTrue(
            "bundled changelog starts with '$assetHead' but the app is $current",
            assetHead.contains(current),
        )
        if (repo.isFile) {
            val repoHead = repo.readLines().firstOrNull { it.startsWith("## ") }.orEmpty()
            assertTrue("repo changelog is behind the app version: '$repoHead'", repoHead.contains(current))
        }
    }
}
