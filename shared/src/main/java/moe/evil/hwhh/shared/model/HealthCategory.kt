package moe.evil.hwhh.shared.model

enum class HealthCategory {
    POINT, SET, SESSION, SEQUENCE, STAT, REALTIME, CONFIG, CONFIGSTAT, CHECK_DWONLOAD,  // huawei's typo
    BUSINESS, UNKNOWN;

    companion object {
        fun of(id: Int) = when {
            id < 1 -> UNKNOWN
            id < 10000 -> POINT
            id < 20000 -> SET
            id < 30000 -> SESSION
            id < 40000 -> SEQUENCE
            id < 50000 -> STAT
            id < 60000 -> REALTIME
            id < 70000 -> BUSINESS
            id < 80000 -> CONFIG
            id < 90000 -> CONFIGSTAT
            id < 100000 -> CHECK_DWONLOAD
            else -> UNKNOWN
        }
    }
}
