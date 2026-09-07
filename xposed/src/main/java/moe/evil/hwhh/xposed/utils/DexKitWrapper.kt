package moe.evil.hwhh.xposed.utils

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.param.PackageParam
import moe.evil.hwhh.shared.HookFeature
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.HOOKER_EDGES
import moe.evil.hwhh.xposed.hookRootOf
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge

interface HookApi

data object NoApi : HookApi

abstract class DexKitHooker<out A : HookApi> : YukiBaseHooker() {
    private enum class State {
        NEW,
        LOADING,
        READY,
        FAILED,
    }

    private var bridge: HostBridge? = null
    private val depFactories = mutableListOf<() -> DexKitHooker<*>>()
    private val dependencies get() = depFactories.map { it() }
    protected val dependencyApis get() = depFactories.map { it().requireReadyApi() }
    private var hookResult: Result<Unit>? = null

    @Volatile
    private var state = State.NEW

    protected abstract val providedApi: A

    protected abstract fun onHookWithDexKit(bridge: HostBridge)

    protected open fun dispatchHook(bridge: HostBridge) = onHookWithDexKit(bridge)

    final override fun onHook() {
        hookResult = runCatching {
            dispatchHook(checkNotNull(bridge) { "HostBridge is not available (out of scope)" })
        }
    }

    protected fun <D : HookApi> require(dep: () -> DexKitHooker<D>): Lazy<D> {
        val hooker = lazy(dep)
        depFactories += { hooker.value }
        return lazy { hooker.value.requireReadyApi() }
    }

    private fun requireReadyApi(): A {
        check(state == State.READY) {
            "Hooker ${javaClass.simpleName} is not ready (state=$state)"
        }
        return providedApi
    }

    private fun markLoading() {
        check(state == State.NEW) {
            "Hooker ${javaClass.simpleName} cannot start loading from state=$state"
        }
        hookResult = null
        state = State.LOADING
    }

    private fun requireSuccessfulHook() {
        check(state == State.LOADING) {
            "Hooker ${javaClass.simpleName} cannot verify onHook from state=$state"
        }
        val result = checkNotNull(hookResult) {
            "Hooker ${javaClass.simpleName} did not execute onHook"
        }
        result.getOrThrow()
    }

    private fun markReady() {
        check(state == State.LOADING) {
            "Hooker ${javaClass.simpleName} cannot become ready from state=$state"
        }
        state = State.READY
    }

    private fun markFailed() {
        state = State.FAILED
    }

    private inline fun <R> withBridge(bridge: HostBridge, block: () -> R): R {
        this.bridge = bridge
        try {
            return block()
        } finally {
            this.bridge = null
        }
    }

    class DexKitScope internal constructor(
        private val param: PackageParam,
        private val bridge: HostBridge,
    ) {
        private val log = HLog.of<DexKitScope>()
        private val roots = mutableSetOf<DexKitBaseHooker>()
        private val visiting = mutableSetOf<DexKitHooker<*>>()
        private val loaded = mutableSetOf<DexKitHooker<*>>()
        private val failed = mutableSetOf<DexKitHooker<*>>()

        private val DexKitHooker<*>.key get() = javaClass.simpleName

        fun loadHooker() {
            val rootsByFeature = HookFeature.entries.associateWith(::hookRootOf)
            roots += rootsByFeature.values
            rootsByFeature.forEach { (feature, hooker) ->
                val enabled = runCatching { param.prefs(PREFS_NAME).getBoolean(feature.key, true) }
                    .onFailure { e -> log.warn { "Config unreadable for ${feature.key}: ${e.describe()}" } }
                    .getOrDefault(true)
                if (enabled) load(hooker)
                else log.debug { "Hooker ${feature.key} disabled by config" }
            }
        }

        private fun load(hooker: DexKitHooker<*>): Boolean {
            if (hooker in loaded) {
                log.debug { "Hooker ${hooker.key} already loaded, skipping" }
                return true
            }
            if (hooker in failed) {
                log.warn { "Hooker ${hooker.key} already failed, skipping" }
                return false
            }
            if (!visiting.add(hooker)) {
                log.warn { "Dependency cycle detected at ${hooker.key}, skipping to break the loop" }
                return false
            }
            hooker.markLoading()
            try {
                val deps = hooker.dependencies
                val declared = HOOKER_EDGES[hooker.key]
                val actual = deps.mapTo(mutableSetOf()) { it.key }
                if (declared != actual) log.error {
                    "Hooker graph drift at ${hooker.key}: generated=$declared runtime=$actual. " +
                            "The UI gates derived at build time no longer describe this build."
                }
                val failedDependency = deps.firstOrNull {
                    if (it in roots) log.warn {
                        "Hooker ${it.key} is both a base hooker and required by ${hooker.key}, " +
                                "possible misuse? It will be loaded ignoring its config switch!"
                    }
                    !load(it)
                }
                if (failedDependency != null) {
                    hooker.markFailed()
                    failed += hooker
                    log.error {
                        "Not loading ${hooker.key}: dependency ${failedDependency.key} first failed"
                    }
                    return false
                }

                return runCatching {
                    hooker.withBridge(bridge) {
                        param.loadHooker(hooker)
                    }
                    hooker.requireSuccessfulHook()
                }.fold(
                    onSuccess = {
                        hooker.markReady()
                        loaded += hooker
                        log.debug { "Hooker ${hooker.key} ready" }
                        true
                    },
                    onFailure = { e ->
                        hooker.markFailed()
                        failed += hooker
                        log.error(e) {
                            "Failed to load hooker ${hooker.javaClass.name} in ${param.packageName}"
                        }
                        false
                    },
                )
            } finally {
                visiting -= hooker
            }
        }
    }
}

abstract class DexKitBaseHooker : DexKitHooker<NoApi>() {
    final override val providedApi = NoApi
}

class DexKitWrapper {
    companion object {
        private val log = HLog.of<DexKitWrapper>()
    }

    context(param: PackageParam)
    fun loadApp(
        name: String,
        onlyMainProcess: Boolean = false,
        initiate: PackageParam.(DexKitHooker.DexKitScope) -> Unit
    ) {
        param.loadApp(name) {
            if (onlyMainProcess && processName != mainProcessName) return@loadApp
            log.info { "Loaded $name by DexKitWrapper, process=$processName" }
            val loader = checkNotNull(appClassLoader) { "appClassLoader is null for $name" }
            HostBridge.open(loader) { bridge ->
                initiate(DexKitHooker.DexKitScope(this, bridge))
            }
        }
    }
}
