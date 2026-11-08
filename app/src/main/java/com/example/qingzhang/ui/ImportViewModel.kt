package com.example.qingzhang.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.qingzhang.data.BudgetWarning
import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.LedgerRepository
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.importer.ImportCandidate
import com.example.qingzhang.importer.ImportParseResult
import com.example.qingzhang.importer.StatementImporter
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val ImportCandidate.canSelect: Boolean get() = !duplicate && !needsReview

data class ImportUiState(
    val loading: Boolean = false,
    val committing: Boolean = false,
    val fileName: String = "",
    val result: ImportParseResult? = null,
    val commitMessage: String = "",
    val legacyConflicts: Map<Long, List<String>> = emptyMap(),
    val legacyChoices: Map<Long, String> = emptyMap(),
)

sealed interface ImportEvent {
    data class Message(val text: String) : ImportEvent
    data class Warnings(val values: List<BudgetWarning>) : ImportEvent
}

class ImportViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state = _state.asStateFlow()
    private val _sourceHint = MutableStateFlow<ImportSource?>(null)
    val sourceHint = _sourceHint.asStateFlow()
    private val _events = MutableSharedFlow<ImportEvent>()
    val events = _events.asSharedFlow()
    private var workJob: Job? = null

    fun setSourceHint(source: ImportSource?) {
        _sourceHint.value = source
    }

    fun reset() {
        workJob?.cancel()
        workJob = null
        _state.value = ImportUiState()
    }

    fun parse(context: Context, uri: Uri) {
        workJob?.cancel()
        val sourceHint = _sourceHint.value
        workJob = viewModelScope.launch {
            _state.value = ImportUiState(loading = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val fileName = context.displayName(uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited() }
                        ?: throw IllegalArgumentException("无法打开所选文件")
                    repository.ensureDefaultCategories()
                    val categories = repository.categories.first()
                    val result = StatementImporter.parse(bytes, fileName, sourceHint, categories)
                    val legacy = repository.previewLegacyImports(result.candidates.legacyMatchesByKey())
                    val existing = repository.existingImportKeys(result.candidates.map { it.importKey }) + legacy.duplicates
                    val conflicted = legacy.conflicts.values.flatten().toSet()
                    ImportUiState(
                        fileName = fileName,
                        result = result.copy(candidates = result.candidates.map { candidate ->
                            when (candidate.importKey) {
                                in existing -> candidate.copy(selected = false, duplicate = true)
                                in conflicted -> candidate.copy(selected = false)
                                else -> candidate
                            }
                        }),
                        legacyConflicts = legacy.conflicts,
                    )
                }
            }.onSuccess { preview ->
                _state.value = preview
                if (preview.result?.candidates.isNullOrEmpty()) _events.emit(ImportEvent.Message("没有可导入的有效账目，请查看解析说明"))
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                _state.value = ImportUiState()
                _events.emit(ImportEvent.Message(error.message ?: "文件解析失败，请检查文件格式"))
            }
        }
    }

    fun chooseLegacyMatch(transactionId: Long, importKey: String) {
        val state = _state.value
        if (state.committing || state.commitMessage.isNotEmpty() ||
            importKey !in state.legacyConflicts[transactionId].orEmpty()
        ) return
        _state.value = state.copy(
            legacyChoices = state.legacyChoices.filterValues { it != importKey } + (transactionId to importKey),
        )
    }

    fun toggle(candidateId: Int, selected: Boolean) {
        updateCandidates { candidate ->
            if (candidate.id == candidateId && candidate.canSelect) candidate.copy(selected = selected) else candidate
        }
    }

    fun updateCandidate(updated: ImportCandidate) {
        updateCandidates { candidate ->
            if (candidate.id == updated.id && !candidate.duplicate && updated.canSelect) {
                updated.copy(selected = true)
            } else {
                candidate
            }
        }
    }

    fun commit() {
        val state = _state.value
        if (state.committing || state.commitMessage.isNotEmpty()) return
        if (state.legacyChoices.keys != state.legacyConflicts.keys) {
            _events.tryEmit(ImportEvent.Message("请先确认旧账对应的原始记录"))
            return
        }
        val candidates = state.result?.candidates.orEmpty()
        val matchedLegacyKeys = state.legacyChoices.values.toSet()
        val selected = candidates.filter { it.selected && it.canSelect && it.importKey !in matchedLegacyKeys }
        if (selected.isEmpty() && state.legacyChoices.isEmpty()) {
            _events.tryEmit(ImportEvent.Message("请至少选择一条待导入账目"))
            return
        }
        workJob?.cancel()
        workJob = viewModelScope.launch {
            _state.value = _state.value.copy(committing = true)
            runCatching {
                val now = System.currentTimeMillis()
                val entities = selected.map { candidate ->
                    TransactionEntity(
                        amountCents = candidate.amountCents,
                        type = candidate.type,
                        categoryId = candidate.categoryId ?: throw IllegalArgumentException("存在未选择分类的账目"),
                        occurredAt = candidate.occurredAt,
                        note = candidate.note,
                        merchant = candidate.merchant,
                        source = candidate.source,
                        externalId = candidate.externalId,
                        importKey = candidate.importKey,
                        createdAt = now,
                        updatedAt = now,
                    )
                }
                // Keep legacy matches assigned to the same rows shown in the preview.
                repository.importTransactions(entities, candidates.legacyMatchesByKey(), state.legacyChoices)
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    committing = false,
                    commitMessage = "成功导入 ${result.importedCount} 条，跳过重复 ${result.duplicateCount} 条",
                )
                _events.emit(ImportEvent.Message("导入完成：新增 ${result.importedCount} 条，重复 ${result.duplicateCount} 条"))
                if (result.warnings.isNotEmpty()) _events.emit(ImportEvent.Warnings(result.warnings))
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                _state.value = _state.value.copy(committing = false)
                _events.emit(ImportEvent.Message(error.message ?: "导入失败，未写入部分数据"))
            }
        }
    }

    private fun updateCandidates(transform: (ImportCandidate) -> ImportCandidate) {
        val result = _state.value.result ?: return
        _state.value = _state.value.copy(
            result = result.copy(candidates = result.candidates.map(transform)),
            commitMessage = "",
        )
    }

    private fun List<ImportCandidate>.legacyMatchesByKey() =
        distinctBy { it.importKey }.filter { it.legacyMatches.isNotEmpty() }.associate { it.importKey to it.legacyMatches }

    private fun InputStream.readLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= StatementImporter.MAX_FILE_BYTES) { "账单文件不能超过 10 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun Context.displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "账单文件"
    }

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(repository) as T
        }
    }
}
