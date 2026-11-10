package com.example.qingzhang.update

import java.net.URI
import org.json.JSONException
import org.json.JSONObject

data class UpdateManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)

object UpdateUrlPolicy {
    private val localTestHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")

    fun requireSafe(raw: String, debugBuild: Boolean): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "请先填写版本清单地址" }
        require(value.length <= 2_000) { "更新地址过长" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("更新地址格式不正确") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(uri.userInfo == null) { "更新地址不能包含账号信息" }
        require(!host.isNullOrBlank()) { "更新地址缺少域名" }
        when (scheme) {
            "https" -> Unit
            "http" -> require(debugBuild && host in localTestHosts) {
                "更新地址必须使用 HTTPS"
            }
            else -> throw IllegalArgumentException("更新地址必须使用 HTTPS")
        }
        return value
    }
}

object UpdateManifestParser {
    const val MAX_APK_BYTES = 100L * 1024 * 1024

    fun parse(
        json: String,
        expectedPackageName: String,
        debugBuild: Boolean,
    ): UpdateManifest {
        require(json.length <= 64 * 1024) { "版本清单过大" }
        val objectJson = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw IllegalArgumentException("版本清单不是有效的 JSON")
        }
        val packageName = objectJson.optString("packageName").trim()
        require(packageName == expectedPackageName) { "版本清单对应的应用包名不匹配" }
        val versionCode = objectJson.optLong("versionCode", 0)
        require(versionCode > 0) { "版本清单缺少有效的 versionCode" }
        val versionName = objectJson.optString("versionName").trim()
        require(versionName.isNotEmpty() && versionName.length <= 32) { "版本清单缺少有效的版本名称" }
        val apkUrl = UpdateUrlPolicy.requireSafe(objectJson.optString("apkUrl"), debugBuild)
        val sha256 = objectJson.optString("sha256").trim().lowercase()
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "版本清单中的 SHA-256 无效" }
        val sizeBytes = objectJson.optLong("size", 0)
        require(sizeBytes in 1..MAX_APK_BYTES) { "版本清单中的安装包大小无效" }
        val releaseNotes = objectJson.optString("releaseNotes").trim().take(4_000)
        return UpdateManifest(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            releaseNotes = releaseNotes,
        )
    }
}
