package com.aspect.nearconnect.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.aspect.nearconnect.NEARWalletManager

class MainActivity : ComponentActivity() {
    private lateinit var walletManager: NEARWalletManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletManager = NEARWalletManager(applicationContext)
        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(walletManager = walletManager)
                }
            }
        }
    }
}
