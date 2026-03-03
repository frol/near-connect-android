package org.near.nearconnect.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.near.nearconnect.NEARWalletManager
import org.near.nearconnect.WalletBridgeSheet

data class LogEntry(
    val action: String,
    val params: String,
    val output: String,
    val isError: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

@Composable
fun MainScreen(walletManager: NEARWalletManager) {
    val currentAccount by walletManager.currentAccount.collectAsState()
    val showWalletUI by walletManager.showWalletUI.collectAsState()
    var accountBalance by remember { mutableStateOf<String?>(null) }
    val logEntries = remember { mutableStateListOf<LogEntry>() }

    // Sheet states
    var showTransactionDemo by remember { mutableStateOf(false) }
    var showContractCallDemo by remember { mutableStateOf(false) }
    var showConnectAndSignDemo by remember { mutableStateOf(false) }
    var showDelegateActionDemo by remember { mutableStateOf(false) }

    val appendLog: (String, String, String, Boolean) -> Unit = { action, params, output, isError ->
        logEntries.add(LogEntry(action, params, output, isError))
    }

    // Fetch balance when account changes
    LaunchedEffect(currentAccount) {
        if (currentAccount != null) {
            try {
                val result = walletManager.viewAccount()
                val amountStr = result["amount"] as? String
                if (amountStr != null) {
                    accountBalance = NEARWalletManager.formatNEAR(amountStr)
                }
            } catch (_: Exception) {
                // Silently fail
            }
        } else {
            accountBalance = null
        }
    }

    // Wallet bridge full-screen dialog
    if (showWalletUI) {
        Dialog(
            onDismissRequest = { walletManager.showWalletUI.let {} },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            WalletBridgeSheet(
                walletManager = walletManager,
                onDismiss = { walletManager.cleanUpOnDismiss() },
            )
        }
    }

    // Demo sheets
    if (showTransactionDemo) {
        Dialog(
            onDismissRequest = { showTransactionDemo = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            TransactionDemoScreen(
                walletManager = walletManager,
                onLog = appendLog,
                onDismiss = { showTransactionDemo = false },
            )
        }
    }

    if (showContractCallDemo) {
        Dialog(
            onDismissRequest = { showContractCallDemo = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ContractCallDemoScreen(
                walletManager = walletManager,
                onLog = appendLog,
                onDismiss = { showContractCallDemo = false },
            )
        }
    }

    if (showConnectAndSignDemo) {
        Dialog(
            onDismissRequest = { showConnectAndSignDemo = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ConnectAndSignDemoScreen(
                walletManager = walletManager,
                onLog = appendLog,
                onDismiss = { showConnectAndSignDemo = false },
            )
        }
    }

    if (showDelegateActionDemo) {
        Dialog(
            onDismissRequest = { showDelegateActionDemo = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            DelegateActionDemoScreen(
                walletManager = walletManager,
                onLog = appendLog,
                onDismiss = { showDelegateActionDemo = false },
            )
        }
    }

    // Main content
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Blue.copy(alpha = 0.1f),
                        Color.Magenta.copy(alpha = 0.1f),
                    ),
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Header
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color(0xFF6366F1),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "NEAR Connect",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Android Demo",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(30.dp))

            val account = currentAccount
            if (account != null) {
                // Account card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(10.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(60.dp),
                            tint = Color(0xFF22C55E),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Connected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            account.accountId,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        if (accountBalance != null) {
                            Text(
                                "$accountBalance NEAR",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .background(
                                    Color.Blue.copy(alpha = 0.1f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wallet,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                " ${account.walletId}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                ActionButton(
                    text = "Send NEAR",
                    icon = Icons.Default.SwapHoriz,
                    color = Color(0xFF3B82F6),
                    onClick = { showTransactionDemo = true },
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionButton(
                    text = "Call Contract",
                    icon = Icons.Default.Code,
                    color = Color(0xFF8B5CF6),
                    onClick = { showContractCallDemo = true },
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionButton(
                    text = "Delegate Actions",
                    icon = Icons.Default.Send,
                    color = Color(0xFF14B8A6),
                    onClick = { showDelegateActionDemo = true },
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionButton(
                    text = "Disconnect",
                    icon = Icons.AutoMirrored.Default.ExitToApp,
                    color = Color(0xFFEF4444),
                    onClick = {
                        walletManager.disconnect()
                        accountBalance = null
                    },
                )
            } else {
                // Connect prompt
                Text(
                    "Connect your NEAR wallet to get started",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { walletManager.connect() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                ) {
                    Icon(Icons.Default.Wallet, contentDescription = null)
                    Text("  Connect Wallet", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                ActionButton(
                    text = "Connect & Sign Message",
                    icon = Icons.Default.Draw,
                    color = Color(0xFFF97316),
                    onClick = { showConnectAndSignDemo = true },
                )
            }

            // Event log
            if (logEntries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Event Log", fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { logEntries.clear() },
                                colors = ButtonDefaults.textButtonColors(),
                            ) {
                                Text("Clear", fontSize = 12.sp)
                            }
                        }

                        for (entry in logEntries.reversed()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        entry.action,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (entry.isError) Color.Red else Color(0xFF22C55E),
                                    )
                                    if (entry.params.isNotEmpty()) {
                                        Text(
                                            entry.params,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                        )
                                    }
                                    Text(
                                        entry.output,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (entry.isError) Color.Red.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Footer
            Text(
                "Powered by NEAR Protocol",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Uses near-connect for secure wallet integration",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Icon(icon, contentDescription = null)
        Text("  $text", fontWeight = FontWeight.Bold)
    }
}
