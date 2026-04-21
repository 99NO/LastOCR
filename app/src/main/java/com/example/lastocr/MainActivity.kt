package com.example.lastocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.lastocr.ui.OcrExperimentScreen
import com.example.lastocr.ui.theme.LastOCRTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LastOCRTheme {
                OcrExperimentScreen()
            }
        }
    }
}
