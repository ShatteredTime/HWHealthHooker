package moe.evil.hwhh.xposed.utils

import com.highcapable.yukihookapi.hook.log.YLog
import moe.evil.hwhh.shared.log.LogLevel
import moe.evil.hwhh.shared.log.LogSink

internal object YLogSink : LogSink {
    override fun emit(level: LogLevel, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> YLog.debug(message, throwable)
            LogLevel.INFO -> YLog.info(message, throwable)
            LogLevel.WARN -> YLog.warn(message, throwable)
            LogLevel.ERROR -> YLog.error(message, throwable)
            LogLevel.OFF -> Unit
        }
    }
}
