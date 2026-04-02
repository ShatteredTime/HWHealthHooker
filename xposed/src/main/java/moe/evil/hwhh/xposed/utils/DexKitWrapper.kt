package moe.evil.hwhh.xposed.utils

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.PackageParam
import de.robv.android.xposed.XSharedPreferences
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.atomic.AtomicBoolean

abstract class DexKitHooker : YukiBaseHooker() {
    private var bridge: DexKitBridge? = null

    internal inline fun <R> withBridge(bridge: DexKitBridge, block: () -> R): R {
        this.bridge = bridge
        try {
            return block()
        } finally {
            this.bridge = null
        }
    }
}

class TmbDexKitScope internal constructor(
    private val param: PackageParam,
    val bridge: DexKitBridge,
) {
    companion object {
        const val PREFS_NAME = "hwhh_config"
    }

    fun loadHooker(vararg hooker: DexKitHooker) = hooker.forEach { h ->
        val key = h.javaClass.simpleName
        val enabled = param.prefs(PREFS_NAME).getBoolean(key, true)
        if (!enabled) {
            YLog.debug("Hooker $key disabled by config")
            return@forEach
        }
        h.withBridge(bridge) {
            runCatching { param.loadHooker(h) }.onFailure { e ->
                YLog.error("Failed to load hooker ${h.javaClass.name} in ${param.packageName}", e)
            }
        }
    }
}

class DexKitWrapper {
    companion object {
        private val soLoaded = AtomicBoolean(false)

        private inline fun <R> withDexKit(classLoader: ClassLoader, block: (DexKitBridge) -> R): R {
            if (soLoaded.compareAndSet(false, true)) {
                System.loadLibrary("dexkit")
            }
            return DexKitBridge.create(classLoader, true).use(block)
        }
    }

    context(param: PackageParam)
    fun loadApp(
        name: String,
        initiate: PackageParam.(TmbDexKitScope) -> Unit
    ) {
        param.loadApp(name) {
            YLog.info("Loaded $name by DexKitWrapper, process=$processName")
            withDexKit(appClassLoader!!) { bridge ->
                initiate(TmbDexKitScope(this, bridge))
            }
        }
    }
}