package com.example.qingzhang.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class TransactionType(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
}

enum class ImportSource(val label: String) {
    MANUAL("手动"),
    WECHAT("微信"),
    ALIPAY("支付宝"),
}

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name", "type"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TransactionType,
    val color: Long,
    val isCustom: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("occurredAt"),
        Index(value = ["importKey"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val type: TransactionType,
    val categoryId: Long,
    val occurredAt: Long,
    val note: String = "",
    val merchant: String = "",
    val source: ImportSource = ImportSource.MANUAL,
    val externalId: String? = null,
    val importKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TransactionDetail(
    @Embedded val transaction: TransactionEntity,
    val categoryName: String,
    val categoryColor: Long,
)

data class TransactionInput(
    val id: Long = 0,
    val amountCents: Long,
    val type: TransactionType,
    val categoryId: Long,
    val occurredAt: Long,
    val note: String = "",
    val merchant: String = "",
)

data class BillFilter(
    val start: LocalDate,
    val end: LocalDate,
    val type: TransactionType? = null,
    val categoryId: Long? = null,
    val query: String = "",
)

data class BudgetWarning(
    val date: LocalDate,
    val totalCents: Long,
    val limitCents: Long,
) {
    val exceededCents: Long get() = totalCents - limitCents
}

data class SaveOutcome(
    val transactionId: Long,
    val warning: BudgetWarning? = null,
)

data class ImportCommitResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val warnings: List<BudgetWarning>,
)
