# Keep JavaScript interface methods
-keepclassmembers class com.aspect.nearconnect.NEARWalletManager$JSBridge {
    @android.webkit.JavascriptInterface <methods>;
}
