package moe.evil.hwhh.shared

import com.highcapable.yukihookapi.hook.xposed.prefs.data.PrefsData

const val HOOK_TARGET_PACKAGE = "com.huawei.health"

const val PREFS_NAME = "hwhh_config"

object AnalysisPrefs {
    const val NAMES_PREFIX = "analysis_names_"
    fun lastBuildTag() = PrefsData("analysis_last_build_tag", "")
    fun names(buildTag: String) = PrefsData(NAMES_PREFIX + buildTag, "")
}

object DebugPrefs {
    val RED_DOT = PrefsData("debug_red_dot", false)
    val HIDE_LAUNCHER_ICON = PrefsData("debug_hide_launcher_icon", false)
    val LOG_LEVEL = PrefsData("debug_log_level", "INFO")
    val VERBOSE_EXPORT = PrefsData("debug_verbose_export", false)
    val HEALTH_QUERY = PrefsData("debug_health_query", false)
}
