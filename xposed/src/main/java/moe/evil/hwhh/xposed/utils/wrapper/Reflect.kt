package moe.evil.hwhh.xposed.utils.wrapper

fun Class<*>.fieldBySerializedName(serializedName: String): HostField<Any>? =
    classLoader?.runCatching { loadClass("com.google.gson.annotations.SerializedName") }
        ?.getOrNull()?.let { raw ->
            @Suppress("UNCHECKED_CAST")
            val annClass = raw as Class<out Annotation>
            val value = raw.getMethod("value")
            generateSequence(this) { it.superclass }
                .takeWhile { it != classOf<Any>() }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { field ->
                    field.getAnnotation(annClass)
                        ?.let { value.invoke(it) as? String } == serializedName
                }
                ?.let { HostField<Any>(it.apply { isAccessible = true }) }
        }
        ?: null.also { resolveLog.warn { "No field with SerializedName($serializedName) in $name" } }

fun Any.hostGsonToJson(): String {
    val gsonClass = checkNotNull(javaClass.classLoader) {
        "ClassLoader missing for ${javaClass.name}"
    }.loadClass("com.google.gson.Gson")
    return gsonClass.getMethod("toJson", classOf<Any>())
        .invoke(gsonClass.getDeclaredConstructor().newInstance(), this) as String
}
