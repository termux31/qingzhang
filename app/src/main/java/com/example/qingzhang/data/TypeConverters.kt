package com.example.qingzhang.data

import androidx.room.TypeConverter

class LedgerTypeConverters {
    @TypeConverter
    fun transactionTypeToString(value: TransactionType): String = value.name

    @TypeConverter
    fun stringToTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun importSourceToString(value: ImportSource): String = value.name

    @TypeConverter
    fun stringToImportSource(value: String): ImportSource = ImportSource.valueOf(value)
}
