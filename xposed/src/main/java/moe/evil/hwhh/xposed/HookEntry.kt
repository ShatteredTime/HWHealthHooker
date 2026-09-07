package moe.evil.hwhh.xposed

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import moe.evil.hwhh.shared.DebugPrefs
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.LOG_TAG
import moe.evil.hwhh.shared.log.LogLevel
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.sportdata.exporter.RecordExporter
import moe.evil.hwhh.xposed.utils.DexKitWrapper
import moe.evil.hwhh.xposed.utils.HostClassLoaderBridge
import moe.evil.hwhh.xposed.utils.YLogSink

@InjectYukiHookWithXposed(
    modulePackageName = "moe.evil.hwhh",
    entryClassName = "peanbao"
)
class HookEntry : IYukiHookXposedInit {
    private val dexkit = DexKitWrapper()
    private val log = HLog.of<HookEntry>()

    override fun onInit() {
        HLog.sink = YLogSink
        configs {
            debugLog {
                tag = LOG_TAG
            }
        }
    }

    override fun onHook() = encase {
        runCatching {
            val config = prefs(PREFS_NAME)
            HLog.globalMinLevel = LogLevel.of(config.get(DebugPrefs.LOG_LEVEL))
            RecordExporter.verboseExport = config.get(DebugPrefs.VERBOSE_EXPORT)
        }.onFailure { log.warn { "Config unreadable, using defaults: ${it.describe()}" } }

        dexkit.loadApp(HOOK_TARGET_PACKAGE, true) { ds ->
            HostClassLoaderBridge.install(appClassLoader)
            ds.loadHooker()
        }
    }
}
