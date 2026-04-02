package moe.evil.hwhh.xposed.utils

import android.view.View
import android.view.ViewGroup
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.MethodCondition
import com.highcapable.kavaref.extension.toClassOrNull
import com.highcapable.kavaref.resolver.base.MemberResolver
import com.highcapable.kavaref.resolver.MethodResolver
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import kotlin.reflect.KClass

fun Any?.safeToString(): String = runCatching { this?.toString() }.getOrNull() ?: "null"

fun Throwable.shortMsg(): String =
    (message?.takeIf { it.isNotBlank() } ?: this::class.java.name)

context(h: YukiBaseHooker)
fun String.toClassOrLog(): Class<*>? =
    this.toClassOrNull(loader = h.appClassLoader)
        .also { if (it == null) YLog.warn("class NOT found: $this") }

inline fun tryHook(tag: String, block: () -> Unit) {
    runCatching(block).onFailure { e ->
        YLog.error("[TryHook] <$tag> on failure! e: ${e.shortMsg()}")
    }
}

inline fun DexKitHooker.tryHook(block: () -> Unit) {
    val tag = this::class.simpleName ?: "Unknown"
    runCatching(block).onFailure { e ->
        YLog.error("[TryHook] <$tag> on failure! e: ${e.shortMsg()}")
    }
}

inline fun safeCall(tag: String, block: () -> Unit) {
    runCatching(block).onFailure { e ->
        YLog.error("[SafeCall] An Error occurred in $tag", e = e)
    }
}

inline fun HookParam.safeCall(tag: String, block: () -> Unit) {
    val hookTag = "${member.declaringClass.simpleName}#${member.name}"
    runCatching(block).onFailure { e ->
        YLog.error("[SafeCall] An Error occurred in $tag Hook: <$hookTag>", e = e)
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
                YLog.error("Error in replaceAny Hook: <$tag>", e = e)
            }.getOrNull()
        }
    }

    fun replaceUnit(initiate: HookParam.() -> Unit) {
        delegate.replaceUnit { safeCall("replaceUnit") { initiate() } }
    }
}

context(p: PackageParam)
fun MemberResolver<*, *>.safeHook(initiate: SafeHookCreator.() -> Unit) {
    with(p) {
        this@safeHook.hook { SafeHookCreator(this).initiate() }
    }
}

inline fun <T : Any> Class<T>.firstMethodOrNullLogged(
    condition: MethodCondition<T>.() -> Unit = {}
): MethodResolver<T>? {
    val scope = this.resolve()
    val r = scope.firstMethodOrNull(condition)
    if (r == null) {
        val c = scope.method().apply(condition)
        YLog.warn("[KavaRef] method NOT found in ${this.name}: ${c.toReadableDesc()}")
    }
    return r
}

fun MethodCondition<*>.toReadableDesc(): String {
    val n = (readGetter("name") as? String)?.takeIf { it.isNotBlank() } ?: "<any>"
    val paramsObj = readGetter("parameters") ?: readGetter("parameterTypes") ?: readGetter("paramTypes")
    val paramCount = (readGetter("parameterCount") as? Int)
    val params = formatParams(paramsObj, paramCount)
    val ret = formatType(readGetter("returnType"))
    return "$n($params)->$ret"
}

private fun formatParams(obj: Any?, paramCount: Int?): String {
    val list: List<Any?>? = when (obj) {
        is Array<*> -> obj.toList()
        is Iterable<*> -> obj.toList()
        null -> null
        else -> null
    }
    return when {
        list != null && list.isNotEmpty() -> list.joinToString(", ") { formatType(it) }
        paramCount != null -> if (paramCount == 0) "" else "<$paramCount params>"
        else -> "<any>"
    }
}

private fun formatType(t: Any?): String = when (t) {
    null -> "<any>"
    is Class<*> -> if (t == Void.TYPE) "void" else t.name
    is KClass<*> -> t.qualifiedName ?: t.toString()
    else -> t.toString()
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

private fun Any.readGetter(prop: String): Any? {
    val suffix = prop.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val candidates = arrayOf("get$suffix", prop)
    for (mn in candidates) {
        val m = this.javaClass.methods.firstOrNull { it.name == mn && it.parameterTypes.isEmpty() } ?: continue
        return runCatching { m.invoke(this) }.getOrNull()
    }
    return null
}