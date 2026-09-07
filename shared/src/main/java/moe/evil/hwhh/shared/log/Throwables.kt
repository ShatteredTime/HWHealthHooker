package moe.evil.hwhh.shared.log

fun Throwable.describe() = generateSequence(this) { it.cause }
    .joinToString(" <- ") { it.toString() }
