package com.avalanche.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.avalanche.app.ui.AvalancheApp
import com.avalanche.app.ui.theme.AvalancheTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AvalancheTheme { AvalancheApp() } }
    }
}
