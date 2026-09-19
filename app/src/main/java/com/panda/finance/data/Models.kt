package com.panda.finance.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class AccountType { OBLIGATION, RECEIVABLE }

enum class TransactionType { INCOME, EXPENSE, RECEIPT, PAYMENT }

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val originalAmount: Long,
    val settledAmount: Long = 0,
    val dueDate: Long? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) { val remaining: Long get() = (originalAmount - settledAmount).coerceAtLeast(0) }

@Entity(tableName = "transactions")
data class FinanceTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    val amount: Long,
    val accountId: Long? = null,
    val description: String,
    val createdAt: Long = System.currentTimeMillis()
)

class FinanceConverters {
    @TypeConverter fun fromAccountType(value: AccountType): String = value.name
    @TypeConverter fun toAccountType(value: String): AccountType = AccountType.valueOf(value)
    @TypeConverter fun fromTransactionType(value: TransactionType): String = value.name
    @TypeConverter fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)
}
