package org.near.nearconnect.example

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
import org.near.nearconnect.NEARWalletManager
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ContractCallDemoScreen(
    walletManager: NEARWalletManager,
    onLog: (String, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentAccount by walletManager.currentAccount.collectAsState()
    var contractId by remember { mutableStateOf("guest-book.near") }
    var methodName by remember { mutableStateOf("add_message") }
    var argsText by remember { mutableStateOf("{\"text\": \"Hello from Android!\"}") }
    var deposit by remember { mutableStateOf("0") }
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
                    "Call Contract",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss, enabled = !isProcessing) {
                    Text("Done")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Call a smart contract function on NEAR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Signer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(currentAccount?.accountId ?: "", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = contractId,
                onValueChange = { contractId = it },
                label = { Text("Contract ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = methodName,
                onValueChange = { methodName = it },
                label = { Text("Method Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = argsText,
                onValueChange = { argsText = it },
                label = { Text("Arguments (JSON)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = deposit,
                onValueChange = { deposit = it },
                label = { Text("Deposit") },
                suffix = { Text("NEAR") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    // Parse JSON args
                    val args: Map<String, Any> = try {
                        val obj = JSONObject(argsText)
                        val map = mutableMapOf<String, Any>()
                        for (key in obj.keys()) {
                            map[key] = obj.get(key)
                        }
                        map
                    } catch (_: Exception) {
                        errorMessage = "Invalid JSON arguments"
                        return@Button
                    }

                    val depositYocto = NEARWalletManager.toYoctoNEAR(deposit) ?: "0"
                    val params = "contract: $contractId, method: $methodName, args: $argsText, deposit: $deposit NEAR"

                    scope.launch {
                        isProcessing = true
                        try {
                            val txResult = walletManager.callFunction(
                                contractId = contractId,
                                methodName = methodName,
                                args = args,
                                deposit = depositYocto,
                            )
                            val hashes = txResult.transactionHashes.joinToString(", ")
                            onLog("callFunction", params, "Hashes: $hashes", false)
                        } catch (e: Exception) {
                            onLog("callFunction", params, e.message ?: "Unknown error", true)
                            errorMessage = e.message
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && contractId.isNotEmpty() && methodName.isNotEmpty(),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isProcessing) "Calling..." else "Call Contract")
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
