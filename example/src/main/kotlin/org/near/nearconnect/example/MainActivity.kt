package org.near.nearconnect.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.near.nearconnect.NEARWalletManager

class MainActivity : ComponentActivity() {
    private lateinit var walletManager: NEARWalletManager
    private var pendingBLEPermissionCallback: ((Boolean) -> Unit)? = null

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        pendingBLEPermissionCallback?.invoke(allGranted)
        pendingBLEPermissionCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletManager = NEARWalletManager(applicationContext)

        // Request BLE permissions on-demand when the Ledger plugin needs them
        walletManager.blePermissionRequester = { permissions, onResult ->
            pendingBLEPermissionCallback = onResult
            blePermissionLauncher.launch(permissions.toTypedArray())
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
