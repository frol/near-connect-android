package org.near.nearconnect

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Full-screen sheet that shows the persistent bridge WebView.
 *
 * Present this composable when [NEARWalletManager.showWalletUI] is true.
 * It handles wallet connection, transaction approval, and message signing flows.
 *
 * When hosting inside a Compose `Dialog`, use `decorFitsSystemWindows = false`
 * in `DialogProperties` so the keyboard doesn't cover the WebView:
 * ```
 * Dialog(
 *     properties = DialogProperties(
 *         usePlatformDefaultWidth = false,
 *         decorFitsSystemWindows = false,
 *     ),
 * ) { WalletBridgeSheet(...) }
 * ```
 */
@Composable
fun WalletBridgeSheet(
    walletManager: NEARWalletManager,
    onDismiss: () -> Unit,
) {
    val isBridgeReady by walletManager.isBridgeReady.collectAsState()
    val popup by walletManager.activePopup.collectAsState()
    var didTrigger by remember { mutableStateOf(false) }

    // Trigger connect flow when bridge is ready
    LaunchedEffect(isBridgeReady) {
        if (!isBridgeReady || didTrigger) return@LaunchedEffect
        triggerConnectIfNeeded(walletManager, onTrigger = { didTrigger = true })
    }

    // Also try on first composition
    LaunchedEffect(Unit) {
        if (!isBridgeReady || didTrigger) return@LaunchedEffect
        triggerConnectIfNeeded(walletManager, onTrigger = { didTrigger = true })
    }

    // Clean up on dispose
    DisposableEffect(Unit) {
        onDispose {
            walletManager.cleanUpOnDismiss()
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .systemBarsPadding()
        .imePadding()
    ) {
        // Bridge WebView
        AndroidView(
            factory = { _ ->
                // Detach from previous parent if needed
                (walletManager.bridgeWebView.parent as? ViewGroup)?.removeView(walletManager.bridgeWebView)
                walletManager.bridgeWebView.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Popup WebView (wallet auth page) — rendered on top of the bridge
        val currentPopup = popup
        if (currentPopup != null) {
            AndroidView(
                factory = { _ ->
                    // Detach from previous parent if the popup was attached elsewhere
                    (currentPopup.parent as? ViewGroup)?.removeView(currentPopup)
                    currentPopup.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Loading overlay
        if (!isBridgeReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.padding(8.dp))
                    Text(
                        "Loading wallet connector...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Close button (always on top)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, end = 16.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            IconButton(
                onClick = {
                    walletManager.showWalletUI.let {
                        // Hide the UI
                    }
                    onDismiss()
                },
                modifier = Modifier.background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = CircleShape,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private suspend fun triggerConnectIfNeeded(
    walletManager: NEARWalletManager,
    onTrigger: () -> Unit,
) {
    val params = walletManager.pendingSignMessageParams
    if (params != null) {
        onTrigger()
        walletManager.pendingSignMessageParams = null
        delay(300)
        walletManager.triggerConnectWithSignMessage(
            message = params.message,
            recipient = params.recipient,
            nonce = params.nonce,
        )
    } else if (walletManager.pendingConnect) {
        onTrigger()
        walletManager.pendingConnect = false
        delay(300)
        walletManager.triggerWalletSelector()
    }
}
