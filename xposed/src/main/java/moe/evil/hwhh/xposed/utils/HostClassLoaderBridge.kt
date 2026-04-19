package moe.evil.hwhh.xposed.utils

import android.annotation.SuppressLint
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HostClassLoaderBridge {
    private val hostPrefixes = arrayOf(
        "com.huawei.",
    )

    private val installed = AtomicBoolean(false)

    @Volatile
    private var parentField: Field? = null

    private val log = HLog.of<HostClassLoaderBridge>()

    fun install(host: ClassLoader?) {
        if (host == null) return
        if (!installed.compareAndSet(false, true)) return
        val moduleLoader = HostClassLoaderBridge::class.java.classLoader ?: run {
            installed.set(false)
            return
        }
        runCatching {
            val oldParent = moduleLoader.parent
            val bridge = BridgeClassLoader(host, oldParent, hostPrefixes)
            setParent(moduleLoader, bridge)
            log.info { "Injected, prefixes=${hostPrefixes.contentToString()}" }
        }.onFailure { e ->
            installed.set(false)
            log.warn { "Inject failed: ${e.javaClass.simpleName}: ${e.message}" }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun setParent(loader: ClassLoader, parent: ClassLoader?) {
        val f = parentField ?: ClassLoader::class.java.getDeclaredField("parent")
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
