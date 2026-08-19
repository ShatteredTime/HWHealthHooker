package moe.evil.hwhh.shared.log

const val LOG_TAG = "hwhh"

fun interface LogSink {
    fun emit(level: LogLevel, message: String, throwable: Throwable?)
}
