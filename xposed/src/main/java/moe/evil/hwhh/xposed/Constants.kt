package moe.evil.hwhh.xposed

import com.highcapable.yukihookapi.hook.xposed.prefs.data.PrefsData

const val HOOK_TARGET_PACKAGE = "com.huawei.health"

const val PREFS_NAME = "hwhh_config"

object DebugPrefs {
    val RED_DOT = PrefsData("debug_red_dot", false)
    val HIDE_LAUNCHER_ICON = PrefsData("debug_hide_launcher_icon", false)
}