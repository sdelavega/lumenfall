# Lumenfall release (R8) rules.
#
# The Rust core resolves JNI entry points by their exact Java names
# (Java_com_aetherdesk_lumenfall_core_LumenfallCore_*), so neither the class
# nor its external methods may be renamed or stripped.
-keep class com.aetherdesk.lumenfall.core.LumenfallCore { *; }

# The wallpaper bundle bridge object is injected into WebView JavaScript via
# addJavascriptInterface; its @JavascriptInterface methods are called by name
# from bundle scripts.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
