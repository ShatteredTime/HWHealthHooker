# Keep all xposed hook classes
-keep class moe.evil.hwhh.xposed.** { *; }
-keep class moe.evil.hwhh.shared.** { *; }

# jadx runs as an on-device library: plugins are found via META-INF/services and
# large parts of jadx-core resolve types reflectively, so it must stay intact.
-keep class jadx.** { *; }
-dontwarn jadx.**
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn ch.qos.logback.**
-dontwarn org.slf4j.**
-dontwarn com.android.tools.smali.**
-dontwarn com.google.common.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.jetbrains.annotations.**

# xbinterface stubs — compileOnly types replaced at runtime by the host app's
# real classes via HostClassLoaderBridge; they are intentionally absent from
# our APK, so tell R8 not to complain.
-dontwarn com.huawei.**
-dontwarn net.zetetic.**

# gson is read off host objects and must resolve to the host's copy via
# HostClassLoaderBridge, which redirects by class name. jadx (in :analyzer)
# drags a real gson into the APK, so R8 would otherwise rename it out of the
# bridged "com.google.gson." namespace and we'd silently get our own copy —
# a different Class than the host's, so annotations never match. Member names
# matter too: a renamed Gson.toJson would not exist on the host's Gson.
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# YukiHookAPI - relies heavily on reflection, ships no proguard rules
-keep class com.highcapable.yukihookapi.** { *; }
-dontwarn com.highcapable.yukihookapi.**

# KavaRef - reflects on host classes, never on itself, so it shrinks like any library
-dontwarn com.highcapable.kavaref.**

# DexKit - JNI only reaches the classes declaring native methods (this is the AAR's own
# consumer rule); results cross the boundary as flatbuffers byte arrays, not reflection
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}
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
