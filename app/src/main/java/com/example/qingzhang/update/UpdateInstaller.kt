package com.example.qingzhang.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed interface InstallLaunchResult {
    data object LauncherOpened : InstallLaunchResult
    data object PermissionRequired : InstallLaunchResult
    data class Failed(val message: String) : InstallLaunchResult
}

object UpdateInstaller {
    fun install(context: Context, apkFile: File): InstallLaunchResult {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return InstallLaunchResult.Failed("更新文件不存在，请重新下载")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(settingsIntent)
                InstallLaunchResult.PermissionRequired
            }.getOrElse {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                InstallLaunchResult.Failed("请允许本应用安装未知应用后重试")
            }
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = android.content.ClipData.newRawUri("更新安装包", uri)
            }
            context.startActivity(intent)
            InstallLaunchResult.LauncherOpened
        }.getOrElse {
            InstallLaunchResult.Failed(it.message ?: "无法打开系统安装器")
        }
    }
}
