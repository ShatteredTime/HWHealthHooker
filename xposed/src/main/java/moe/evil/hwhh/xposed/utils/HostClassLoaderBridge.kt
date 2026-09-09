package moe.evil.hwhh.xposed.utils

import android.annotation.SuppressLint
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HostClassLoaderBridge {
    private val hostPrefixes = arrayOf(
        "com.huawei.",
        "com.google.gson.",
        "com.tencent.mmkv.",
        "net.zetetic.",
    )

    private val installed = AtomicBoolean(false)

    @Volatile
    private var parentField: Field? = null

    private val log = HLog.of<HostClassLoaderBridge>()

    fun install(host: ClassLoader?) {
        if (host == null) {
            log.warn { "Not injected: host ClassLoader is null, stub types stay unbridged" }
            return
        }
        if (!installed.compareAndSet(false, true)) return
        val moduleLoader = classOf<HostClassLoaderBridge>().classLoader ?: run {
            installed.set(false)
            log.warn { "Not injected: module ClassLoader is null, stub types stay unbridged" }
            return
        }
        runCatching {
            val oldParent = moduleLoader.parent
            val bridge = BridgeClassLoader(host, oldParent, hostPrefixes)
            setParent(moduleLoader, bridge)
            log.info { "Injected, prefixes=${hostPrefixes.contentToString()}" }
        }.onFailure { e ->
            installed.set(false)
            log.warn(e) { "Inject failed, stub types stay unbridged" }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun setParent(loader: ClassLoader, parent: ClassLoader?) {
        val f = parentField ?: classOf<ClassLoader>().getDeclaredField("parent")
            .apply { isAccessible = true }
            .also { parentField = it }
        f.set(loader, parent)
    }

    private class BridgeClassLoader(
        private val host: ClassLoader,
        fallback: ClassLoader?,
        private val prefixes: Array<String>,
    ) : ClassLoader(fallback) {
        private val hostMiss = ConcurrentHashMap.newKeySet<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (prefixes.any(name::startsWith) && name !in hostMiss) {
                try {
                    return host.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    hostMiss.add(name)
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
