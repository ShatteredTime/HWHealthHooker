package moe.evil.hwhh.xposed

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import moe.evil.hwhh.xposed.exporter.RecordExporter
import moe.evil.hwhh.xposed.hooks.HomeHooker
import moe.evil.hwhh.xposed.hooks.MessageCenterHooker
import moe.evil.hwhh.xposed.hooks.PersonalCenterHooker
import moe.evil.hwhh.xposed.hooks.SportDataExportHooker
import moe.evil.hwhh.xposed.hooks.SportHistoryExportHooker
import moe.evil.hwhh.xposed.utils.DexKitWrapper
import moe.evil.hwhh.xposed.utils.HLog
import moe.evil.hwhh.xposed.utils.HostClassLoaderBridge

@InjectYukiHookWithXposed(
    modulePackageName = "moe.evil.hwhh",
    entryClassName = "peanbao"
)
class HookEntry : IYukiHookXposedInit {
    private val dexkit = DexKitWrapper()

    override fun onInit() = configs {
        debugLog {
            tag = "hwhh"
        }
    }

    override fun onHook() = encase {
        HLog.globalMinLevel = runCatching {
            HLog.Level.valueOf(prefs(PREFS_NAME).get(DebugPrefs.LOG_LEVEL))
        }.getOrDefault(HLog.Level.DEBUG)

        RecordExporter.verboseExport = runCatching {
            prefs(PREFS_NAME).get(DebugPrefs.VERBOSE_EXPORT)
        }.getOrDefault(false)

        dexkit.loadApp(HOOK_TARGET_PACKAGE, true) { ds ->
            HostClassLoaderBridge.install(appClassLoader)
            ds.loadHooker(
                HomeHooker,
                MessageCenterHooker,
                PersonalCenterHooker,
                SportDataExportHooker,
                SportHistoryExportHooker,
            )
        }
    }
}
