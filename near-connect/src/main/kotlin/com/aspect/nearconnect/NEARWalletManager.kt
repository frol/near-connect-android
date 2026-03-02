package com.aspect.nearconnect

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages NEAR wallet connection state.
 *
 * Owns a persistent [WebView] running the near-connect JavaScript bridge.
 * The WebView lives for the lifetime of the manager so that wallet sessions
 * and JS state survive across sheet presentations.
 */
class NEARWalletManager(private val context: Context) {

    // MARK: - Published State

    private val _currentAccount = MutableStateFlow<NEARAccount?>(null)
    val currentAccount: StateFlow<NEARAccount?> = _currentAccount

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _isBridgeReady = MutableStateFlow(false)
    val isBridgeReady: StateFlow<Boolean> = _isBridgeReady

    private val _showWalletUI = MutableStateFlow(false)
    val showWalletUI: StateFlow<Boolean> = _showWalletUI

    private val _network = MutableStateFlow(Network.MAINNET)
    val network: StateFlow<Network> = _network

    val isSignedIn: Boolean get() = _currentAccount.value != null

    /**
     * Optional interceptor for WebView resource requests.
     * Used by tests to serve custom executor scripts from test assets.
     */
    var requestInterceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null

    /**
     * When true, the wallet selector should be auto-triggered on sheet appear.
     */
    var pendingConnect = false

    /**
     * Parameters for a pending connect-and-sign-message flow.
     */
    var pendingSignMessageParams: SignMessageParams? = null

    data class SignMessageParams(val message: String, val recipient: String, val nonce: ByteArray)

    enum class Network(val value: String) {
        MAINNET("mainnet"),
        TESTNET("testnet"),
    }

    // MARK: - WebView (persistent)

