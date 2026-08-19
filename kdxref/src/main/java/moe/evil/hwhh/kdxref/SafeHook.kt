package moe.evil.hwhh.kdxref

import com.highcapable.kavaref.extension.toClassOrNull
import com.highcapable.kavaref.resolver.base.MemberResolver
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import moe.evil.hwhh.shared.log.HLog

@PublishedApi
internal val log = HLog("HookWrapper")

context(h: YukiBaseHooker)
fun String.toClassOrLog() =
    toClassOrNull(loader = h.appClassLoader)
        .also { if (it == null) log.error { "Class not found: $this" } }

inline fun safeCall(tag: String, block: () -> Unit) {
    runCatching(block).onFailure { e ->
        log.error(e, tag = "SafeCall") { "An error occurred in $tag" }
    }
}

inline fun HookParam.safeCall(tag: String, block: () -> Unit) {
    val hookTag = "${member.declaringClass.simpleName}#${member.name}"
    runCatching(block).onFailure { e ->
        log.error(e, tag = "SafeCall") { "An error occurred in $tag Hook: <$hookTag>" }
    }
}

class SafeHookCreator(private val delegate: YukiMemberHookCreator.MemberHookCreator) {
    fun before(initiate: HookParam.() -> Unit) {
        delegate.before { safeCall("before") { initiate() } }
    }

    fun after(initiate: HookParam.() -> Unit) {
        delegate.after { safeCall("after") { initiate() } }
    }

    fun replaceAny(initiate: HookParam.() -> Any?) {
        delegate.replaceAny {
            val tag = "${member.declaringClass.simpleName}#${member.name}"
            runCatching { initiate() }.onFailure { e ->
                log.error(e, tag = "SafeCall") { "Error in replaceAny Hook: <$tag>" }
            }.getOrNull()
        }
    }

    fun replaceUnit(initiate: HookParam.() -> Unit) {
        delegate.replaceUnit { safeCall("replaceUnit") { initiate() } }
    }
}

context(p: PackageParam)
internal fun MemberResolver<*, *>.safeHook(initiate: SafeHookCreator.() -> Unit) {
    with(p) {
        this@safeHook.hook { SafeHookCreator(this).initiate() }
    }
}
