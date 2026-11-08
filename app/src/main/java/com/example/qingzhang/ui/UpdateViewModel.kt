package com.example.qingzhang.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.qingzhang.BuildConfig
import com.example.qingzhang.data.AppSettings
import com.example.qingzhang.data.SettingsRepository
import com.example.qingzhang.update.InstallLaunchResult
import com.example.qingzhang.update.InstalledVersion
import com.example.qingzhang.update.UpdateInstaller
import com.example.qingzhang.update.UpdateManifest
import com.example.qingzhang.update.UpdateRepository
import com.example.qingzhang.update.UpdateUrlPolicy
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    ERROR,
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val progress: Int = 0,
    val error: String = "",
    val installedVersion: InstalledVersion = InstalledVersion(0, ""),
)

class UpdateViewModel(
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        UpdateUiState(installedVersion = updateRepository.currentVersion()),
    )
    val state = _state.asStateFlow()
    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadedApk: File? = null

    init {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            if (current.autoCheckUpdates && current.updateManifestUrl.isNotBlank()) {
                checkForUpdate(manual = false)
            }
        }
    }

    fun checkForUpdate(manual: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) {
            if (manual) notify("正在检查或下载更新，请稍候")
            return
        }
        checkJob = viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val url = currentSettings.updateManifestUrl.trim()
            if (url.isEmpty()) {
                _state.update { it.copy(phase = UpdatePhase.IDLE, error = "请先填写版本清单地址") }
                if (manual) notify("请先填写服务器上的版本清单地址")
                return@launch
            }
            _state.update {
                it.copy(phase = UpdatePhase.CHECKING, error = "", progress = 0, manifest = null)
            }
            runCatching { updateRepository.fetchManifest(url) }
                .onSuccess { manifest ->
                    if (manifest.versionCode <= _state.value.installedVersion.versionCode) {
                        _state.update { it.copy(phase = UpdatePhase.UP_TO_DATE, manifest = null) }
                        if (manual) notify("当前已是最新版本")
                    } else {
                        _state.update { it.copy(phase = UpdatePhase.AVAILABLE, manifest = manifest) }
                    }
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    _state.update {
                        it.copy(phase = UpdatePhase.ERROR, error = error.message ?: "检查更新失败")
                    }
                    if (manual) notify(error.message ?: "检查更新失败")
                }
        }
    }

    fun downloadUpdate() {
        val manifest = _state.value.manifest ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            downloadedApk?.delete()
            downloadedApk = null
            _state.update {
                it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, error = "")
            }
            runCatching {
                updateRepository.downloadAndVerify(manifest) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }.onSuccess { file ->
                downloadedApk = file
                _state.update { it.copy(phase = UpdatePhase.READY, progress = 100) }
                notify("更新已下载，请继续安装")
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                _state.update {
                    it.copy(phase = UpdatePhase.ERROR, error = error.message ?: "下载更新失败")
                }
                notify(error.message ?: "下载更新失败")
            }
        }
    }

    fun installUpdate(context: Context) {
        val file = downloadedApk
        if (file == null) {
            notify("更新文件不存在，请重新下载")
            if (_state.value.phase == UpdatePhase.READY) {
                _state.update { it.copy(phase = UpdatePhase.AVAILABLE) }
            }
            return
        }
        when (val result = UpdateInstaller.install(context, file)) {
            InstallLaunchResult.LauncherOpened -> notify("正在打开系统安装器")
            InstallLaunchResult.PermissionRequired ->
                notify("请允许本应用安装未知应用，返回后再次点击“安装更新”")
            is InstallLaunchResult.Failed -> notify(result.message)
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(phase = UpdatePhase.IDLE) }
    }

    fun saveUpdateSettings(url: String, autoCheck: Boolean) {
        viewModelScope.launch {
            runCatching {
                val normalized = if (url.isBlank()) "" else UpdateUrlPolicy.requireSafe(url, BuildConfig.DEBUG)
                settingsRepository.updateUpdateSettings(normalized, autoCheck)
                normalized
            }.onSuccess { normalized ->
                notify("更新设置已保存")
                if (normalized.isNotEmpty()) checkForUpdate(manual = true)
            }.onFailure { error ->
                notify(error.message ?: "更新设置保存失败")
            }
        }
    }

    private fun notify(message: String) {
        _events.tryEmit(message)
    }

    companion object {
        fun factory(
            settingsRepository: SettingsRepository,
            updateRepository: UpdateRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(settingsRepository, updateRepository) as T
        }
    }
}
