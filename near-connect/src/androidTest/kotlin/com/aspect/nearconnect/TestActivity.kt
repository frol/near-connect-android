package com.aspect.nearconnect

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import androidx.activity.ComponentActivity

/**
 * Minimal activity that hosts a [NEARWalletManager] and attaches its
 * [bridgeWebView] to the content view. Required for WebView iframes
 * to function correctly during instrumented tests.
 */
class TestActivity : ComponentActivity() {

    lateinit var walletManager: NEARWalletManager
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletManager = NEARWalletManager(this)

        // Handle WebView renderer crashes gracefully instead of crashing the app.
        // This is critical on slow emulators without KVM where the sandbox process
        // may be killed by the OS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            walletManager.bridgeWebView.webViewRenderProcessClient =
                object : WebViewRenderProcessClient() {
                    override fun onRenderProcessUnresponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?,
                    ) {
                        Log.w("TestActivity", "WebView render process unresponsive")
                    }

                    override fun onRenderProcessResponsive(
                        view: WebView,
                        renderer: WebViewRenderProcess?,
                    ) {
                        Log.i("TestActivity", "WebView render process responsive again")
                    }
                }
        }

        setContentView(
            walletManager.bridgeWebView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
