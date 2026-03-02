# NEAR Connect Android

Android library for integrating NEAR Protocol wallets into your app. Provides wallet connection, transaction signing, message signing (NEP-413), meta transactions (NEP-366), and Ledger hardware wallet support via Bluetooth LE.

Built with Jetpack Compose and Kotlin coroutines. Uses a persistent WebView bridge to the [@hot-labs/near-connect](https://www.npmjs.com/package/@hot-labs/near-connect) JavaScript library.

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 2.0+
- Jetpack Compose

## Installation

Add the `:near-connect` module to your project, then depend on it from your app module:

```kotlin
// settings.gradle.kts
include(":near-connect")

// app/build.gradle.kts
dependencies {
    implementation(project(":near-connect"))
}
```

## Quick Start

### 1. Create the wallet manager

Create a single `NEARWalletManager` instance in your Activity. It owns a persistent WebView that must live for the app's lifetime:

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var walletManager: NEARWalletManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletManager = NEARWalletManager(applicationContext)
        setContent {
            MaterialTheme {
                MyApp(walletManager)
            }
        }
    }
}
```

### 2. Show the wallet UI

The library provides a `WalletBridgeSheet` composable that displays the wallet selector and transaction approval UI. Show it when `showWalletUI` is true:

```kotlin
@Composable
fun MyApp(walletManager: NEARWalletManager) {
    val showWalletUI by walletManager.showWalletUI.collectAsState()

    if (showWalletUI) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            WalletBridgeSheet(
                walletManager = walletManager,
                onDismiss = { walletManager.cleanUpOnDismiss() },
            )
        }
    }

    // Your app content...
}
```

### 3. Connect a wallet

```kotlin
// Show the wallet selector (user picks a wallet)
walletManager.connect()

// Or connect with a specific wallet ID
walletManager.connect("hot-wallet")
```

### 4. Observe connection state

```kotlin
@Composable
fun AccountStatus(walletManager: NEARWalletManager) {
    val account by walletManager.currentAccount.collectAsState()

    if (account != null) {
        Text("Connected: ${account!!.accountId}")
    } else {
        Text("Not connected")
    }
}
```

## API Reference

### NEARWalletManager

The central class that manages wallet connections and operations.

#### Constructor

```kotlin
val walletManager = NEARWalletManager(context: Context)
```

#### Reactive State (StateFlow)

| Property | Type | Description |
|---|---|---|
| `currentAccount` | `StateFlow<NEARAccount?>` | Currently connected account, or null |
| `isBridgeReady` | `StateFlow<Boolean>` | Whether the JS bridge has loaded |
| `isBusy` | `StateFlow<Boolean>` | Whether an operation is in progress |
| `lastError` | `StateFlow<String?>` | Last error message |
| `showWalletUI` | `StateFlow<Boolean>` | Whether wallet UI should be shown |
| `network` | `StateFlow<Network>` | Current network (MAINNET or TESTNET) |

#### Properties

| Property | Type | Description |
|---|---|---|
| `isSignedIn` | `Boolean` | Convenience check for `currentAccount != null` |
| `bridgeWebView` | `WebView` | The underlying bridge WebView |
| `ledgerBLE` | `LedgerBLEManager` | Ledger Bluetooth LE manager |

#### Connection

```kotlin
// Show wallet selector
walletManager.connect()

// Connect with a specific wallet
walletManager.connect(walletId = "hot-wallet")

// Disconnect
walletManager.disconnect()
```

#### Transactions

```kotlin
// Send NEAR tokens
val result: TransactionResult = walletManager.sendNEAR(
    to = "recipient.near",
    amountYocto = "1000000000000000000000000", // 1 NEAR
)
// result.transactionHashes: List<String>

// Call a smart contract function
val result: TransactionResult = walletManager.callFunction(
    contractId = "guest-book.near",
    methodName = "add_message",
    args = mapOf("text" to "Hello from Android!"),
    gas = "30000000000000",      // 30 TGas (default)
    deposit = "0",                // no deposit (default)
)

