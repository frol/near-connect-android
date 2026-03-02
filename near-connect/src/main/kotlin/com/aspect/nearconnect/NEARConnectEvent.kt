package com.aspect.nearconnect

/**
 * Events received from the JavaScript bridge.
 */
internal sealed class NEARConnectEvent {
    data object Ready : NEARConnectEvent()
    data class SignedIn(val accountId: String, val publicKey: String?, val walletId: String) : NEARConnectEvent()
    data class SignedInAndSignedMessage(
        val accountId: String,
        val publicKey: String?,
        val walletId: String,
        val signedMessage: String?,
    ) : NEARConnectEvent()

    data object SignedOut : NEARConnectEvent()
    data class TransactionResult(val hash: String, val rawResult: String?) : NEARConnectEvent()
    data class TransactionsResult(val rawResults: String?) : NEARConnectEvent()
    data class TransactionError(val message: String) : NEARConnectEvent()
    data class MessageResult(val accountId: String?, val publicKey: String?, val signature: String?) : NEARConnectEvent()
    data class MessageError(val message: String) : NEARConnectEvent()
    data class DelegateActionResult(val signedDelegateActions: List<String>) : NEARConnectEvent()
    data class DelegateActionError(val message: String) : NEARConnectEvent()
    data class Error(val message: String) : NEARConnectEvent()
}
