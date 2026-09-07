package moe.evil.hwhh.xposed.hooks.metadata

import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge

internal abstract class MetaDataBaseHooker<A : MetadataSourceApi>(
    private val isMajor: Boolean = true,
) : DexKitHooker<A>() {
    private val log = HLog(javaClass.simpleName)

    @Volatile
    private var availabilityError: Throwable? =
        IllegalStateException("Metadata source has not reported its availability")

    protected val availability: MetadataSourceApi = object : MetadataSourceApi {
        override val isMajor get() = this@MetaDataBaseHooker.isMajor
        override val isAvailable get() = this@MetaDataBaseHooker.availabilityError == null
        override val availabilityError get() = this@MetaDataBaseHooker.availabilityError
    }

    final override fun dispatchHook(bridge: HostBridge) {
        availabilityError = runCatching { onHookWithDexKit(bridge) }
            .onFailure { log.warn(it) { "Unavailable" } }
            .exceptionOrNull()
    }
}
