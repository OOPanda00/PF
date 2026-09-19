package com.panda.finance.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt DESC") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC") fun transactions(): Flow<List<FinanceTransaction>>
    @Query("SELECT * FROM accounts") suspend fun allAccounts(): List<Account>
    @Query("SELECT * FROM transactions") suspend fun allTransactions(): List<FinanceTransaction>
    @Insert suspend fun insertAccount(account: Account): Long
    @Insert suspend fun insertAccounts(accounts: List<Account>)
    @Insert suspend fun insertTransaction(tx: FinanceTransaction): Long
    @Insert suspend fun insertTransactions(txs: List<FinanceTransaction>)
    @Update suspend fun updateAccount(account: Account)
    @Delete suspend fun deleteTransaction(tx: FinanceTransaction)
    @Delete suspend fun deleteAccount(account: Account)
    @Query("DELETE FROM transactions WHERE accountId = :accountId") suspend fun deleteTransactionsForAccount(accountId: Long)
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1") suspend fun getAccount(id: Long): Account?

    @Transaction
    suspend fun undoTransaction(tx: FinanceTransaction) {
        if (tx.type == TransactionType.PAYMENT || tx.type == TransactionType.RECEIPT) {
            tx.accountId?.let { id ->
                getAccount(id)?.let { account ->
                    updateAccount(account.copy(settledAmount = (account.settledAmount - tx.amount).coerceAtLeast(0)))
                }
            }
        }
        deleteTransaction(tx)
    }
}

@Database(entities = [Account::class, FinanceTransaction::class], version = 2, exportSchema = false)
@TypeConverters(FinanceConverters::class)
abstract class FinanceDatabase : RoomDatabase() { abstract fun dao(): FinanceDao }
