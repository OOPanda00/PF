package com.panda.finance.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import com.panda.finance.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.panda.finance.domain.FinanceCalculator
import com.panda.finance.security.SecurityUtils

private val Application.settingsStore by preferencesDataStore("settings")

class FinanceViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Room.databaseBuilder(app, FinanceDatabase::class.java, "panda_finance.db")
        .fallbackToDestructiveMigration().build()
    private val dao = db.dao()
    private val reserveKey = intPreferencesKey("reserve_percent")
    private val darkKey = androidx.datastore.preferences.core.booleanPreferencesKey("dark_mode")
    private val languageKey = androidx.datastore.preferences.core.stringPreferencesKey("language")
    private val lockKey = androidx.datastore.preferences.core.booleanPreferencesKey("app_lock")
    private val biometricKey = androidx.datastore.preferences.core.booleanPreferencesKey("biometric_unlock")

    val accounts = dao.accounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transactions = dao.transactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reservePercent = app.settingsStore.data.map { it[reserveKey] ?: 30 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)
    val darkMode = app.settingsStore.data.map { it[darkKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val appLock = app.settingsStore.data.map { it[lockKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val biometricEnabled = app.settingsStore.data.map { it[biometricKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val language = app.settingsStore.data.map { it[languageKey] ?: "ar" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "ar")

    val balance = transactions.map { list ->
        list.sumOf { if (it.type == TransactionType.INCOME || it.type == TransactionType.RECEIPT) it.amount else -it.amount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setDarkMode(value: Boolean) = viewModelScope.launch {
        getApplication<Application>().settingsStore.edit { it[darkKey] = value }
    }

    fun setAppLock(value: Boolean) = viewModelScope.launch { getApplication<Application>().settingsStore.edit { it[lockKey] = value } }
    fun setBiometricEnabled(value: Boolean) = viewModelScope.launch { getApplication<Application>().settingsStore.edit { it[biometricKey] = value } }
    fun hasPin(): Boolean = SecurityUtils.hasPin(getApplication())
    fun setPin(pin: String) { SecurityUtils.setPin(getApplication(), pin) }
    fun verifyPin(pin: String): Boolean = SecurityUtils.verifyPin(getApplication(), pin)
    fun clearPin() { SecurityUtils.clearPin(getApplication()); setAppLock(false) }

    fun setLanguage(value: String) = viewModelScope.launch {
        getApplication<Application>().settingsStore.edit { it[languageKey] = if (value == "en") "en" else "ar" }
    }

    fun setReserve(value: Int) = viewModelScope.launch {
        getApplication<Application>().settingsStore.edit { it[reserveKey] = value.coerceIn(0, 80) }
    }

    fun addIncome(amount: Long, description: String) = viewModelScope.launch {
        if (amount > 0) dao.insertTransaction(FinanceTransaction(type = TransactionType.INCOME, amount = amount, description = description.ifBlank { "دخل" }))
    }

    fun addAccount(name: String, type: AccountType, amount: Long, note: String, dueDate: Long?) = viewModelScope.launch {
        if (name.isNotBlank() && amount > 0) dao.insertAccount(Account(name = name.trim(), type = type, originalAmount = amount, note = note.trim(), dueDate = dueDate))
    }

    fun settle(account: Account, amount: Long) = viewModelScope.launch {
        val max = if (account.type == AccountType.OBLIGATION) minOf(account.remaining, balance.value.coerceAtLeast(0)) else account.remaining
        val safe = amount.coerceIn(0, max)
        if (safe <= 0) return@launch
        dao.updateAccount(account.copy(settledAmount = account.settledAmount + safe))
        val type = if (account.type == AccountType.OBLIGATION) TransactionType.PAYMENT else TransactionType.RECEIPT
        dao.insertTransaction(FinanceTransaction(type = type, amount = safe, accountId = account.id, description = if (type == TransactionType.PAYMENT) "سداد لـ ${account.name}" else "استلام من ${account.name}"))
    }

    fun undo(transaction: FinanceTransaction) = viewModelScope.launch { dao.undoTransaction(transaction) }

    fun updateAccount(account: Account, name: String, amount: Long, note: String, dueDate: Long?) = viewModelScope.launch {
        if (name.isBlank() || amount <= 0 || account.settledAmount > amount) return@launch
        dao.updateAccount(account.copy(name = name.trim(), originalAmount = amount, note = note.trim(), dueDate = dueDate))
    }

    fun deleteAccount(account: Account) = viewModelScope.launch {
        db.withTransaction {
            dao.deleteTransactionsForAccount(account.id)
            dao.deleteAccount(account)
        }
    }

    fun suggestedPayment(account: Account, reserve: Int): Long =
        FinanceCalculator.suggestedPayment(balance.value, account, reserve)

    fun suggestedPercent(account: Account, reserve: Int): Int =
        FinanceCalculator.suggestedPercent(balance.value, account, reserve)

    suspend fun exportEncryptedBackup(pin: String): String = SecurityUtils.encryptBackup(exportBackup(), pin)

    suspend fun exportBackup(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())
        root.put("accounts", JSONArray().apply {
            dao.allAccounts().forEach { a -> put(JSONObject().apply {
                put("id", a.id); put("name", a.name); put("type", a.type.name); put("originalAmount", a.originalAmount)
                put("settledAmount", a.settledAmount); put("dueDate", a.dueDate ?: JSONObject.NULL); put("note", a.note); put("createdAt", a.createdAt)
            }) }
        })
        root.put("transactions", JSONArray().apply {
            dao.allTransactions().forEach { t -> put(JSONObject().apply {
                put("id", t.id); put("type", t.type.name); put("amount", t.amount); put("accountId", t.accountId ?: JSONObject.NULL); put("description", t.description); put("createdAt", t.createdAt)
            }) }
        })
        return root.toString(2)
    }

    suspend fun importBackup(json: String, backupPin: String? = null) {
        val plain = if (json.startsWith("PANDAFINANCE1:")) SecurityUtils.decryptBackup(json, backupPin ?: error("PIN required")) else json
        val root = JSONObject(plain)
        val accountArray = root.getJSONArray("accounts")
        val txArray = root.getJSONArray("transactions")
        val accounts = buildList {
            for (i in 0 until accountArray.length()) {
                val o = accountArray.getJSONObject(i)
                add(Account(o.getLong("id"), o.getString("name"), AccountType.valueOf(o.getString("type")), o.getLong("originalAmount"), o.getLong("settledAmount"), if (o.isNull("dueDate")) null else o.getLong("dueDate"), o.optString("note"), o.getLong("createdAt")))
            }
        }
        val txs = buildList {
            for (i in 0 until txArray.length()) {
                val o = txArray.getJSONObject(i)
                add(FinanceTransaction(o.getLong("id"), TransactionType.valueOf(o.getString("type")), o.getLong("amount"), if (o.isNull("accountId")) null else o.getLong("accountId"), o.getString("description"), o.getLong("createdAt")))
            }
        }
        db.withTransaction {
            dao.clearTransactions(); dao.clearAccounts(); dao.insertAccounts(accounts); dao.insertTransactions(txs)
        }
    }

    override fun onCleared() { db.close(); super.onCleared() }
}
