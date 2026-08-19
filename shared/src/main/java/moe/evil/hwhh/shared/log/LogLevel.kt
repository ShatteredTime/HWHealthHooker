package moe.evil.hwhh.shared.log

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, OFF;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: DEBUG
    }
}
