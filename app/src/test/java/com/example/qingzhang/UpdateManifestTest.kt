package com.example.qingzhang

import com.example.qingzhang.data.AppSettings
import com.example.qingzhang.update.UpdateManifestParser
import com.example.qingzhang.update.UpdateUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val validJson = """
        {
          "packageName": "com.example.qingzhang",
          "versionCode": 3,
          "versionName": "1.1.1",
          "apkUrl": "https://example.com/updates/qingzhang-1.1.1.apk",
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "size": 18000000,
          "releaseNotes": "修复问题，优化导入",
          "force": false
        }
    """.trimIndent()

    @Test
    fun parsesValidManifest() {
        val manifest = UpdateManifestParser.parse(validJson, "com.example.qingzhang", debugBuild = false)
        assertEquals(3L, manifest.versionCode)
        assertEquals("1.1.1", manifest.versionName)
        assertEquals(18_000_000L, manifest.sizeBytes)
        assertFalse(manifest.force)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongPackageName() {
        UpdateManifestParser.parse(validJson, "com.example.other", debugBuild = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHash() {
        UpdateManifestParser.parse(
            validJson.replace(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "not-a-hash",
            ),
            "com.example.qingzhang",
            debugBuild = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedApk() {
        UpdateManifestParser.parse(
            validJson.replace("18000000", "104857601"),
            "com.example.qingzhang",
            debugBuild = false,
        )
    }

    @Test
    fun onlyAllowsDebugCleartextForLocalHosts() {
        assertEquals(
            "http://10.0.2.2:8080/latest.json",
            UpdateUrlPolicy.requireSafe("http://10.0.2.2:8080/latest.json", debugBuild = true),
        )
        assertTrue(
            runCatching {
                UpdateUrlPolicy.requireSafe("http://example.com/latest.json", debugBuild = true)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                UpdateUrlPolicy.requireSafe("http://10.0.2.2/latest.json", debugBuild = false)
            }.isFailure,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsInUrl() {
        UpdateUrlPolicy.requireSafe("https://user:pass@example.com/latest.json", debugBuild = false)
    }

    @Test
    fun defaultsToZeroLimitWithNoTransactions() {
        val defaults = AppSettings()
        assertEquals(0L, defaults.dailyLimitCents)
        assertFalse(defaults.dailyLimitEnabled)
    }
}