    @SuppressLint("SetJavaScriptEnabled")
    val bridgeWebView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Use a standard Chrome Mobile user agent so wallet websites don't
        // detect us as a WebView and block rendering.
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // Allow mixed content – some wallet executors load sub-resources over HTTP
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false
        }

        // Enable third-party cookies (required by wallet executors that set cookies
        // inside sandboxed iframes, e.g. for session tracking or Cloudflare challenges)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(JSBridge(), "NearConnectBridge")
        webViewClient = BridgeWebViewClient()
        webChromeClient = BridgeWebChromeClient()

        setBackgroundColor(android.graphics.Color.TRANSPARENT)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Request high renderer priority so the OS is less likely to kill the sandbox process
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
    }

    // MARK: - Popup WebViews

    internal val popupWebViews = mutableListOf<WebView>()

    /**
     * The currently active popup WebView (wallet auth page).
     * Observed by [WalletBridgeSheet] to render the popup within the Compose hierarchy.
     */
    val activePopup: MutableStateFlow<WebView?> = MutableStateFlow(null)

    // MARK: - Continuations

    private var signInContinuation: CancellableContinuation<NEARAccount>? = null
    private var signInAndSignMessageContinuation: CancellableContinuation<SignInWithMessageResult>? = null
    private var transactionContinuation: CancellableContinuation<TransactionResult>? = null
    private var messageContinuation: CancellableContinuation<MessageSignResult>? = null
    private var delegateActionContinuation: CancellableContinuation<DelegateActionResult>? = null

    // MARK: - Ledger BLE

    val ledgerBLE = LedgerBLEManager(context)

    // MARK: - Persistence

    private val prefs: SharedPreferences =
        context.getSharedPreferences("near_connect", Context.MODE_PRIVATE)
    private val accountStorageKey = "near_connected_account"

    // MARK: - Init

    init {
        loadStoredAccount()
        loadBridgePage()
    }

    private fun loadBridgePage() {
        val htmlContent = context.assets.open("near-connect-bridge.html").bufferedReader().readText()
        val modified = htmlContent.replace(
            "window.location.search",
            "'?network=${_network.value.value}'",
        )
        bridgeWebView.loadDataWithBaseURL(
            "https://near-connect-bridge.local/",
            modified,
            "text/html",
            "UTF-8",
            null,
        )
    }

    /** Remove all popup WebViews. */
    fun closePopups() {
        for (popup in popupWebViews) {
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
        }
        popupWebViews.clear()
        activePopup.value = null
    }

    /**
     * Clean up all wallet UI state when the sheet is dismissed.
     */
    fun cleanUpOnDismiss() {
        callJS("document.querySelectorAll('.hot-connector-popup').forEach(function(el) { el.remove(); })")
        closePopups()
        pendingConnect = false
        pendingSignMessageParams = null
        signInContinuation?.resumeWithException(NEARError.WalletError("Cancelled"))
        signInContinuation = null
        signInAndSignMessageContinuation?.resumeWithException(NEARError.WalletError("Cancelled"))
        signInAndSignMessageContinuation = null
        transactionContinuation?.resumeWithException(NEARError.WalletError("Cancelled"))
        transactionContinuation = null
        messageContinuation?.resumeWithException(NEARError.WalletError("Cancelled"))
        messageContinuation = null
        delegateActionContinuation?.resumeWithException(NEARError.WalletError("Cancelled"))
        delegateActionContinuation = null
        _isBusy.value = false
        _showWalletUI.value = false
    }

    // MARK: - Handle events from WebView

    internal fun handleEvent(event: NEARConnectEvent) {
        when (event) {
            is NEARConnectEvent.Ready -> {
                _isBridgeReady.value = true
                _lastError.value = null
            }

            is NEARConnectEvent.SignedIn -> {
                val account = NEARAccount(
                    accountId = event.accountId,
                    publicKey = event.publicKey,
                    walletId = event.walletId,
                )
                _currentAccount.value = account
                saveAccount(account)
                signInContinuation?.resume(account)
                signInContinuation = null
                if (signInAndSignMessageContinuation != null) {
                    val result = SignInWithMessageResult(account = account, signedMessage = null)
                    signInAndSignMessageContinuation?.resume(result)
                    signInAndSignMessageContinuation = null
                }
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.SignedInAndSignedMessage -> {
                val account = NEARAccount(
                    accountId = event.accountId,
                    publicKey = event.publicKey,
                    walletId = event.walletId,
                )
                _currentAccount.value = account
                saveAccount(account)
                if (signInAndSignMessageContinuation != null) {
                    val result = SignInWithMessageResult(
                        account = account,
                        signedMessage = event.signedMessage,
                    )
                    signInAndSignMessageContinuation?.resume(result)
                    signInAndSignMessageContinuation = null
                } else {
                    signInContinuation?.resume(account)
                    signInContinuation = null
                }
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.SignedOut -> {
                _currentAccount.value = null
                clearStoredAccount()
                _isBusy.value = false
            }

            is NEARConnectEvent.TransactionResult -> {
                val result = TransactionResult(
                    transactionHashes = listOf(event.hash),
                    rawResult = event.rawResult,
                )
                transactionContinuation?.resume(result)
                transactionContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.TransactionsResult -> {
                val result = TransactionResult(
                    transactionHashes = emptyList(),
                    rawResult = event.rawResults,
                )
                transactionContinuation?.resume(result)
                transactionContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.TransactionError -> {
                transactionContinuation?.resumeWithException(NEARError.WalletError(event.message))
                transactionContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.MessageResult -> {
                val result = MessageSignResult(
                    accountId = event.accountId,
                    publicKey = event.publicKey,
                    signature = event.signature,
                )
                messageContinuation?.resume(result)
                messageContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.MessageError -> {
                messageContinuation?.resumeWithException(NEARError.WalletError(event.message))
                messageContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.DelegateActionResult -> {
                val result = DelegateActionResult(signedDelegateActions = event.signedDelegateActions)
                delegateActionContinuation?.resume(result)
                delegateActionContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.DelegateActionError -> {
                delegateActionContinuation?.resumeWithException(NEARError.WalletError(event.message))
                delegateActionContinuation = null
                _isBusy.value = false
                closePopups()
                _showWalletUI.value = false
            }

            is NEARConnectEvent.Error -> {
                _lastError.value = event.message
                signInContinuation?.resumeWithException(NEARError.WalletError(event.message))
                signInContinuation = null
                signInAndSignMessageContinuation?.resumeWithException(NEARError.WalletError(event.message))
                signInAndSignMessageContinuation = null
                transactionContinuation?.resumeWithException(NEARError.WalletError(event.message))
                transactionContinuation = null
                messageContinuation?.resumeWithException(NEARError.WalletError(event.message))
                messageContinuation = null
                delegateActionContinuation?.resumeWithException(NEARError.WalletError(event.message))
                delegateActionContinuation = null
                _isBusy.value = false
            }
        }
    }

    // MARK: - Connect Wallet

    /** Present the wallet selector. */
    fun connect() {
        pendingConnect = true
        _showWalletUI.value = true
    }

    /** Trigger the near-connect wallet selector UI. */
    fun triggerWalletSelector() {
        callJS("window.nearConnect()")
    }

    /** Connect with a specific wallet by ID. */
    fun connect(walletId: String) {
        _showWalletUI.value = true
        val escaped = walletId.replace("'", "\\'")
        callJS("window.nearConnectWallet('$escaped')")
    }

    // MARK: - Connect & Sign Message

    /** Result of connecting a wallet with a signed message. */
    data class SignInWithMessageResult(
        val account: NEARAccount,
        val signedMessage: String?,
    )

    /**
     * Connect a wallet and request a message signature in a single step.
     */
    suspend fun connectAndSignMessage(
        message: String,
        recipient: String,
        nonce: ByteArray? = null,
    ): SignInWithMessageResult {
        if (_isBusy.value) throw NEARError.OperationInProgress()
        if (!_isBridgeReady.value) throw NEARError.WebViewNotReady()

        val messageNonce = nonce ?: generateNonce()
        pendingSignMessageParams = SignMessageParams(message, recipient, messageNonce)
        _showWalletUI.value = true
        _isBusy.value = true
        _lastError.value = null

        return suspendCancellableCoroutine { cont ->
            signInAndSignMessageContinuation = cont
        }
    }

    /** Trigger the JS connect flow with sign message parameters. */
    fun triggerConnectWithSignMessage(message: String, recipient: String, nonce: ByteArray) {
        val nonceBase64 = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
        val escapedMsg = message.replace("'", "\\'").replace("\n", "\\n")
        val escapedRecipient = recipient.replace("'", "\\'")
        callJS("window.nearConnectWithSignMessage('$escapedMsg', '$escapedRecipient', '$nonceBase64')")
    }

    // MARK: - Disconnect

    /** Disconnect the current wallet. */
    fun disconnect() {
        callJS("window.nearDisconnect()")
        _currentAccount.value = null
        clearStoredAccount()
        _lastError.value = null
    }

    // MARK: - Sign & Send Transaction

    /** Result of a signed and sent transaction. */
    data class TransactionResult(
        val transactionHashes: List<String>,
        val rawResult: String?,
    )

    /** Sign and send a transaction with custom actions. */
    suspend fun signAndSendTransaction(
        receiverId: String,
        actions: List<Map<String, Any>>,
    ): TransactionResult {
        if (!isSignedIn) throw NEARError.NotSignedIn()
        if (_isBusy.value) throw NEARError.OperationInProgress()
        if (!_isBridgeReady.value) throw NEARError.WebViewNotReady()

        _isBusy.value = true
        _lastError.value = null
        _showWalletUI.value = true

        val actionsJSON = JSONArray(actions.map { mapToJSONObject(it) }).toString()

        return suspendCancellableCoroutine { cont ->
            transactionContinuation = cont
            val escapedReceiver = receiverId.replace("'", "\\'")
            val escapedActions = actionsJSON.replace("'", "\\'")
            callJS("window.nearSignAndSendTransaction('$escapedReceiver', '$escapedActions')")
        }
    }

    /** Send a NEAR transfer. */
    suspend fun sendNEAR(to: String, amountYocto: String): TransactionResult {
        val actions = listOf(
            mapOf(
                "type" to "Transfer",
                "params" to mapOf("deposit" to amountYocto),
            )
        )
        return signAndSendTransaction(receiverId = to, actions = actions)
    }

    /** Call a smart contract function. */
    suspend fun callFunction(
        contractId: String,
        methodName: String,
        args: Map<String, Any> = emptyMap(),
        gas: String = "30000000000000",
        deposit: String = "0",
    ): TransactionResult {
        val argsJSON = JSONObject(args).toString()
        val argsBase64 = android.util.Base64.encodeToString(
            argsJSON.toByteArray(),
            android.util.Base64.NO_WRAP,
        )

        val actions = listOf(
            mapOf(
                "type" to "FunctionCall",
                "params" to mapOf(
                    "methodName" to methodName,
                    "args" to argsBase64,
                    "gas" to gas,
                    "deposit" to deposit,
                ),
            )
        )
        return signAndSendTransaction(receiverId = contractId, actions = actions)
    }

    // MARK: - Sign Message (NEP-413)

    /** Result of a signed message. */
    data class MessageSignResult(
        val accountId: String?,
        val publicKey: String?,
        val signature: String?,
    )

    /** Sign an off-chain message (NEP-413). */
    suspend fun signMessage(
        message: String,
        recipient: String,
        nonce: ByteArray? = null,
    ): MessageSignResult {
        if (!isSignedIn) throw NEARError.NotSignedIn()
        if (_isBusy.value) throw NEARError.OperationInProgress()
        if (!_isBridgeReady.value) throw NEARError.WebViewNotReady()

        _isBusy.value = true
        _lastError.value = null
        _showWalletUI.value = true

        val messageNonce = nonce ?: generateNonce()
        val nonceBase64 = android.util.Base64.encodeToString(messageNonce, android.util.Base64.NO_WRAP)

        return suspendCancellableCoroutine { cont ->
            messageContinuation = cont
            val escapedMsg = message.replace("'", "\\'").replace("\n", "\\n")
            val escapedRecipient = recipient.replace("'", "\\'")
            callJS("window.nearSignMessage('$escapedMsg', '$escapedRecipient', '$nonceBase64')")
        }
    }

    // MARK: - Sign Delegate Actions (NEP-366 Meta Transactions)

    /** Result of signed delegate actions. */
    data class DelegateActionResult(
        val signedDelegateActions: List<String>,
    )

    /**
     * Sign delegate actions for meta transactions (NEP-366).
     */
    suspend fun signDelegateActions(
        delegateActions: List<Map<String, Any>>,
    ): DelegateActionResult {
        if (!isSignedIn) throw NEARError.NotSignedIn()
        if (_isBusy.value) throw NEARError.OperationInProgress()
        if (!_isBridgeReady.value) throw NEARError.WebViewNotReady()

        _isBusy.value = true
        _lastError.value = null
        _showWalletUI.value = true

        val json = JSONArray(delegateActions.map { mapToJSONObject(it) }).toString()

        return suspendCancellableCoroutine { cont ->
            delegateActionContinuation = cont
            val escaped = json.replace("'", "\\'")
            callJS("window.nearSignDelegateActions('$escaped')")
        }
    }

    // MARK: - NEAR RPC

    /** Query account info via NEAR RPC. */
    suspend fun viewAccount(accountId: String? = null): Map<String, Any?> {
        val id = accountId ?: _currentAccount.value?.accountId
            ?: throw NEARError.NotSignedIn()

        val rpcURL = if (_network.value == Network.MAINNET) {
            "https://rpc.mainnet.near.org"
        } else {
            "https://rpc.testnet.near.org"
        }

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "1")
            put("method", "query")
            put("params", JSONObject().apply {
                put("request_type", "view_account")
                put("finality", "final")
                put("account_id", id)
            })
        }

        return withContext(Dispatchers.IO) {
            val connection = URL(rpcURL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.write(body.toString().toByteArray())

                val responseText = BufferedReader(InputStreamReader(connection.inputStream))
                    .readText()
                val json = JSONObject(responseText)

                if (json.has("result")) {
                    jsonObjectToMap(json.getJSONObject("result"))
                } else if (json.has("error")) {
                    val errorMsg = json.getJSONObject("error").optString("message", "RPC error")
                    throw NEARError.RPCError(errorMsg)
                } else {
                    throw NEARError.RPCError("Invalid RPC response")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    // MARK: - Private

    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(32)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    private fun saveAccount(account: NEARAccount) {
        prefs.edit().putString(accountStorageKey, account.toJson()).apply()
    }

    private fun loadStoredAccount() {
        val json = prefs.getString(accountStorageKey, null) ?: return
        _currentAccount.value = NEARAccount.fromJson(json)
    }

    private fun clearStoredAccount() {
        prefs.edit().remove(accountStorageKey).apply()
    }

    internal fun callJS(js: String) {
        bridgeWebView.evaluateJavascript(js, null)
    }

    // MARK: - JavaScript Interface

    @Suppress("unused")
    inner class JSBridge {
        @JavascriptInterface
        fun onEvent(jsonString: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val obj = JSONObject(jsonString)
                    val type = obj.optString("type", "")
                    val event = parseEvent(type, obj)
                    if (event != null) {
                        handleEvent(event)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling event: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onLedgerBLE(jsonString: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val obj = JSONObject(jsonString)
                    val action = obj.optString("action", "")
                    val requestId = obj.optString("id", "")
                    handleLedgerBLEAction(action, obj, requestId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling ledger BLE: ${e.message}")
                }
            }
        }

        private fun parseEvent(type: String, body: JSONObject): NEARConnectEvent? {
            return when (type) {
                "ready" -> NEARConnectEvent.Ready

                "signIn" -> NEARConnectEvent.SignedIn(
                    accountId = body.optString("accountId", ""),
                    publicKey = body.optString("publicKey").takeIf { it.isNotEmpty() && it != "null" },
                    walletId = body.optString("walletId", "unknown"),
                )

                "signInAndSignMessage" -> {
                    val signedMessage: String? = when {
                        body.optString("signedMessage").let { it.isNotEmpty() && it != "null" && !it.startsWith("{") } ->
                            body.optString("signedMessage")
                        body.optJSONObject("signedMessage") != null ->
                            body.optJSONObject("signedMessage")?.toString(2)
                        else -> null
                    }
                    val smObj = body.optJSONObject("signedMessage")
                    val publicKey = body.optString("publicKey").takeIf { it.isNotEmpty() && it != "null" }
                        ?: smObj?.optString("publicKey")?.takeIf { it.isNotEmpty() && it != "null" }
                    NEARConnectEvent.SignedInAndSignedMessage(
                        accountId = body.optString("accountId", ""),
                        publicKey = publicKey,
                        walletId = body.optString("walletId", "unknown"),
                        signedMessage = signedMessage,
                    )
                }

                "signOut" -> NEARConnectEvent.SignedOut

                "transactionResult" -> NEARConnectEvent.TransactionResult(
                    hash = body.optString("transactionHash", "unknown"),
                    rawResult = body.optString("result").takeIf { it.isNotEmpty() && it != "null" },
                )

                "transactionsResult" -> NEARConnectEvent.TransactionsResult(
                    rawResults = body.optString("results").takeIf { it.isNotEmpty() && it != "null" },
                )

                "transactionError" -> NEARConnectEvent.TransactionError(
                    body.optString("message", "Unknown error"),
                )

                "messageResult" -> {
                    val msgSignature: String? = when {
                        body.optString("signedMessage").let { it.isNotEmpty() && it != "null" && !it.startsWith("{") } ->
                            body.optString("signedMessage")
                        body.optJSONObject("signedMessage") != null ->
                            body.optJSONObject("signedMessage")?.toString(2)
                        else -> null
                    }
                    NEARConnectEvent.MessageResult(
                        accountId = body.optString("accountId").takeIf { it.isNotEmpty() && it != "null" },
                        publicKey = body.optString("publicKey").takeIf { it.isNotEmpty() && it != "null" },
                        signature = msgSignature,
                    )
                }

                "messageError" -> NEARConnectEvent.MessageError(
                    body.optString("message", "Unknown error"),
                )

                "delegateActionResult" -> {
                    val actionsArray = body.optJSONArray("signedDelegateActions")
                    val actions = mutableListOf<String>()
                    if (actionsArray != null) {
                        for (i in 0 until actionsArray.length()) {
                            actions.add(actionsArray.getString(i))
                        }
                    }
                    NEARConnectEvent.DelegateActionResult(signedDelegateActions = actions)
                }

                "delegateActionError" -> NEARConnectEvent.DelegateActionError(
                    body.optString("message", "Unknown error"),
                )

                "error" -> NEARConnectEvent.Error(
                    body.optString("message", "Unknown error"),
                )

                else -> null
            }
        }
    }

    // MARK: - CORS Proxy

    /**
     * Fetch an HTTPS URL from Kotlin and return the response with
     * Access-Control-Allow-Origin headers, bypassing WebView CORS restrictions.
     *
     * Called from [BridgeWebViewClient.shouldInterceptRequest] on the WebView's
     * internal IO thread (blocking is expected).
     */
    private fun proxyCrossOriginGet(
        url: String,
        requestHeaders: Map<String, String>?,
    ): WebResourceResponse? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true

            // Forward Accept and other headers, but NOT Origin / Referer
            // (those are what cause CORS failures on the server side).
            requestHeaders?.forEach { (key, value) ->
                val lower = key.lowercase()
                if (lower != "origin" && lower != "referer") {
                    conn.setRequestProperty(key, value)
                }
            }

            val responseCode = conn.responseCode
            val inputStream =
                if (responseCode in 200..299) conn.inputStream else conn.errorStream

            // Parse Content-Type: "text/javascript; charset=utf-8" → mimeType + encoding
            val rawContentType = conn.contentType ?: "application/octet-stream"
            val parts = rawContentType.split(";").map { it.trim() }
            val mimeType = parts.firstOrNull() ?: "application/octet-stream"
            val encoding = parts.find { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter("=")?.trim() ?: "UTF-8"

            val headers = mutableMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
            )
            // Preserve caching headers from the real response
            for (headerName in listOf("Cache-Control", "ETag", "Last-Modified", "Expires")) {
                conn.getHeaderField(headerName)?.let { headers[headerName] = it }
            }

            WebResourceResponse(
                mimeType,
                encoding,
                responseCode,
                conn.responseMessage ?: "OK",
                headers,
                inputStream,
            )
        } catch (e: Exception) {
            Log.w(TAG, "CORS proxy failed for $url: ${e.message}")
            null // Fall back to WebView's default handling
        }
    }

    // MARK: - WebView Clients

    private inner class BridgeWebViewClient : WebViewClient() {
        /**
         * Intercept resource requests from the bridge WebView.
         *
         * 1. Serve ledger-executor.js from local assets.
         * 2. Proxy cross-origin HTTPS requests to bypass CORS restrictions.
         *    The bridge page has a synthetic origin (near-connect-bridge.local)
         *    which most servers won't accept, so we fetch from Kotlin and
         *    inject Access-Control-Allow-Origin headers.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            requestInterceptor?.invoke(request)?.let { return it }
            val url = request.url.toString()
            if (url.contains("ledger-executor.js")) {
                return try {
                    val inputStream = context.assets.open("ledger-executor.js")
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                    )
                    WebResourceResponse(
                        "application/javascript",
                        "UTF-8",
                        200,
                        "OK",
                        headers,
                        inputStream,
                    )
                } catch (_: Exception) {
                    null
                }
            }

            // Proxy cross-origin HTTPS GET requests that originate from the
            // bridge page itself.  The bridge has a synthetic origin
            // (near-connect-bridge.local from loadDataWithBaseURL) which real
            // servers won't accept in CORS.
            //
            // We must NOT proxy requests that originate from wallet pages loaded
            // inside near-connect sandboxed iframes — those need the WebView's
            // native cookie jar, redirect handling, and network stack.
            //
            // Distinguish by Referer: bridge-page requests have an empty Referer,
            // "https://near-connect-bridge.local/…", or "about:srcdoc" (the
            // initial executor iframe before it navigates).  Wallet-page requests
            // have the wallet's real HTTPS URL as Referer.
            val uri = request.url
            val host = uri.host?.lowercase() ?: ""
            val referer = request.requestHeaders?.get("Referer") ?: ""
            val isFromBridge = referer.isEmpty() ||
                referer.startsWith("https://near-connect-bridge.local") ||
                referer.startsWith("about:")

            if (isFromBridge &&
                uri.scheme?.lowercase() == "https" &&
                host != "near-connect-bridge.local" &&
                request.method?.uppercase() == "GET"
            ) {
                return proxyCrossOriginGet(url, request.requestHeaders)
            }

            return null
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail?,
        ): Boolean {
            Log.w(TAG, "WebView render process gone, didCrash=${detail?.didCrash()}")
            // Reset bridge ready state and reload to recover
            _isBridgeReady.value = false
            Handler(Looper.getMainLooper()).postDelayed({ loadBridgePage() }, 2000)
            return true // Prevent app crash
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class BridgeWebChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val level = when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                else -> "log"
            }
            Log.d(TAG, "[JS $level] ${consoleMessage.message()}")
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // Peek at the URL from the hit test result
            val hitResult = view.hitTestResult
            val url = hitResult.extra
            if (url != null && shouldOpenExternally(Uri.parse(url))) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return false
            }

            val popup = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = false
                }

                // Enable cookies (required by Cloudflare-protected wallets)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = PopupWebViewClient()
                webChromeClient = PopupWebChromeClient()

                setBackgroundColor(android.graphics.Color.WHITE)
            }

            popupWebViews.add(popup)
            // Expose to Compose so WalletBridgeSheet renders it at the right z-level.
            activePopup.value = popup

            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            dismissPopup(window)
        }
    }

    private inner class PopupWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            if (shouldOpenExternally(url)) {
                context.startActivity(Intent(Intent.ACTION_VIEW, url).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                dismissPopup(view)
                return true
            }
            return false
        }
    }

    private inner class PopupWebChromeClient : WebChromeClient() {
        override fun onCloseWindow(window: WebView) {
            dismissPopup(window)
        }
    }

    private fun dismissPopup(popup: WebView) {
        (popup.parent as? ViewGroup)?.removeView(popup)
        popupWebViews.remove(popup)
        if (activePopup.value === popup) {
            activePopup.value = null
        }
        popup.destroy()
    }

    private fun shouldOpenExternally(url: Uri): Boolean {
        val scheme = url.scheme?.lowercase() ?: ""

        // about:blank / about:srcdoc are used internally
        if (scheme == "about") return false

        // Non-HTTP schemes open externally (deep links)
        if (scheme != "http" && scheme != "https") return true

        // Known app-link domains
        val externalDomains = listOf("t.me", "telegram.me")
        val host = url.host?.lowercase() ?: ""
        if (externalDomains.any { host == it || host.endsWith(".$it") }) return true

        return false
    }

    // MARK: - Ledger BLE Bridge

    @SuppressLint("MissingPermission")
    private fun handleLedgerBLEAction(action: String, body: JSONObject, requestId: String) {
        Log.d(TAG, "handleLedgerBLEAction: $action id=$requestId")

        // Guard: BLE operations need runtime permissions on Android 6+
        if (action in listOf("scan", "connect", "exchange") && !hasBLEPermissions(context)) {
            val missing = requiredBLEPermissions().filter { perm ->
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, perm,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            Log.e(TAG, "Missing BLE permissions: $missing")
            respondToJS(requestId, error = "Bluetooth permissions not granted. Required: ${missing.joinToString()}")
            return
        }

        when (action) {
            "scan" -> {
                ledgerBLE.startScanning()
                respondToJS(requestId, result = "true")
            }

            "stopScan" -> {
                ledgerBLE.stopScanning()
                respondToJS(requestId, result = "true")
            }

            "getDevices" -> {
                val devices = ledgerBLE.discoveredDevices.value.map { device ->
                    JSONObject().apply {
                        put("id", device.id)
                        put("name", device.name)
                    }
                }
                respondToJS(requestId, result = JSONArray(devices).toString())
            }

            "connect" -> {
                val deviceName = body.optString("deviceName", "")
                MainScope().launch {
                    try {
                        val devices = ledgerBLE.discoveredDevices.value
                        val device = if (deviceName.isEmpty()) {
                            devices.firstOrNull()
                        } else {
                            devices.firstOrNull { it.name == deviceName }
                        }
                        if (device == null) {
                            respondToJS(requestId, error = "No Ledger device found")
                            return@launch
                        }
                        ledgerBLE.connect(device)
                        ledgerBLE.negotiateMTU()
                        respondToJS(requestId, result = "true")
                    } catch (e: Exception) {
                        respondToJS(requestId, error = e.message ?: "Connection failed")
                    }
                }
            }

            "disconnect" -> {
                ledgerBLE.disconnect()
                respondToJS(requestId, result = "true")
            }

            "exchange" -> {
                val commandArray = body.optJSONArray("command")
                if (commandArray == null) {
                    respondToJS(requestId, error = "Missing command data")
                    return
                }
                val apduData = ByteArray(commandArray.length()) { i ->
                    commandArray.getInt(i).coerceIn(0, 255).toByte()
                }
                MainScope().launch {
                    try {
                        val response = ledgerBLE.exchange(apduData)
                        val responseArray = JSONArray(response.map { it.toInt() and 0xFF })
                        respondToJS(requestId, result = responseArray.toString())
                    } catch (e: Exception) {
                        respondToJS(requestId, error = e.message ?: "Exchange failed")
                    }
                }
            }

            "isConnected" -> {
                val connected = ledgerBLE.connectedDevice.value != null
                respondToJS(requestId, result = if (connected) "true" else "false")
            }

            "isBluetoothReady" -> {
                respondToJS(
                    requestId,
                    result = if (ledgerBLE.isBluetoothReady.value) "true" else "false",
                )
            }

            else -> {
                respondToJS(requestId, error = "Unknown ledgerBLE action: $action")
            }
        }
    }

    private fun respondToJS(requestId: String, result: String? = null, error: String? = null) {
        val escapedId = requestId.replace("'", "\\'")
        val js = if (error != null) {
            val escapedError = error.replace("'", "\\'").replace("\n", "\\n")
            "window._ledgerBLECallback && window._ledgerBLECallback('$escapedId', null, '$escapedError')"
        } else {
            val safeResult = result ?: "null"
            "window._ledgerBLECallback && window._ledgerBLECallback('$escapedId', $safeResult, null)"
        }
        Log.d(TAG, "respondToJS id=$requestId error=$error resultLen=${result?.length ?: 0}")
        callJS(js)
    }

    // MARK: - JSON Helpers

    private fun mapToJSONObject(map: Map<String, Any>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    obj.put(key, mapToJSONObject(value as Map<String, Any>))
                }
                is List<*> -> {
                    val array = JSONArray()
                    for (item in value) {
                        when (item) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                array.put(mapToJSONObject(item as Map<String, Any>))
                            }
                            else -> array.put(item)
                        }
                    }
                    obj.put(key, array)
                }
                else -> obj.put(key, value)
            }
        }
        return obj
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when (val value = obj.get(key)) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> {
                    (0 until value.length()).map { value.get(it) }
                }
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    companion object {
        private const val TAG = "NEARConnect"

        /**
         * Returns the runtime permissions required for Ledger BLE operations
         * on the current API level.
         *
         * On Android 12+ (API 31): BLUETOOTH_SCAN, BLUETOOTH_CONNECT
         * On Android 6–11 (API 23–30): ACCESS_FINE_LOCATION
         */
        fun requiredBLEPermissions(): List<String> {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                listOf(
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        /**
         * Check whether all required BLE permissions have been granted.
         */
        fun hasBLEPermissions(context: Context): Boolean {
            return requiredBLEPermissions().all { perm ->
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    perm,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

        // Expose utility methods as companion for convenience
        fun formatNEAR(yoctoNEAR: String): String {
            return try {
                val value = BigDecimal(yoctoNEAR)
                val divisor = BigDecimal.TEN.pow(24)
                val near = value.divide(divisor, 5, RoundingMode.DOWN)
                near.stripTrailingZeros().toPlainString()
            } catch (_: Exception) {
                "0"
            }
        }

        fun toYoctoNEAR(near: String): String? {
            return try {
                val value = BigDecimal(near)
                val multiplier = BigDecimal.TEN.pow(24)
                val yocto = value.multiply(multiplier)
                yocto.toBigInteger().toString()
            } catch (_: Exception) {
                null
            }
        }
    }
}

