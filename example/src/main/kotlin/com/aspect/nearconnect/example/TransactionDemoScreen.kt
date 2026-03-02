package com.aspect.nearconnect.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aspect.nearconnect.NEARWalletManager
import kotlinx.coroutines.launch

@Composable
fun TransactionDemoScreen(
    walletManager: NEARWalletManager,
    onLog: (String, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentAccount by walletManager.currentAccount.collectAsState()
    var receiverId by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("0.01") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Send NEAR",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss, enabled = !isProcessing) {
                    Text("Done")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Send NEAR tokens to another account via your connected wallet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("From", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(currentAccount?.accountId ?: "", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = receiverId,
                onValueChange = { receiverId = it },
                label = { Text("Receiver (e.g., bob.near)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                suffix = { Text("NEAR") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val yocto = NEARWalletManager.toYoctoNEAR(amount)
                    if (yocto == null) {
                        errorMessage = "Invalid amount"
                        return@Button
                    }
                    val params = "to: $receiverId, amount: $amount NEAR"
                    scope.launch {
                        isProcessing = true
                        try {
                            val txResult = walletManager.sendNEAR(to = receiverId, amountYocto = yocto)
                            val hashes = txResult.transactionHashes.joinToString(", ")
                            onLog("sendNEAR", params, "Hashes: $hashes", false)
                        } catch (e: Exception) {
                            onLog("sendNEAR", params, e.message ?: "Unknown error", true)
                            errorMessage = e.message
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && receiverId.isNotEmpty() && amount.isNotEmpty(),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isProcessing) "Sending..." else "Send NEAR")
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "The wallet will open to confirm the transaction. The transaction is signed and broadcast by the wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
