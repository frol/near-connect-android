# Keep JavaScript interface methods
-keepclassmembers class org.near.nearconnect.NEARWalletManager$JSBridge {
    @android.webkit.JavascriptInterface <methods>;
}
