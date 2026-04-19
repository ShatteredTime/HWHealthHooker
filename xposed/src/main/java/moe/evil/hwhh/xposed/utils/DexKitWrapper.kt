package moe.evil.hwhh.xposed.utils

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.param.PackageParam
import moe.evil.hwhh.xposed.PREFS_NAME
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

    @PublishedApi
    internal fun requireBridge(): DexKitBridge =
        bridge ?: error("DexKitBridge is not available (out of scope)")
}

class DexKitScope internal constructor(
    private val param: PackageParam,
    val bridge: DexKitBridge,
) {
    private val log = HLog.of<DexKitScope>()

    fun loadHooker(vararg hooker: DexKitHooker) = hooker.forEach { h ->
        val key = h.javaClass.simpleName
        val enabled = param.prefs(PREFS_NAME).getBoolean(key, true)
        if (!enabled) {
            log.debug { "Hooker $key disabled by config" }
            return@forEach
        }
        h.withBridge(bridge) {
            runCatching { param.loadHooker(h) }.onFailure { e ->
                log.error(e) { "Failed to load hooker ${h.javaClass.name} in ${param.packageName}" }
            }
        }
    }
}

class DexKitWrapper {
    companion object {
        private val soLoaded = AtomicBoolean(false)
        private val log = HLog.of<DexKitWrapper>()

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
        onlyMainProcess: Boolean = false,
        initiate: PackageParam.(DexKitScope) -> Unit
    ) {
        param.loadApp(name) {
            if (onlyMainProcess && processName != mainProcessName) return@loadApp
            log.info { "Loaded $name by DexKitWrapper, process=$processName" }
            val loader = checkNotNull(appClassLoader) { "appClassLoader is null for $name" }
            withDexKit(loader) { bridge ->
                initiate(DexKitScope(this, bridge))
            }
        }
    }
}