# Keep all xposed hook classes
-keep class moe.evil.hwhh.xposed.** { *; }

# xbinterface stubs — compileOnly types replaced at runtime by the host app's
# real classes via HostClassLoaderBridge; they are intentionally absent from
# our APK, so tell R8 not to complain.
-dontwarn com.huawei.**

# YukiHookAPI - relies heavily on reflection, ships no proguard rules
-keep class com.highcapable.yukihookapi.** { *; }
-dontwarn com.highcapable.yukihookapi.**

# KavaRef - reflection-based member resolver
-keep class com.highcapable.kavaref.** { *; }
-dontwarn com.highcapable.kavaref.**

# DexKit - native JNI + reflection
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# Xposed framework classes (provided at runtime, not in APK)
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