// Custom transaction with multiple actions
val result: TransactionResult = walletManager.signAndSendTransaction(
    receiverId = "contract.near",
    actions = listOf(
        mapOf(
            "type" to "FunctionCall",
            "params" to mapOf(
                "methodName" to "my_method",
                "args" to mapOf("key" to "value"),
                "gas" to "30000000000000",
                "deposit" to "0",
            ),
        )
    ),
)
```

#### Message Signing (NEP-413)

```kotlin
val result: MessageSignResult = walletManager.signMessage(
    message = "Authenticate to MyApp",
    recipient = "myapp.near",
    nonce = byteArrayOf(/* 32 bytes, optional - auto-generated if omitted */),
)
// result.accountId: String?
// result.publicKey: String?
// result.signature: String?
```

#### Connect and Sign in One Step

```kotlin
val result: SignInWithMessageResult = walletManager.connectAndSignMessage(
    message = "Sign in to MyApp",
    recipient = "myapp.near",
)
// result.accountId: String
// result.publicKey: String
// result.signature: String
```

#### Meta Transactions (NEP-366)

```kotlin
val result: DelegateActionResult = walletManager.signDelegateActions(
    delegateActions = listOf(
        mapOf(
            "receiverId" to "contract.near",
            "actions" to listOf(
                mapOf(
                    "type" to "FunctionCall",
                    "params" to mapOf(
                        "methodName" to "my_method",
                        "args" to mapOf("key" to "value"),
                        "gas" to "30000000000000",
                        "deposit" to "0",
                    ),
                )
            ),
        )
    ),
)
// result.signedDelegateActions: List<String> (base64-encoded)
```

#### RPC Queries

```kotlin
// View current account info
val info: Map<String, Any?> = walletManager.viewAccount()
// info["amount"] -> account balance in yoctoNEAR
// info["block_height"] -> block height

// View a specific account
val info = walletManager.viewAccount(accountId = "other.near")
```

#### Utility Functions

```kotlin
// Format yoctoNEAR to human-readable NEAR
NEARWalletManager.formatNEAR("1000000000000000000000000") // "1.000000"

// Convert NEAR to yoctoNEAR
NEARWalletManager.toYoctoNEAR("1.5") // "1500000000000000000000000"
```

### NEARAccount

```kotlin
data class NEARAccount(
    val accountId: String,      // e.g. "alice.near"
    val publicKey: String?,     // e.g. "ed25519:..."
    val walletId: String,       // e.g. "hot-wallet"
)
```

### NEARError

All errors thrown by wallet operations extend `NEARError`:

| Error | When |
|---|---|
| `NEARError.NotSignedIn` | Operation requires a connected wallet |
| `NEARError.OperationInProgress` | Another operation is already running |
| `NEARError.WebViewNotReady` | Bridge hasn't finished loading |
| `NEARError.WalletError(msg)` | Wallet returned an error |
| `NEARError.RPCError(msg)` | NEAR RPC call failed |

### WalletBridgeSheet

Jetpack Compose component that hosts the wallet UI:

```kotlin
@Composable
fun WalletBridgeSheet(
    walletManager: NEARWalletManager,
    onDismiss: () -> Unit,
)
```

Present this inside a full-screen `Dialog` when `walletManager.showWalletUI` is true.

## Network Configuration

The library defaults to mainnet. All operations use the network specified by `walletManager.network`.

## Permissions

The library declares these permissions in its manifest (merged automatically):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- Bluetooth permissions are for Ledger hardware wallet support -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

If your app doesn't use Ledger hardware wallets, you can remove the Bluetooth permissions in your app manifest:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" tools:node="remove" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" tools:node="remove" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" tools:node="remove" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" tools:node="remove" />
```

## Example App

The `example/` module is a full demo app showing all library features:

- **Wallet connection** with wallet selector UI
- **Account info** display with balance
- **Send NEAR** tokens to any account
- **Call smart contracts** with custom arguments
- **Sign delegate actions** for meta transactions (NEP-366)
- **Connect & sign message** in a single flow

### Running the example

```bash
./gradlew :example:installDebug
```

Or open the project in Android Studio and run the `example` configuration.

## Project Structure

```
near-connect-android/
  near-connect/                    # Library module (AAR)
    src/main/
      assets/
        near-connect-bridge.html   # JavaScript bridge page
        ledger-executor.js         # Ledger wallet executor
      kotlin/com/aspect/nearconnect/
        NEARWalletManager.kt       # Main API
        NEARAccount.kt             # Account data class
        NEARError.kt               # Error types
        NEARConnectEvent.kt        # Internal JS event parsing
        WalletBridgeSheet.kt       # Compose wallet UI
        LedgerBLEManager.kt        # Ledger Bluetooth support
    src/androidTest/               # Instrumented E2E tests
  example/                         # Demo application
    src/main/kotlin/.../example/
      MainActivity.kt              # Entry point
      MainScreen.kt                # Main UI with account display
      TransactionDemoScreen.kt     # Send NEAR demo
      ContractCallDemoScreen.kt    # Contract call demo
      ConnectAndSignDemoScreen.kt  # Connect + sign message demo
      DelegateActionDemoScreen.kt  # Meta transaction demo
```

## Testing

The library includes instrumented E2E tests that exercise the full bridge flow (Kotlin -> WebView -> JavaScript -> executor iframe -> JavaScript -> WebView -> Kotlin):

```bash
./gradlew :near-connect:connectedAndroidTest
```

Tests require an Android emulator or device with internet access. API 30+ with WebView 83+ is recommended.

## License

See [LICENSE](LICENSE) for details.
