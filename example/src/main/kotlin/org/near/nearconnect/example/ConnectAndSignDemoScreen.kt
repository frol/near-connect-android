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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.near.nearconnect.NEARWalletManager
import kotlinx.coroutines.launch

@Composable
fun ConnectAndSignDemoScreen(
    walletManager: NEARWalletManager,
    onLog: (String, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentAccount by walletManager.currentAccount.collectAsState()
    val isSignedIn = currentAccount != null
    var message by remember { mutableStateOf("Sign in to NEAR Connect Demo") }
    var recipient by remember { mutableStateOf("near-connect-demo.near") }
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
                    "Connect & Sign",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss, enabled = !isProcessing) {
                    Text("Done")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Connect a wallet and sign a message in a single step. The wallet presents one approval screen for both sign-in and message signing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("Recipient") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (isSignedIn) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Connected as: ${currentAccount?.accountId ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { walletManager.disconnect() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                ) {
                    Text("Disconnect first to test connect flow")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val params = "message: $message, recipient: $recipient"
                    scope.launch {
                        isProcessing = true
                        try {
                            val signInResult = walletManager.connectAndSignMessage(
                                message = message,
                                recipient = recipient,
                            )
                            var output = "account: ${signInResult.account.accountId}, wallet: ${signInResult.account.walletId}"
                            signInResult.account.publicKey?.let { output += ", publicKey: $it" }
                            signInResult.signedMessage?.let { output += "\nsignedMessage: $it" }
                            onLog("connectAndSignMessage", params, output, false)
                        } catch (e: Exception) {
                            onLog("connectAndSignMessage", params, e.message ?: "Unknown error", true)
                            errorMessage = e.message
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && message.isNotEmpty() && recipient.isNotEmpty() && !isSignedIn,
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isProcessing) "Connecting..." else "Connect & Sign Message")
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
                "A random nonce is generated automatically to prevent replay attacks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
