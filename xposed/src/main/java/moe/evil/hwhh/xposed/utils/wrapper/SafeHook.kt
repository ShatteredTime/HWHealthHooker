@file:OptIn(HostInternalApi::class)

package moe.evil.hwhh.xposed.utils.wrapper

import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import moe.evil.hwhh.shared.log.HLog
import java.lang.reflect.Member

private val hookLog = HLog("Hook")

private inline fun <T> HookParam.guarded(stage: String, block: () -> T): T? =
    runCatching(block).onFailure { e ->
        hookLog.error(e) {
            "An error occurred in $stage hook: " +
                    "<${member.declaringClass.simpleName}#${member.name}>"
        }
    }.getOrNull()

class SafeHookCreator(private val delegate: YukiMemberHookCreator.MemberHookCreator) {
    fun before(initiate: HookParam.() -> Unit) {
        delegate.before { guarded("before") { initiate() } }
    }

    fun after(initiate: HookParam.() -> Unit) {
        delegate.after { guarded("after") { initiate() } }
    }

    fun replaceAny(initiate: HookParam.() -> Any?) {
        delegate.replaceAny { guarded("replaceAny") { initiate() } }
    }

    fun replaceUnit(initiate: HookParam.() -> Unit) {
        delegate.replaceUnit { guarded("replaceUnit") { initiate() } }
    }
}

context(p: PackageParam)
private fun Member.safeHook(initiate: SafeHookCreator.() -> Unit) {
    with(p) {
        this@safeHook.hook { SafeHookCreator(this).initiate() }
    }
}

context(_: PackageParam)
fun HostMethod<*>.safeHook(initiate: SafeHookCreator.() -> Unit) = member.safeHook(initiate)

context(_: PackageParam)
fun HostConstructor<*>.safeHook(initiate: SafeHookCreator.() -> Unit) = member.safeHook(initiate)
