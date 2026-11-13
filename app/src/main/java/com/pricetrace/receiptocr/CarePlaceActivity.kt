package com.pricetrace.receiptocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pricetrace.receiptocr.information.CarePlaceViewModel

class CarePlaceActivity : ComponentActivity() {
    private val model: CarePlaceViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.prefill(intent.getStringExtra("care_name"), intent.getStringExtra("care_address"), intent.getStringExtra("care_phone"))
        setContent { ReceiptOcrTheme { CarePlaceScreen(model, onClose = { finish() }) } }
    }
}
