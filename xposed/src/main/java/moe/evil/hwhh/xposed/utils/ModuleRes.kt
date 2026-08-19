package moe.evil.hwhh.xposed.utils

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.highcapable.yukihookapi.hook.factory.injectModuleAppResources
import java.util.Collections
import java.util.WeakHashMap

// Injecting the module APK into the *host's* AssetManager (rather than reading a standalone
// XModuleResources) is what makes localisation work: the host Resources already carries the
// live Configuration — locale, density, night mode, per-app language — so getString picks the
// right values-xx automatically and keeps following the host after a config change.
// Safe only because the module APK is linked with resource package id 0x64 (see
// app/build.gradle.kts), so its ids can never collide with the host's 0x7f.
private val injected = Collections.newSetFromMap(WeakHashMap<Resources, Boolean>())

private fun Context.moduleResources() = resources.also {
    synchronized(injected) {
        if (it !in injected) {
            it.injectModuleAppResources()
            injected += it
        }
    }
}

fun Context.moduleString(@StringRes id: Int) = moduleResources().getString(id)

fun Context.moduleString(@StringRes id: Int, vararg args: Any) =
    moduleResources().getString(id, *args)
