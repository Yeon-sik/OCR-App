package com.pricetrace.receiptocr.pricetrace

import android.content.Context

/** Persists only a user-selected ledger id; auth tokens remain in CashOsSupabaseStore. */
interface CashOsLedgerSelectionStore {
    fun selectedLedgerEntryId(documentId: String): String?
    fun saveSelectedLedgerEntry(documentId: String, ledgerEntryId: String)
    fun clear(documentId: String)
}

internal class AndroidCashOsLedgerSelectionStore(context: Context) : CashOsLedgerSelectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "cashos_ledger_selection",
        Context.MODE_PRIVATE,
    )

    override fun selectedLedgerEntryId(documentId: String): String? =
        preferences.getString(documentId, null)

    override fun saveSelectedLedgerEntry(documentId: String, ledgerEntryId: String) {
        require(documentId.isNotBlank() && ledgerEntryId.isNotBlank())
        check(preferences.edit().putString(documentId, ledgerEntryId).commit()) {
            "CashOS ledger selection could not be saved."
        }
    }

    override fun clear(documentId: String) {
        preferences.edit().remove(documentId).apply()
    }
}
