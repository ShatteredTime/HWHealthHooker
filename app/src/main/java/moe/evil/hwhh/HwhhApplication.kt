package moe.evil.hwhh

import android.app.Application
import com.highcapable.yukihookapi.hook.factory.prefs
import moe.evil.hwhh.shared.DebugPrefs
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.LogLevel


class HwhhApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HLog.globalMinLevel = LogLevel.of(prefs(PREFS_NAME).get(DebugPrefs.LOG_LEVEL))
    }
}
