package com.example.qingzhang.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.qingzhang.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledVersion(val versionCode: Long, val versionName: String)

class UpdateRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun currentVersion(): InstalledVersion {
        val info = packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return InstalledVersion(code, info.versionName ?: BuildConfig.VERSION_NAME)
    }

    suspend fun fetchManifest(manifestUrl: String): UpdateManifest = withContext(Dispatchers.IO) {
        val safeUrl = UpdateUrlPolicy.requireSafe(manifestUrl, BuildConfig.DEBUG)
        val connection = openConnection(safeUrl, "application/json")
        try {
            val body = connection.inputStream.use { it.readLimited(64 * 1024) }
                .toString(Charsets.UTF_8)
            UpdateManifestParser.parse(body, appContext.packageName, BuildConfig.DEBUG)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndVerify(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val safeUrl = UpdateUrlPolicy.requireSafe(manifest.apkUrl, BuildConfig.DEBUG)
        val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.deleteRecursively() }
        val target = File(directory, "qingzhang-update.apk")
        val temporary = File(directory, "qingzhang-update.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = openConnection(safeUrl, "application/vnd.android.package-archive")
        try {
            val declaredLength = connection.contentLengthLong
            if (declaredLength > UpdateManifestParser.MAX_APK_BYTES) {
                throw IllegalArgumentException("安装包超过允许的大小")
            }
            var downloaded = 0L
            var lastPercent = -1
            FileOutputStream(temporary).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > UpdateManifestParser.MAX_APK_BYTES) {
                            throw IllegalArgumentException("安装包超过允许的大小")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = ((downloaded * 100) / manifest.sizeBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.fd.sync()
                }
            }
            require(downloaded == manifest.sizeBytes) { "安装包大小校验失败，请重新下载" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == manifest.sha256) { "安装包 SHA-256 校验失败，已阻止安装" }
            verifyApk(temporary, manifest)
            require(temporary.renameTo(target)) { "无法保存更新文件" }
            onProgress(100)
            target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun clearDownloadedApk() {
        File(appContext.cacheDir, "updates").listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun openConnection(rawUrl: String, accept: String): HttpURLConnection {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("User-Agent", "QingZhang-Android-Updater/${BuildConfig.VERSION_NAME}")
        val response = connection.responseCode
        if (response !in 200..299) {
            connection.errorStream?.close()
            throw IOException("更新服务器返回 HTTP $response")
        }
        UpdateUrlPolicy.requireSafe(connection.url.toString(), BuildConfig.DEBUG)
        return connection
    }

    private fun verifyApk(file: File, manifest: UpdateManifest) {
        val flagSigning = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flagSigning)
            ?: throw IllegalArgumentException("下载内容不是有效的 Android 安装包")
        require(archive.packageName == appContext.packageName) { "安装包包名与应用不一致" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        require(archiveVersion == manifest.versionCode) { "APK 版本与版本清单不一致" }
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
        }
        val installedSigners = installed.signatureHashes()
        val archiveSigners = archive.signatureHashes()
        require(installedSigners.isNotEmpty() && archiveSigners == installedSigners) {
            "更新包签名与原应用不一致，已阻止安装"
        }
    }

    private fun PackageInfo.signatureHashes(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "版本清单超过允许大小" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
