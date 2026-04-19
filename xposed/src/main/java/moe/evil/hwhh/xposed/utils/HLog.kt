package moe.evil.hwhh.xposed.utils

import com.highcapable.yukihookapi.hook.log.YLog

class HLog @PublishedApi internal constructor(private val defaultTag: String) {
    enum class Level { DEBUG, INFO, WARN, ERROR, OFF }

    @Volatile
    var minLevel: Level = Level.DEBUG

    @PublishedApi
    internal fun enabled(level: Level): Boolean =
        level.ordinal >= minLevel.ordinal && level.ordinal >= globalMinLevel.ordinal

    @PublishedApi
    internal fun resolveTag(override: String?): String =
        if (override != null) "[$override]" else defaultTag

    inline fun debug(tag: String? = null, lazyMessage: () -> String) {
        if (enabled(Level.DEBUG)) YLog.debug("${resolveTag(tag)} ${lazyMessage()}")
    }

    inline fun info(tag: String? = null, lazyMessage: () -> String) {
        if (enabled(Level.INFO)) YLog.info("${resolveTag(tag)} ${lazyMessage()}")
    }

    inline fun warn(tag: String? = null, lazyMessage: () -> String) {
        if (enabled(Level.WARN)) YLog.warn("${resolveTag(tag)} ${lazyMessage()}")
    }

    inline fun error(
        throwable: Throwable? = null,
        tag: String? = null,
        lazyMessage: () -> String,
    ) {
        if (!enabled(Level.ERROR)) return
        val msg = "${resolveTag(tag)} ${lazyMessage()}"
        if (throwable != null) YLog.error(msg, throwable) else YLog.error(msg)
    }

    companion object {
        @Volatile
        var globalMinLevel: Level = Level.DEBUG

        inline fun <reified T : Any> of(): HLog = HLog("[${T::class.java.simpleName}]")

        operator fun invoke(tag: String): HLog = HLog("[$tag]")
    }
}
