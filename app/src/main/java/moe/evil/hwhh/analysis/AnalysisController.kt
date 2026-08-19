package moe.evil.hwhh.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AnalysisState {
    data object Idle : AnalysisState

    data class Running(
        val percent: Int = 0,
        val lines: List<String> = emptyList(),
        val cancelling: Boolean = false,
    ) : AnalysisState

    data class Succeeded(val names: Int, val elapsedMs: Long) : AnalysisState

    data class Failed(val reason: String) : AnalysisState

    data object Cancelled : AnalysisState
}

object AnalysisController {
    private const val LOG_CAPACITY = 200

    private val st = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state = st.asStateFlow()

    private val claimed = AtomicBoolean(false)
    private val lines = ArrayDeque<String>(LOG_CAPACITY)

    @Volatile
    var isCancelled = false
        private set

    fun begin() = claimed.compareAndSet(false, true).also {
        if (it) {
            isCancelled = false
            synchronized(lines) { lines.clear() }
            st.value = AnalysisState.Running()
        }
    }

    fun release() = claimed.set(false)

    fun log(line: String) {
        val snapshot = synchronized(lines) {
            if (lines.size == LOG_CAPACITY) lines.removeFirst()
            lines.addLast(line)
            lines.toList()
        }
        onRunning { it.copy(lines = snapshot) }
    }

    fun progress(fraction: Float) = onRunning {
        it.copy(percent = (fraction * 100).toInt().coerceIn(0, 100))
    }

    fun cancel() {
        isCancelled = true
        onRunning { it.copy(cancelling = true) }
    }

    fun finish(outcome: AnalysisState) {
        st.value = outcome
    }

    fun consume() {
        st.value = AnalysisState.Idle
    }

    private inline fun onRunning(next: (AnalysisState.Running) -> AnalysisState.Running) =
        st.update { if (it is AnalysisState.Running) next(it) else it }
}
