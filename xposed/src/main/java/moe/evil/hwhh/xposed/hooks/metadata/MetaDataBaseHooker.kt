package moe.evil.hwhh.xposed.hooks.metadata

import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge

internal abstract class MetaDataBaseHooker<A : MetadataSourceApi>(
    protected val isMajor: Boolean = true,
) : DexKitHooker<A>() {
    private val log = HLog(javaClass.simpleName)

    @Volatile
    protected var isAvailable = false
        private set

    @Volatile
    protected var availabilityError: Throwable? =
        IllegalStateException("Metadata source has not reported its availability")
        private set

    private fun markAvailable() {
        availabilityError = null
        isAvailable = true
    }

    private fun markUnavailable(error: Throwable) {
        isAvailable = false
        availabilityError = error
        log.warn(error) { "Unavailable" }
    }

    private fun <R> guard(block: () -> R) =
        runCatching(block)
            .onSuccess { markAvailable() }
            .onFailure { markUnavailable(it) }
            .getOrNull()

    final override fun dispatchHook(bridge: HostBridge) {
        guard { onHookWithDexKit(bridge) }
    }
}
