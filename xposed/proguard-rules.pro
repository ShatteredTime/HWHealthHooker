# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontwarn android.app.AndroidAppHelper
-dontwarn android.content.res.XModuleResources
-dontwarn android.content.res.XResources
-dontwarn de.robv.android.xposed.IXposedHookLoadPackage
-dontwarn de.robv.android.xposed.IXposedHookZygoteInit$StartupParam
-dontwarn de.robv.android.xposed.IXposedHookZygoteInit
-dontwarn de.robv.android.xposed.XC_MethodHook$Unhook
-dontwarn de.robv.android.xposed.XC_MethodHook
-dontwarn de.robv.android.xposed.XSharedPreferences
-dontwarn de.robv.android.xposed.XposedBridge
-dontwarn de.robv.android.xposed.callbacks.XC_InitPackageResources$InitPackageResourcesParam
-dontwarn de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam
-dontwarn java.lang.reflect.AnnotatedType
-keep class moe.evil.hwhh.xposed.** { *; }