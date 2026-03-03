package org.near.nearconnect

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NearConnectE2ETest {

    private lateinit var scenario: ActivityScenario<TestActivity>
    private lateinit var walletManager: NEARWalletManager

    private val testExecutorUrl = "https://test-wallet-executor.local/executor.js"

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(TestActivity::class.java)
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            walletManager = activity.walletManager

            // Set up request interceptor to serve test-wallet-executor.js from test assets
            walletManager.requestInterceptor = { request: WebResourceRequest ->
                val url = request.url.toString()
                if (url.contains("test-wallet-executor.local")) {
                    try {
                        val js = activity.assets.open("test-wallet-executor.js")
                            .bufferedReader().readText()
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
                            ByteArrayInputStream(js.toByteArray()),
                        )
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            latch.countDown()
        }
        assertTrue("Activity setup timed out", latch.await(30, TimeUnit.SECONDS))

        // Wait for bridge to become ready (generous timeout for slow emulators)
        runBlocking {
            withTimeout(120_000) {
                walletManager.isBridgeReady.first { it }
            }
        }

        // Register the test wallet via JavaScript
        val registerLatch = CountDownLatch(1)
        scenario.onActivity {
            walletManager.callJS(
                """
                (async function() {
                    try {
                        await window._nearConnector.whenManifestLoaded;
                        await window._nearConnector.registerWallet({
                            id: "test-wallet",
                            name: "Test Wallet",
                            icon: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40'%3E%3Crect width='40' height='40' rx='8' fill='%23333'/%3E%3C/svg%3E",
                            description: "Automated test wallet",
                            website: "https://test.local",
                            version: "1.0.0",
                            executor: "$testExecutorUrl",
                            type: "sandbox",
                            platform: [],
                            features: {
                                signMessage: true,
                                signTransaction: true,
                                signInWithoutAddKey: false,
                                signInAndSignMessage: true,
                                signAndSendTransaction: true,
                                signAndSendTransactions: true,
                                signDelegateActions: true,
                                mainnet: true,
                                testnet: true,
                            },
                            permissions: {
                                storage: true,
                                bluetooth: false,
                                allowsOpen: [],
                            },
                        });
                        console.log("[E2ETest] Test wallet registered");
                    } catch (e) {
                        console.error("[E2ETest] Failed to register test wallet:", e);
                    }
                })();
                """.trimIndent()
            )
            registerLatch.countDown()
        }
        assertTrue("Register JS timed out", registerLatch.await(10, TimeUnit.SECONDS))

        // Give the wallet executor time to load and initialize (CDN fetch + key derivation)
        // Extra time needed on slow emulators without KVM acceleration
        Thread.sleep(15_000)
    }

    @After
    fun tearDown() {
        // Disconnect if connected
        if (walletManager.isSignedIn) {
            val latch = CountDownLatch(1)
            scenario.onActivity {
                walletManager.disconnect()
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
        }
        scenario.close()
    }

    // ================================================================
    // Helper: connect with the test wallet
    // ================================================================

    private fun connectTestWallet() {
        val latch = CountDownLatch(1)
        scenario.onActivity {
            walletManager.connect("test-wallet")
            latch.countDown()
        }
        assertTrue("Connect call timed out", latch.await(5, TimeUnit.SECONDS))

        // Wait for sign-in to complete
        runBlocking {
            withTimeout(120_000) {
                walletManager.currentAccount.first { it != null }
            }
        }
    }

    // ================================================================
    // Helper: run a suspend block on the main thread with timeout
    // ================================================================

    private fun <T> runOnMain(timeoutMs: Long = 120_000, block: suspend () -> T): T {
        return runBlocking {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.Main) {
                    block()
                }
            }
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun testBridgeInitialization() {
        assertTrue("Bridge should be ready", walletManager.isBridgeReady.value)
    }

    @Test
    fun testWalletConnection() {
        connectTestWallet()
        val account = walletManager.currentAccount.value
        assertNotNull("Account should not be null after connection", account)
        assertEquals("a.frol.near", account!!.accountId)
    }

    @Test
    fun testViewAccount() {
        connectTestWallet()
        val result = runOnMain {
            walletManager.viewAccount()
        }
        assertTrue("Result should contain 'amount'", result.containsKey("amount"))
        assertTrue("Result should contain 'block_height'", result.containsKey("block_height"))
    }

    @Test
    fun testSignMessage() {
        connectTestWallet()
        val result = runOnMain {
            walletManager.signMessage(
                message = "Hello NEAR E2E test",
                recipient = "e2e-test.near",
            )
        }
        assertNotNull("accountId should not be null", result.accountId)
        assertNotNull("publicKey should not be null", result.publicKey)
        assertNotNull("signature should not be null", result.signature)
        assertEquals("a.frol.near", result.accountId)
    }

    @Test
    fun testSignDelegateActions() {
        connectTestWallet()
        val delegateActions = listOf(
            mapOf(
                "receiverId" to "guest-book.near",
                "actions" to listOf(
                    mapOf(
                        "type" to "FunctionCall",
                        "params" to mapOf(
                            "methodName" to "add_message",
                            "args" to mapOf("text" to "E2E test delegate"),
                            "gas" to "30000000000000",
                            "deposit" to "0",
                        ),
                    )
                ),
            )
        )
        val result = runOnMain {
            walletManager.signDelegateActions(delegateActions)
        }
        assertTrue(
            "Should have non-empty signedDelegateActions",
            result.signedDelegateActions.isNotEmpty(),
        )
        assertTrue(
            "Signed delegate action should be non-empty base64",
            result.signedDelegateActions[0].isNotEmpty(),
        )
    }

    @Test
    fun testCallFunction() {
        connectTestWallet()
        val result = runOnMain {
            walletManager.callFunction(
                contractId = "guest-book.near",
                methodName = "add_message",
                args = mapOf("text" to "NEAR Connect Android E2E test"),
                gas = "30000000000000",
                deposit = "0",
            )
        }
        assertTrue(
            "Should have at least one transaction hash",
            result.transactionHashes.isNotEmpty(),
        )
        assertTrue(
            "Transaction hash should be non-empty",
            result.transactionHashes[0].isNotEmpty(),
        )
    }

    @Test
    fun testDisconnect() {
        connectTestWallet()
        assertNotNull("Should be connected", walletManager.currentAccount.value)

        val latch = CountDownLatch(1)
        scenario.onActivity {
            walletManager.disconnect()
            latch.countDown()
        }
        assertTrue("Disconnect timed out", latch.await(5, TimeUnit.SECONDS))

        assertNull("Account should be null after disconnect", walletManager.currentAccount.value)
    }

    @Test
    fun testFullLifecycle() {
        // 1. Connect
        connectTestWallet()
        val account = walletManager.currentAccount.value
        assertNotNull("Should be connected", account)
        assertEquals("a.frol.near", account!!.accountId)

        // 2. View account
        val accountInfo = runOnMain {
            walletManager.viewAccount()
        }
        assertTrue("Should have amount", accountInfo.containsKey("amount"))

        // 3. Sign message
        val messageResult = runOnMain {
            walletManager.signMessage(
                message = "Lifecycle test message",
                recipient = "lifecycle-test.near",
            )
        }
        assertNotNull("Should have signature", messageResult.signature)

        // 4. Sign delegate actions
        val delegateActions = listOf(
            mapOf(
                "receiverId" to "guest-book.near",
                "actions" to listOf(
                    mapOf(
                        "type" to "FunctionCall",
                        "params" to mapOf(
                            "methodName" to "add_message",
                            "args" to mapOf("text" to "lifecycle test"),
                            "gas" to "30000000000000",
                            "deposit" to "0",
                        ),
                    )
                ),
            )
        )
        val delegateResult = runOnMain {
            walletManager.signDelegateActions(delegateActions)
        }
        assertTrue(
            "Should have signed delegate actions",
            delegateResult.signedDelegateActions.isNotEmpty(),
        )

        // 5. Disconnect
        val latch = CountDownLatch(1)
        scenario.onActivity {
            walletManager.disconnect()
            latch.countDown()
        }
        assertTrue("Disconnect timed out", latch.await(5, TimeUnit.SECONDS))
        assertNull("Should be disconnected", walletManager.currentAccount.value)
    }
}
