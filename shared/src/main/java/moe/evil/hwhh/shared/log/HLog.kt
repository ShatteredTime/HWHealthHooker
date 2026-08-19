package moe.evil.hwhh.shared.log

class HLog @PublishedApi internal constructor(private val defaultTag: String) {
    @PublishedApi
    internal fun enabled(level: LogLevel) = level >= globalMinLevel

    @PublishedApi
    internal fun emit(level: LogLevel, tag: String?, throwable: Throwable?, message: String) =
        sink.emit(level, "${tag?.let { "[$it]" } ?: defaultTag} $message", throwable)

    inline fun debug(tag: String? = null, lazyMessage: () -> String) {
        if (enabled(LogLevel.DEBUG)) emit(LogLevel.DEBUG, tag, null, lazyMessage())
    }

    inline fun info(tag: String? = null, lazyMessage: () -> String) {
        if (enabled(LogLevel.INFO)) emit(LogLevel.INFO, tag, null, lazyMessage())
    }

    inline fun warn(
        throwable: Throwable? = null,
        tag: String? = null,
        lazyMessage: () -> String,
    ) {
        if (enabled(LogLevel.WARN)) emit(LogLevel.WARN, tag, throwable, lazyMessage())
    }

    inline fun error(
        throwable: Throwable? = null,
        tag: String? = null,
        lazyMessage: () -> String,
    ) {
        if (enabled(LogLevel.ERROR)) emit(LogLevel.ERROR, tag, throwable, lazyMessage())
    }

    companion object {
        @Volatile
        var globalMinLevel = LogLevel.DEBUG

        @Volatile
        var sink: LogSink = AndroidLogSink

        inline fun <reified T : Any> of() = HLog("[${T::class.java.simpleName}]")

        operator fun invoke(tag: String) = HLog("[$tag]")
    }
}
