package moe.evil.hwhh.analyzer

sealed interface AnalysisOutcome<out T> {
    data class Done<out T>(val value: T) : AnalysisOutcome<T>

    data object Cancelled : AnalysisOutcome<Nothing>

    data class Failed(val cause: Throwable) : AnalysisOutcome<Nothing>
}

class AnalysisCancelled : RuntimeException(null, null, false, false)

fun <T> AnalysisOutcome<T>.getOrThrow() = when (this) {
    is AnalysisOutcome.Done -> value
    is AnalysisOutcome.Cancelled -> throw AnalysisCancelled()
    is AnalysisOutcome.Failed -> throw cause
}

inline fun <T> outcomeOf(block: () -> T) = try {
    AnalysisOutcome.Done(block())
} catch (_: AnalysisCancelled) {
    AnalysisOutcome.Cancelled
} catch (failure: Throwable) {
    AnalysisOutcome.Failed(failure)
}
