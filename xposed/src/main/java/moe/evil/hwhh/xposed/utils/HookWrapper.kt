package moe.evil.hwhh.xposed.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import moe.evil.hwhh.shared.DebugToggle
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.log.HLog

@PublishedApi
internal val log = HLog("Prefs")

inline fun YukiBaseHooker.ifDebugPref(toggle: DebugToggle, block: () -> Unit) {
    val enabled = runCatching {
        prefs(PREFS_NAME).run {
            get(toggle.pref) && toggle.isUnlocked { getBoolean(it.key, true) }
        }
    }
        .onFailure { log.warn { "Debug pref ${toggle.pref.key} unreadable, treating as off" } }
        .getOrDefault(false)
    if (enabled) block()
}

fun collapseView(view: View) {
    view.visibility = View.GONE
    val layoutParams = view.layoutParams ?: return
    layoutParams.height = 0
    if (layoutParams is ViewGroup.MarginLayoutParams) {
        layoutParams.topMargin = 0
        layoutParams.bottomMargin = 0
    }
    view.layoutParams = layoutParams
}

fun Int.asResIdOrNull() = takeIf { it != 0 }

fun Activity.toast(msg: String) {
    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
