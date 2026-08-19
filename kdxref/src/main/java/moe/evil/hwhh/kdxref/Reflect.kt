package moe.evil.hwhh.kdxref

import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.makeAccessible

private const val SERIALIZED_NAME = "com.google.gson.annotations.SerializedName"
private const val GSON = "com.google.gson.Gson"

fun Class<*>.fieldBySerializedName(serializedName: String) =
    classLoader?.runCatching { loadClass(SERIALIZED_NAME) }?.getOrNull()?.let { raw ->
        @Suppress("UNCHECKED_CAST")
        val annClass = raw as Class<out Annotation>
        val value = raw.getMethod("value")
        generateSequence(this) { it.superclass }
            .takeWhile { it != classOf<Any>() }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field ->
                field.getAnnotation(annClass)?.let { value.invoke(it) as? String } == serializedName
            }
            ?.apply { makeAccessible() }
    }

fun Any.hostGsonToJson(): String {
    val gsonClass = checkNotNull(javaClass.classLoader) {
        "ClassLoader missing for ${javaClass.name}"
    }.loadClass(GSON)
    return gsonClass.getMethod("toJson", classOf<Any>())
        .invoke(gsonClass.getDeclaredConstructor().newInstance(), this) as String
}

fun Throwable.describe() = generateSequence(this) { it.cause }
    .joinToString(" <- ") { it.toString() }

