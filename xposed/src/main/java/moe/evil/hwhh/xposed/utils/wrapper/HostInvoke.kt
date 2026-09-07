@file:OptIn(HostInternalApi::class)

package moe.evil.hwhh.xposed.utils.wrapper

import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe

private val invokeLog = HLog("Invoke")

private inline fun <T> guarded(label: () -> String, block: () -> T): T? =
    runCatching(block).onFailure { invokeLog.error { "${label()} <- ${it.describe()}" } }
        .getOrNull()

@Suppress("UNCHECKED_CAST")
fun <R : Any> HostMethod<R>.invoke(instance: Any?, vararg args: Any?): R? =
    member.invoke(instance, *args).let { if (member.returnType == Void.TYPE) Unit else it } as R?

fun <R : Any> HostMethod<R>.invokeOrNull(instance: Any?, vararg args: Any?) =
    guarded({ "Invoke failed for $label" }) { invoke(instance, *args) }

@Suppress("UNCHECKED_CAST")
fun <T : Any> HostField<T>.get(instance: Any?) = member.get(instance) as T?

fun <T : Any> HostField<T>.getOrNull(instance: Any?) =
    guarded({ "Read failed for $label" }) { get(instance) }

fun <T : Any> HostField<T>.set(instance: Any?, value: T?) = member.set(instance, value)

fun <T : Any> HostField<T>.trySet(instance: Any?, value: T?) =
    guarded({ "Write failed for $label" }) { set(instance, value) } != null

@Suppress("UNCHECKED_CAST")
fun <T : Any> HostConstructor<T>.create(vararg args: Any?) = member.newInstance(*args) as T

fun <T : Any> HostConstructor<T>.createOrNull(vararg args: Any?) =
    guarded({ "Create failed for $label" }) { create(*args) }
