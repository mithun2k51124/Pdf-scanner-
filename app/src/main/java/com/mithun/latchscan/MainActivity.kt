package com.mithun.latchscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mithun.latchscan.ui.LatchNavHost
import com.mithun.latchscan.ui.theme.LatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LatchTheme {
                LatchNavHost()
            }
        }
    }
}
