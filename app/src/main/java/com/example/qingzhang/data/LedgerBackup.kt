package com.example.qingzhang.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LedgerBackup(private val database: LedgerDatabase) {
    suspend fun exportTo(context: Context, uri: Uri) = withBackupFile(context) { file ->
        write(file)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法写入所选备份位置")
        output.use { file.inputStream().use { input -> input.copyTo(it) } }
        Unit
    }

    suspend fun restoreFrom(context: Context, uri: Uri) = withBackupFile(context) { file ->
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开所选备份")
        input.use {
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "账本备份不能超过 32 MiB" }
                    output.write(buffer, 0, count)
                }
            }
        }
        restore(file)
    }

    private suspend fun <T> withBackupFile(context: Context, action: suspend (File) -> T): T =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("ledger-backup-", ".json", context.cacheDir)
            try {
                action(file)
            } finally {
                file.delete()
            }
        }

    suspend fun write(file: File) = database.withTransaction {
        val db = database.openHelper.writableDatabase
        JsonWriter(file.bufferedWriter()).use { writer ->
            writer.beginObject()
            writer.name("qingzhangLedger").value(2L)
            for ((table, columns) in tables) {
                writer.name(table).beginArray()
                db.query("SELECT ${columns.joinToString()} FROM $table ORDER BY id").use { rows ->
                    while (rows.moveToNext()) {
                        writer.beginArray()
                        for (index in columns.indices) {
                            when (rows.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                                Cursor.FIELD_TYPE_INTEGER -> writer.value(rows.getLong(index))
                                Cursor.FIELD_TYPE_STRING -> writer.value(rows.getString(index))
                                else -> error("账本包含无法备份的数据类型")
                            }
                        }
                        writer.endArray()
                    }
                }
                writer.endArray()
            }
            writer.endObject()
        }
        require(file.length() <= MAX_BYTES) { "账本备份不能超过 32 MiB" }
    }

    suspend fun restore(file: File) = database.withTransaction {
        require(file.length() in 1..MAX_BYTES.toLong()) { "备份为空或超过 32 MiB" }
        val db = database.openHelper.writableDatabase
        for (table in tables.keys) {
            val where = if (table == "categories") " WHERE isCustom != 0" else ""
            db.query("SELECT 1 FROM $table$where LIMIT 1").use {
                require(!it.moveToFirst()) { "只能恢复到空账本。当前账本已有数据，未被覆盖" }
            }
        }
        // Built-in IDs can differ across installations. Restore the original IDs and their references together.
        db.execSQL("DELETE FROM categories")
        JsonReader(InputStreamReader(file.inputStream(), Charsets.UTF_8.newDecoder()).buffered()).use { reader ->
            reader.beginObject()
            require(reader.nextName() == "qingzhangLedger" && reader.peek() == JsonToken.NUMBER
            ) { "无法识别此轻账备份的格式或版本" }
            val version = reader.nextString()
            require(version == "1" || version == "2") { "无法识别此轻账备份的格式或版本" }
            for ((table, columns) in tables) {
                require(reader.nextName() == table) { "备份缺少 $table 数据" }
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginArray()
                    val values = ContentValues(columns.size)
                    for (column in columns) {
                        if (version == "1" && column == "fromPlan") {
                            values.put(column, if (values.getAsLong("planId") != null) 1L else 0L)
                        } else {
                            reader.readColumn(column, values)
                        }
                    }
                    reader.endArray()
                    if (table == "transactions") {
                        require(values.getAsLong("amountCents") > 0) { "备份账目金额必须大于零" }
                    } else if (table == "savings_entries" && values.getAsLong("planId") != null) {
                        require(values.getAsLong("fromPlan") == 1L) { "备份流水的来源与关联计划不一致" }
                    }
                    db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                }
                reader.endArray()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "备份文件含有多余内容" }
        }
    }

    private fun JsonReader.readColumn(column: String, values: ContentValues) {
        if (peek() == JsonToken.NULL) {
            require(column in nullableColumns) { "备份字段 $column 不能为空" }
            nextNull()
            values.putNull(column)
        } else if (column in textColumns) {
            require(peek() == JsonToken.STRING) { "备份字段 $column 应为文本" }
            val value = nextString()
            when (column) {
                "type" -> TransactionType.valueOf(value)
                "source" -> ImportSource.valueOf(value)
                "name" -> require(value.length in 1..40) { "备份名称长度无效" }
                "note" -> require(value.length <= 200) { "备份备注过长" }
                "merchant" -> require(value.length <= 100) { "备份商户名称过长" }
                else -> require(value.length <= 512) { "备份标识过长" }
            }
            values.put(column, value)
        } else {
            require(peek() == JsonToken.NUMBER) { "备份字段 $column 应为整数" }
            val value = requireNotNull(nextString().toLongOrNull()) { "备份字段 $column 超出整数范围" }
            val valid = when (column) {
                "id", "categoryId", "goalId", "incomeId", "planId" -> value > 0
                "archived", "isCustom", "extraDailyExpense", "fromPlan" -> value in 0..1
                "amountCents" -> value != 0L && value in -MAX_CENTS..MAX_CENTS
                "budgetCents", "targetCents" -> value in 1..MAX_CENTS
                "reserveCents", "detachedSavingsCents" -> value in 0..MAX_CENTS
                "startDay", "targetDay", "day" -> value in -719_162L..2_932_896L
                else -> true
            }
            require(valid) { "备份字段 $column 的值无效" }
            values.put(column, value)
        }
    }

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_CENTS = 99_999_999_999L
        // Keep existing columns in file order and insert parents before their children.
        val tables = linkedMapOf(
            "categories" to "id name type color isCustom sortOrder",
            "transactions" to "id amountCents type categoryId occurredAt note merchant source externalId importKey createdAt updatedAt extraDailyExpense",
            "savings_goals" to "id name targetCents targetDay archived",
            "living_plans" to "id startDay budgetCents reserveCents goalId incomeId archived detachedSavingsCents",
            "savings_entries" to "id goalId planId amountCents day fromPlan",
        ).mapValues { (_, columns) -> columns.split(' ') }
        val nullableColumns = setOf("externalId", "importKey", "targetDay", "goalId", "incomeId", "planId")
        val textColumns = setOf("name", "type", "note", "merchant", "source", "externalId", "importKey")
    }
}
