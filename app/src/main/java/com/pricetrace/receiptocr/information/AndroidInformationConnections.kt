package com.pricetrace.receiptocr.information

import android.content.Context
import com.pricetrace.receiptocr.BuildConfig
import com.pricetrace.receiptocr.fitness.AndroidNutritionSupabaseStore
import com.pricetrace.receiptocr.pricetrace.AndroidCashOsSupabaseStore
import com.pricetrace.receiptocr.pricetrace.AndroidPriceTraceSupabaseStore
import com.pricetrace.receiptscanner.information.InformationConnectionAdapters

internal fun informationConnections(context: Context) = InformationConnectionAdapters(
    AndroidNutritionSupabaseStore(context), AndroidCashOsSupabaseStore(context), AndroidPriceTraceSupabaseStore(context),
    AndroidPriceTraceSupabaseStore(context,
        preferencesName = "healthtrace_information_connection", keyAlias = "receipt_ocr_healthtrace_information_session",
        sessionPreferencesName = "healthtrace_information_session",
        // Shared PersonalOS hosting; Health credentials and domain tables remain separate.
        defaultUrl = BuildConfig.DEFAULT_CASHOS_SUPABASE_URL, defaultPublishableKey = BuildConfig.DEFAULT_CASHOS_SUPABASE_PUBLISHABLE_KEY),
)
