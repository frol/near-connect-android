package com.aspect.nearconnect.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.aspect.nearconnect.NEARWalletManager

class MainActivity : ComponentActivity() {
    private lateinit var walletManager: NEARWalletManager

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* permissions granted or denied — Ledger will report a clear error if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletManager = NEARWalletManager(applicationContext)

        // Request BLE permissions up-front so Ledger scanning works immediately
        if (!NEARWalletManager.hasBLEPermissions(this)) {
            blePermissionLauncher.launch(
                NEARWalletManager.requiredBLEPermissions().toTypedArray(),
            )
        }

        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(walletManager = walletManager)
                }
            }
        }
    }
}
