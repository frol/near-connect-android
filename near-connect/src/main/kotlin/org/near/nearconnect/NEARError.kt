package org.near.nearconnect

/**
 * Errors thrown by [NEARWalletManager] operations.
 */
sealed class NEARError(override val message: String) : Exception(message) {
    class OperationInProgress : NEARError("Another wallet operation is in progress")
    class NotSignedIn : NEARError("Not signed in. Please connect a wallet first.")
    class InvalidURL : NEARError("Failed to build wallet URL")
    class InvalidTransaction : NEARError("Failed to encode transaction")
    class NoTransactionHash : NEARError("Wallet did not return a transaction hash")
    class WalletError(msg: String) : NEARError(msg)
    class WebViewNotReady : NEARError("Wallet bridge is not ready yet")
    class RPCError(msg: String) : NEARError("RPC error: $msg")
}
