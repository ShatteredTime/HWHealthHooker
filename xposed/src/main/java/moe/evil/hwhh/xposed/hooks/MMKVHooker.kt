package moe.evil.hwhh.xposed.hooks

import com.tencent.mmkv.MMKV
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.HostMethod
import moe.evil.hwhh.xposed.utils.wrapper.Modifiers
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.invoke
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod

internal interface MMKVApi : HookApi {
    fun <R> withStore(mmapID: String, block: MMKV.() -> R): Result<R>
}

internal object MMKVHooker : DexKitHooker<MMKVApi>() {
    private const val MULTI_PROCESS_MODE = 2

    private var storeFactory: HostMethod<MMKV>? = null

    override val providedApi = object : MMKVApi {
        override fun <R> withStore(mmapID: String, block: MMKV.() -> R) = runCatching {
            val factory = checkNotNull(storeFactory) { "MMKV store factory unresolved" }
            checkNotNull(factory.invoke(null, mmapID, MULTI_PROCESS_MODE, null)) {
                "MMKV store '$mmapID' unavailable"
            }.block()
        }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val mmkv = classOf<MMKV>()
        storeFactory = mmkv.requireMethod(mmkv) {
            name = "mmkvWithID"
            modifiers(Modifiers.STATIC)
            parameters(classOf<String>(), classOf<Int>(), classOf<String>())
        }
    }
}
