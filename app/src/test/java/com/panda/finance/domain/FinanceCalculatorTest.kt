package com.panda.finance.domain

import com.panda.finance.data.Account
import com.panda.finance.data.AccountType
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceCalculatorTest {
    @Test fun obligation_respects_reserve_and_debt() {
        val account = Account(name = "Ahmed", type = AccountType.OBLIGATION, originalAmount = 2000)
        assertEquals(1400L, FinanceCalculator.suggestedPayment(2000, account, 30))
        assertEquals(70, FinanceCalculator.suggestedPercent(2000, account, 30))
    }

    @Test fun obligation_never_exceeds_remaining() {
        val account = Account(name = "Ahmed", type = AccountType.OBLIGATION, originalAmount = 1000, settledAmount = 600)
        assertEquals(400L, FinanceCalculator.suggestedPayment(10000, account, 10))
        assertEquals(100, FinanceCalculator.suggestedPercent(10000, account, 10))
    }

    @Test fun receivable_can_suggest_full_remaining_amount() {
        val account = Account(name = "Mohamed", type = AccountType.RECEIVABLE, originalAmount = 2500, settledAmount = 500)
        assertEquals(2000L, FinanceCalculator.suggestedPayment(100, account, 80))
        assertEquals(100, FinanceCalculator.suggestedPercent(100, account, 80))
    }

    @Test fun zero_or_negative_balance_suggests_no_obligation_payment() {
        val account = Account(name = "Ahmed", type = AccountType.OBLIGATION, originalAmount = 1000)
        assertEquals(0L, FinanceCalculator.suggestedPayment(-50, account, 30))
        assertEquals(0, FinanceCalculator.suggestedPercent(-50, account, 30))
    }
}
