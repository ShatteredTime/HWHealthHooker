package moe.evil.hwhh.xposed

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import moe.evil.hwhh.xposed.hooks.HomeHooker
import moe.evil.hwhh.xposed.hooks.MessageCenterHooker
import moe.evil.hwhh.xposed.hooks.PersonalCenterHooker
import moe.evil.hwhh.xposed.utils.DexKitWrapper

@InjectYukiHookWithXposed(
    modulePackageName = "moe.evil.hwhh",
    entryClassName = "peanbao"
)
class HookEntry : IYukiHookXposedInit {
    private val tmbDexKit = DexKitWrapper()

    override fun onInit() = configs {
        debugLog {
            tag = "hwhh"
        }
    }

    override fun onHook() = encase {
        tmbDexKit.loadApp(HOOK_TARGET_PACKAGE) { tds ->
            if (processName != mainProcessName) return@loadApp
            tds.loadHooker(
                HomeHooker,
                MessageCenterHooker,
                PersonalCenterHooker,
            )
        }
    }
}
