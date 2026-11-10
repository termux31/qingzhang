package com.example.qingzhang.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query(
        """SELECT t.*, c.name AS categoryName, c.color AS categoryColor
        FROM transactions t INNER JOIN categories c ON c.id = t.categoryId
        WHERE t.occurredAt >= :startInclusive AND t.occurredAt < :endExclusive
        ORDER BY t.occurredAt DESC, t.id DESC""",
    )
    fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<TransactionDetail>>

    @Query(
        """SELECT t.*, c.name AS categoryName, c.color AS categoryColor
        FROM transactions t INNER JOIN categories c ON c.id = t.categoryId
        WHERE t.occurredAt >= :startInclusive AND t.occurredAt < :endExclusive
        ORDER BY t.occurredAt DESC, t.id DESC""",
    )
    suspend fun getBetween(startInclusive: Long, endExclusive: Long): List<TransactionDetail>

    @Query(
        """SELECT t.*, c.name AS categoryName, c.color AS categoryColor
        FROM transactions t INNER JOIN categories c ON c.id = t.categoryId
        ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<TransactionDetail>>

    @Query(
        """SELECT t.*, c.name AS categoryName, c.color AS categoryColor
        FROM transactions t INNER JOIN categories c ON c.id = t.categoryId WHERE t.id = :id""",
    )
    suspend fun getDetailById(id: Long): TransactionDetail?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImported(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity): Int

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT importKey FROM transactions WHERE importKey IN (:keys)")
    suspend fun findExistingImportKeys(keys: List<String>): List<String>

    @Query("SELECT * FROM transactions WHERE importKey IN (:keys)")
    suspend fun findImportedTransactions(keys: List<String>): List<TransactionEntity>

    @Query("UPDATE transactions SET importKey = :key WHERE id = :id")
    suspend fun assignImportKey(id: Long, key: String)

    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
        WHERE type = 'EXPENSE' AND occurredAt >= :startInclusive AND occurredAt < :endExclusive""",
    )
    suspend fun expenseTotalBetween(startInclusive: Long, endExclusive: Long): Long

    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
        WHERE type = 'EXPENSE' AND extraDailyExpense = 0
        AND occurredAt >= :startInclusive AND occurredAt < :endExclusive""",
    )
    suspend fun dailyLimitExpenseBetween(startInclusive: Long, endExclusive: Long): Long

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE occurredAt >= :startInclusive AND occurredAt < :endExclusive)")
    suspend fun hasTransactionsBetween(startInclusive: Long, endExclusive: Long): Boolean
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY type DESC, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY type DESC, sortOrder, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun transactionCount(categoryId: Long): Int

    @Delete
    suspend fun delete(category: CategoryEntity)
}
