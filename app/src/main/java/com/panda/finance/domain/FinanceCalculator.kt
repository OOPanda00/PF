package com.panda.finance.domain

import com.panda.finance.data.Account
import com.panda.finance.data.AccountType

object FinanceCalculator {
    fun suggestedPayment(balance: Long, account: Account, reservePercent: Int): Long {
        if (account.remaining <= 0) return 0
        if (account.type == AccountType.RECEIVABLE) return account.remaining
        val available = balance.coerceAtLeast(0)
        val reserve = available * reservePercent.coerceIn(0, 100) / 100
        return (available - reserve).coerceAtLeast(0).coerceAtMost(account.remaining)
    }

    fun suggestedPercent(balance: Long, account: Account, reservePercent: Int): Int {
        val payment = suggestedPayment(balance, account, reservePercent)
        return if (account.remaining == 0L) 0 else ((payment * 100) / account.remaining).toInt().coerceIn(0, 100)
    }
}
